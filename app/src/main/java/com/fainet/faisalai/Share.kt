package com.fainet.faisalai

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.text.format.Formatter
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.io.File
import java.util.concurrent.Executors

/**
 * Share Files. Receive shows a QR code; Send scans it. After that both phones are
 * connected and either one can pick files and send them to the other.
 *
 * Speed: Wi-Fi is kept in its fastest mode, files go straight from storage to the network
 * without copies, over up to 4 connections at once, and big files are cut into 4 parts.
 * The limit is then the Wi-Fi link itself; 5 GHz is several times faster than 2.4 GHz.
 */
class ShareActivity : BaseActivity() {

    private enum class Panel { HOME, RECEIVE, SCAN, CONNECTED }

    private val deviceName = Build.MODEL ?: "Android"
    private var server: ShareServer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var peer: Peer? = null
    private var panel = Panel.HOME

    private val selected = LinkedHashMap<String, Item>()
    private var shown: List<Item> = emptyList()
    private var tab = 0
    private var sending = false
    private var sendTotal = 0L
    private var sendBase = 0L
    private var lastBytes = 0L
    private var lastTime = 0L
    private var smooth = 0.0
    private var loadToken = 0

    private lateinit var pHome: View
    private lateinit var pReceive: View
    private lateinit var pScan: View
    private lateinit var pConnected: View
    private lateinit var qr: ImageView
    private lateinit var myAddress: TextView
    private lateinit var preview: PreviewView
    private lateinit var peerName: TextView
    private lateinit var state: TextView
    private lateinit var speed: TextView
    private lateinit var progress: ProgressBar
    private lateinit var tabs: TabLayout
    private lateinit var items: RecyclerView
    private lateinit var emptyBox: View
    private lateinit var empty: TextView
    private lateinit var emptyBtn: Button
    private lateinit var selText: TextView
    private lateinit var toolbar: androidx.appcompat.widget.Toolbar

    private val scanThread = Executors.newSingleThreadExecutor()
    private val reader = MultiFormatReader()
    private var lastScan = 0L
    private var rotate = false
    private var handled = false

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else { toast(getString(R.string.perm_needed)); show(Panel.HOME) }
    }
    private val mediaPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { loadTab() }
    private val browse = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        val list = uris.map { u ->
            val name = Storage.displayName(this, u)
            Item(u.toString(), name, u, ShareClient.sizeOf(this, u))
        }
        picked.addAll(list)
        list.forEach { selected[it.key] = it }
        loadTab()
        updateBar()
    }
    private val picked = ArrayList<Item>()

    private val meter = object : Runnable {
        override fun run() {
            if (panel != Panel.CONNECTED) return
            val now = SystemClock.elapsedRealtime()
            val total = Meter.bytes.get()
            val dt = (now - lastTime).coerceAtLeast(1)
            val inst = (total - lastBytes) * 1000.0 / dt
            lastBytes = total
            lastTime = now
            smooth = if (inst > 0) smooth * 0.4 + inst * 0.6 else smooth * 0.5
            if (smooth < 1024) smooth = 0.0
            speed.text = getString(R.string.sh_mbs, String.format("%.1f", smooth / 1048576.0))
            if (sending && sendTotal > 0) {
                val pct = ((total - sendBase) * 100 / sendTotal).toInt().coerceIn(0, 100)
                progress.progress = pct * 10
                state.text = getString(R.string.sh_sending, pct)
            }
            Net.main.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_share)
        toolbar = bar(R.string.sh_title)
        pHome = findViewById(R.id.pHome)
        pReceive = findViewById(R.id.pReceive)
        pScan = findViewById(R.id.pScan)
        pConnected = findViewById(R.id.pConnected)
        qr = findViewById(R.id.qr)
        myAddress = findViewById(R.id.myAddress)
        preview = findViewById(R.id.preview)
        peerName = findViewById(R.id.peerName)
        state = findViewById(R.id.state)
        speed = findViewById(R.id.speed)
        progress = findViewById(R.id.progress)
        tabs = findViewById(R.id.shareTabs)
        items = findViewById(R.id.items)
        emptyBox = findViewById(R.id.emptyBox)
        empty = findViewById(R.id.empty)
        emptyBtn = findViewById(R.id.emptyBtn)
        selText = findViewById(R.id.selText)

        reader.setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))

        findViewById<View>(R.id.btnReceive).setOnClickListener { startReceive() }
        findViewById<View>(R.id.btnSend).setOnClickListener { startSend() }
        findViewById<Button>(R.id.cancelReceive).setOnClickListener { stopAll(); show(Panel.HOME) }
        findViewById<View>(R.id.goBtn).setOnClickListener { sendSelected() }
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (panel == Panel.HOME) finish() else { stopAll(); show(Panel.HOME) }
            }
        })

        items.layoutManager = GridLayoutManager(this, 3)
        items.adapter = ItemAdapter()
        listOf(R.string.sh_photos, R.string.sh_videos, R.string.sh_music, R.string.sh_apps, R.string.sh_files, R.string.sh_received)
            .forEach { tabs.addTab(tabs.newTab().setText(it)) }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(t: TabLayout.Tab) {
                tab = t.position
                loadTab()
                if (tab == 4 && picked.isEmpty()) browse.launch(arrayOf("*/*"))
            }
            override fun onTabUnselected(t: TabLayout.Tab) {}
            override fun onTabReselected(t: TabLayout.Tab) { if (t.position == 4) browse.launch(arrayOf("*/*")) }
        })
        show(Panel.HOME)
    }

    private fun show(p: Panel) {
        panel = p
        pHome.visibility = if (p == Panel.HOME) View.VISIBLE else View.GONE
        pReceive.visibility = if (p == Panel.RECEIVE) View.VISIBLE else View.GONE
        pScan.visibility = if (p == Panel.SCAN) View.VISIBLE else View.GONE
        pConnected.visibility = if (p == Panel.CONNECTED) View.VISIBLE else View.GONE
        if (p == Panel.CONNECTED) {
            lastBytes = Meter.bytes.get()
            lastTime = SystemClock.elapsedRealtime()
            Net.main.removeCallbacks(meter)
            Net.main.postDelayed(meter, 500)
            loadTab()
            updateBar()
        }
    }

    // ---------- connecting ----------

    private fun startServer(): Boolean {
        stopServer()
        val s = ShareServer(applicationContext, deviceName,
            onHello = { p -> Net.main.post { if (!isDestroyed && server != null) connected(p) } },
            onReceived = { fname -> Net.main.post {
                if (!isDestroyed) {
                    toast(if (fname.isEmpty()) getString(R.string.save_failed) else getString(R.string.sh_got, fname))
                    if (tab == 5) loadTab()
                }
            } })
        if (!s.start()) { toast(getString(R.string.share_no_net)); return false }
        server = s
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wifi.createWifiLock(mode, "FaisalAI:share").apply { acquire() }
        } catch (e: Exception) {
            wifiLock = null
        }
        return true
    }

    private fun stopServer() {
        server?.stop()
        server = null
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (e: Exception) { }
        wifiLock = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun stopAll() {
        Net.main.removeCallbacks(meter)
        stopServer()
        peer = null
        sending = false
        selected.clear()
        picked.clear()
        handled = false
        try { ProcessCameraProvider.getInstance(this).get().unbindAll() } catch (e: Exception) { }
    }

    private fun startReceive() {
        val ip = ShareServer.localIp()
        if (ip == null) { toast(getString(R.string.share_no_net)); return }
        if (!startServer()) return
        val code = "FSHARE|$ip|${ShareServer.PORT}|${deviceName.replace("|", " ")}"
        qr.setImageBitmap(Codes.qr(code, 700))
        myAddress.text = "$deviceName · $ip"
        show(Panel.RECEIVE)
    }

    private fun startSend() {
        handled = false
        show(Panel.SCAN)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun connected(p: Peer) {
        peer = p
        peerName.text = getString(R.string.sh_connected, p.name)
        state.text = getString(R.string.sh_idle)
        try { ProcessCameraProvider.getInstance(this).get().unbindAll() } catch (e: Exception) { }
        if (panel != Panel.CONNECTED) show(Panel.CONNECTED)
    }

    private fun onCode(text: String) {
        if (handled || panel != Panel.SCAN) return
        val f = text.split("|")
        if (f.size < 4 || f[0] != "FSHARE") { toast(getString(R.string.sh_bad_qr)); return }
        handled = true
        val ip = f[1]
        val port = f[2].toIntOrNull() ?: ShareServer.PORT
        val name = f[3]
        if (!startServer()) { handled = false; return }
        Net.pool.execute {
            val reply = ShareClient.hello(ip, port, deviceName)
            Net.main.post {
                if (isDestroyed) return@post
                if (reply == null) {
                    toast(getString(R.string.sh_fail))
                    stopServer()
                    handled = false
                } else connected(Peer(ip, port, reply.ifEmpty { name }))
            }
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val view = Preview.Builder().build()
                view.setSurfaceProvider(preview.surfaceProvider)
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(scanThread) { image -> scan(image) }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, view, analysis)
            } catch (e: Exception) {
                toast(getString(R.string.cam_wait))
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun scan(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastScan < 200) return
            lastScan = now
            val plane = image.planes[0]
            val w = image.width
            val h = image.height
            val stride = plane.rowStride
            val data = ByteArray(stride * h)
            plane.buffer.get(data, 0, minOf(plane.buffer.remaining(), data.size))
            rotate = !rotate
            val source = if (rotate) {
                val turned = ByteArray(w * h)
                for (y in 0 until h) { val row = y * stride; for (x in 0 until w) turned[x * h + (h - 1 - y)] = data[row + x] }
                PlanarYUVLuminanceSource(turned, h, w, 0, 0, h, w, false)
            } else PlanarYUVLuminanceSource(data, stride, h, 0, 0, w, h, false)
            val found = try { reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text }
            catch (e: NotFoundException) { null } finally { reader.reset() }
            if (found != null) Net.main.post { onCode(found) }
        } catch (e: Exception) {
            // skip this frame
        } finally {
            image.close()
        }
    }

    // ---------- choosing and sending ----------

    private fun sendSelected() {
        val p = peer
        if (p == null || selected.isEmpty()) return
        if (sending) return
        val list = selected.values.toList()
        sendTotal = list.sumOf { if (it.size > 0) it.size else 0L }
        sendBase = Meter.bytes.get()
        sending = true
        progress.visibility = View.VISIBLE
        progress.progress = 0
        selected.clear()
        updateBar()
        items.adapter?.notifyDataSetChanged()
        ShareClient.send(this, p, list) { ok, bad ->
            sending = false
            progress.visibility = View.INVISIBLE
            if (!isDestroyed) {
                state.text = getString(R.string.sh_idle)
                toast(getString(R.string.sh_done, ok, bad))
            }
        }
    }

    private fun updateBar() {
        if (selected.isEmpty()) selText.setText(R.string.sh_none)
        else selText.text = getString(R.string.sh_sel, selected.size, Formatter.formatShortFileSize(this, selected.values.sumOf { maxOf(it.size, 0L) }))
    }

    private fun hasMediaAccess(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= 33)
            listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
        else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        return needed.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun askMedia() {
        mediaPermission.launch(
            if (Build.VERSION.SDK_INT >= 33)
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
            else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        )
    }

    private fun spanFor(t: Int) = when (t) { 0, 1 -> 3; 3 -> 4; else -> 1 }

    private fun setShown(list: List<Item>, message: Int = R.string.sh_empty) {
        shown = list
        (items.layoutManager as GridLayoutManager).spanCount = spanFor(tab)
        items.adapter?.notifyDataSetChanged()
        emptyBox.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        empty.setText(message)
        emptyBtn.visibility = View.GONE
    }

    private fun loadTab() {
        val my = ++loadToken
        val t = tab
        if (t == 4) {
            setShown(picked)
            emptyBtn.visibility = View.VISIBLE
            emptyBtn.setText(R.string.sh_browse)
            emptyBtn.setOnClickListener { browse.launch(arrayOf("*/*")) }
            if (picked.isNotEmpty()) emptyBox.visibility = View.GONE
            return
        }
        if (t < 3 && !hasMediaAccess()) {
            setShown(emptyList(), R.string.sh_perm)
            emptyBtn.visibility = View.VISIBLE
            emptyBtn.setText(R.string.sh_allow)
            emptyBtn.setOnClickListener { askMedia() }
            return
        }
        Net.pool.execute {
            val list = try {
                when (t) {
                    0 -> query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                    1 -> query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                    2 -> query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
                    3 -> apps()
                    else -> received()
                }
            } catch (e: Exception) {
                emptyList()
            }
            Net.main.post { if (my == loadToken && !isDestroyed) setShown(list) }
        }
    }

    private fun query(base: Uri): List<Item> {
        val out = ArrayList<Item>()
        contentResolver.query(
            base, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE),
            null, null, MediaStore.MediaColumns.DATE_ADDED + " DESC"
        )?.use { c ->
            while (c.moveToNext() && out.size < 1500) {
                val id = c.getLong(0)
                val uri = Uri.withAppendedPath(base, id.toString())
                out.add(Item(uri.toString(), c.getString(1) ?: "file", uri, c.getLong(2), id))
            }
        }
        return out
    }

    private fun apps(): List<Item> {
        val pm = packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(main, 0).mapNotNull { r ->
            val ai = r.activityInfo.applicationInfo
            val f = File(ai.sourceDir)
            if (!f.exists()) null
            else Item("app:" + ai.packageName, r.loadLabel(pm).toString() + ".apk", Uri.fromFile(f), f.length(), 0L, ai.packageName)
        }.sortedBy { it.name.lowercase() }
    }

    private fun received(): List<Item> =
        Public.list(this, "Share").map { Item("r:" + it.uri, it.name, it.uri, it.size) }

    private fun open(item: Item) {
        try {
            val uri = if (item.uri.scheme == "file") Storage.uri(this, File(item.uri.path ?: return)) else item.uri
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, Public.mime(item.name)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            toast(getString(R.string.no_app))
        }
    }

    private fun thumb(item: Item, iv: ImageView) {
        iv.tag = item.key
        iv.setImageDrawable(null)
        Net.pool.execute {
            var bmp: Bitmap? = null
            try {
                bmp = when {
                    item.pkg.isNotEmpty() -> {
                        val d = packageManager.getApplicationIcon(item.pkg)
                        val b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                        val cv = android.graphics.Canvas(b)
                        d.setBounds(0, 0, 96, 96)
                        d.draw(cv)
                        b
                    }
                    Build.VERSION.SDK_INT >= 29 -> contentResolver.loadThumbnail(item.uri, Size(260, 260), null)
                    tab == 0 -> {
                        val o = BitmapFactory.Options().apply { inSampleSize = 8 }
                        contentResolver.openInputStream(item.uri)?.use { BitmapFactory.decodeStream(it, null, o) }
                    }
                    else -> null
                }
            } catch (e: Exception) {
                bmp = null
            }
            if (bmp != null) Net.main.post { if (iv.tag == item.key) iv.setImageBitmap(bmp) }
        }
    }

    private inner class ItemAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = shown.size
        override fun getItemViewType(position: Int) = spanFor(tab)

        override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder {
            val c = parent.context
            val root = FrameLayout(c)
            val size = resources.displayMetrics.widthPixels
            when (type) {
                3, 4 -> { // media thumbnails and app icons
                    val cell = if (type == 3) size / 3 else size / 4
                    root.layoutParams = ViewGroup.MarginLayoutParams(cell, if (type == 3) cell else cell + dp(28)).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }
                    val iv = ImageView(c)
                    iv.id = R.id.rowIcon
                    iv.scaleType = if (type == 3) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
                    root.addView(iv, FrameLayout.LayoutParams(-1, if (type == 3) -1 else cell - dp(16)).apply { if (type == 4) topMargin = dp(8) })
                    val label = TextView(c)
                    label.id = R.id.rowBody
                    label.textSize = 12f
                    label.maxLines = 1
                    label.gravity = Gravity.CENTER
                    label.setTextColor(attrColor(R.attr.colFg))
                    if (type == 4) root.addView(label, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
                }
                else -> { // rows: music, files, received
                    root.layoutParams = ViewGroup.MarginLayoutParams(-1, -2)
                    val row = LinearLayout(c)
                    row.orientation = LinearLayout.HORIZONTAL
                    row.gravity = Gravity.CENTER_VERTICAL
                    row.setPadding(dp(14), dp(10), dp(14), dp(10))
                    val iv = ImageView(c)
                    iv.id = R.id.rowIcon
                    iv.setImageResource(R.drawable.ic_file)
                    row.addView(iv, LinearLayout.LayoutParams(dp(36), dp(36)))
                    val col = LinearLayout(c)
                    col.orientation = LinearLayout.VERTICAL
                    val n = TextView(c); n.id = R.id.rowTitle; n.textSize = 15f; n.maxLines = 1; n.setTextColor(attrColor(R.attr.colFg))
                    val s = TextView(c); s.id = R.id.rowBody; s.textSize = 12f; s.setTextColor(attrColor(R.attr.colMuted))
                    col.addView(n); col.addView(s)
                    row.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
                    root.addView(row, FrameLayout.LayoutParams(-1, -2))
                }
            }
            val mark = TextView(c)
            mark.id = R.id.rowMark
            mark.gravity = Gravity.CENTER
            mark.textSize = 14f
            mark.setTextColor(0xFFFFFFFF.toInt())
            val lp = FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.END)
            lp.setMargins(dp(6), dp(6), dp(10), dp(6))
            if (type == 1) lp.gravity = Gravity.CENTER_VERTICAL or Gravity.END
            root.addView(mark, lp)
            return object : RecyclerView.ViewHolder(root) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
            val item = shown[i]
            val v = h.itemView
            val mark = v.findViewById<TextView>(R.id.rowMark)
            val on = selected.containsKey(item.key)
            mark.text = if (on) "✓" else ""
            mark.setBackgroundResource(if (on) R.drawable.bg_check_on else R.drawable.bg_check_off)
            val type = spanFor(tab)
            if (type == 3 || type == 4) {
                thumb(item, v.findViewById(R.id.rowIcon))
                v.findViewById<TextView?>(R.id.rowBody)?.text = item.name.removeSuffix(".apk")
            } else {
                v.findViewById<TextView>(R.id.rowTitle).text = item.name
                v.findViewById<TextView>(R.id.rowBody).text = Formatter.formatShortFileSize(this@ShareActivity, maxOf(item.size, 0L))
            }
            v.setOnClickListener {
                if (tab == 5) { open(item); return@setOnClickListener }
                if (selected.remove(item.key) == null) selected[item.key] = item
                notifyItemChanged(i)
                updateBar()
            }
            if (tab == 5) mark.visibility = View.GONE else mark.visibility = View.VISIBLE
        }
    }

    override fun onDestroy() {
        stopAll()
        scanThread.shutdown()
        super.onDestroy()
    }
}
