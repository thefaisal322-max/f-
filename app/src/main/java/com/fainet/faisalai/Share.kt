package com.fainet.faisalai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Phone-to-phone file sharing over the local network, without internet.
 * This phone runs a small web server; the other phone scans the QR code (or opens the
 * address in any browser) to download the chosen files or to send files back.
 *
 * Speed: the app keeps Wi-Fi in its high-performance mode, moves data in 1 MB pieces
 * with large network buffers, and several files can move at once. The limit is then
 * the Wi-Fi link itself; a 5 GHz hotspot or router is several times faster than 2.4 GHz.
 */
class ShareActivity : BaseActivity() {

    private val files = ArrayList<Uri>()
    private var server: ShareServer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var lastBytes = 0L

    private lateinit var chosen: TextView
    private lateinit var start: Button
    private lateinit var info: TextView
    private lateinit var address: TextView
    private lateinit var received: TextView
    private lateinit var qr: ImageView
    private lateinit var qrHint: TextView
    private lateinit var speed: TextView

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            files.clear()
            files.addAll(uris)
            chosen.text = getString(R.string.share_count, files.size)
            if (server != null) {
                end()
                begin()
            }
        }
    }

    /** Once a second while sharing: how many bytes moved since the last look. */
    private val meter = object : Runnable {
        override fun run() {
            val s = server ?: return
            val total = s.bytes.get()
            val moved = total - lastBytes
            lastBytes = total
            if (moved > 0) {
                speed.text = getString(R.string.share_speed, Formatter.formatShortFileSize(this@ShareActivity, moved))
                speed.visibility = View.VISIBLE
            } else {
                speed.visibility = View.GONE
            }
            Net.main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_share)
        bar(R.string.t_share)
        chosen = findViewById(R.id.chosen)
        start = findViewById(R.id.start)
        info = findViewById(R.id.info)
        address = findViewById(R.id.address)
        received = findViewById(R.id.received)
        qr = findViewById(R.id.qr)
        qrHint = findViewById(R.id.qrHint)
        speed = findViewById(R.id.speed)

        findViewById<Button>(R.id.pick).setOnClickListener { picker.launch(arrayOf("*/*")) }
        start.setOnClickListener { if (server == null) begin() else end() }
        findViewById<Button>(R.id.other).setOnClickListener {
            if (files.isEmpty()) {
                picker.launch(arrayOf("*/*"))
            } else {
                val send = Intent(Intent.ACTION_SEND_MULTIPLE)
                    .setType("*/*")
                    .putParcelableArrayListExtra(Intent.EXTRA_STREAM, files)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(send, getString(R.string.share_other)))
            }
        }
    }

    private fun begin() {
        val ip = ShareServer.localIp()
        if (ip == null) {
            toast(getString(R.string.share_no_net))
            return
        }
        val s = ShareServer(applicationContext, ArrayList(files)) { name ->
            runOnUiThread { received.append(getString(R.string.share_received, name) + "\n") }
        }
        if (!s.start()) {
            toast(getString(R.string.share_no_net))
            return
        }
        server = s
        val link = "http://$ip:${ShareServer.PORT}"
        address.text = link
        info.visibility = View.VISIBLE
        address.visibility = View.VISIBLE
        try {
            qr.setImageBitmap(Codes.qr(link, 600))
            qr.visibility = View.VISIBLE
            qrHint.visibility = View.VISIBLE
        } catch (e: Exception) {
            // The code could not be drawn; the address above still works.
        }
        start.setText(R.string.share_stop)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Stop Wi-Fi from saving power during the transfer; power saving slows it down a lot.
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val lockMode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wifi.createWifiLock(lockMode, "FaisalAI:share").apply { acquire() }
        } catch (e: Exception) {
            wifiLock = null // sharing still works, only without the speed-up
        }
        lastBytes = 0L
        Net.main.postDelayed(meter, 1000)
    }

    private fun end() {
        Net.main.removeCallbacks(meter)
        server?.stop()
        server = null
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (e: Exception) {
            // Already released.
        }
        wifiLock = null
        info.visibility = View.GONE
        address.visibility = View.GONE
        qr.visibility = View.GONE
        qrHint.visibility = View.GONE
        speed.visibility = View.GONE
        start.setText(R.string.share_start)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onDestroy() {
        if (server != null) end()
        super.onDestroy()
    }
}

/** Minimal HTTP server: GET / (page), GET /f/N (download), PUT /up?name=X (upload). */
class ShareServer(
    private val ctx: Context,
    private val files: List<Uri>,
    private val onReceived: (String) -> Unit
) {
    companion object {
        const val PORT = 8080
        const val CHUNK = 1024 * 1024 // data moves in 1 MB pieces
        const val SOCKET_BUFFER = 4 * 1024 * 1024 // large network buffers keep a fast link busy

        /** This phone's address on Wi-Fi or on its own hotspot, or null when there is no local network. */
        fun localIp(): String? {
            try {
                for (ni in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if (!ni.isUp || ni.isLoopback) continue
                    for (a in Collections.list(ni.inetAddresses)) {
                        if (a is Inet4Address && a.isSiteLocalAddress) return a.hostAddress
                    }
                }
            } catch (e: Exception) {
                return null
            }
            return null
        }
    }

    /** All file bytes sent and received so far; the screen turns this into a speed. */
    val bytes = AtomicLong()

    private var socket: ServerSocket? = null

    fun start(): Boolean {
        val s = try {
            ServerSocket().apply {
                reuseAddress = true
                receiveBufferSize = SOCKET_BUFFER // must be set before the first connection
                bind(InetSocketAddress(PORT), 50)
            }
        } catch (e: Exception) {
            return false
        }
        socket = s
        thread(isDaemon = true) {
            while (!s.isClosed) {
                val client = try {
                    s.accept()
                } catch (e: Exception) {
                    break // the server was stopped
                }
                // One thread per connection, so several files move at the same time.
                thread(isDaemon = true) {
                    try {
                        handle(client)
                    } catch (e: Exception) {
                        // The other phone dropped the connection; nothing to clean up.
                    } finally {
                        try { client.close() } catch (e: Exception) { }
                    }
                }
            }
        }
        return true
    }

    fun stop() {
        try { socket?.close() } catch (e: Exception) { }
        socket = null
    }

    private fun readLine(ins: InputStream): String {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = ins.read()
            if (b == -1 || b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
        }
        return buf.toString("UTF-8")
    }

    private fun handle(client: Socket) {
        client.soTimeout = 30000
        client.tcpNoDelay = true
        client.sendBufferSize = SOCKET_BUFFER
        val ins = BufferedInputStream(client.getInputStream(), CHUNK)
        val out = client.getOutputStream() // written in 1 MB pieces, so no extra buffering
        val request = readLine(ins).split(" ")
        if (request.size < 2) return
        var length = 0L
        while (true) {
            val header = readLine(ins)
            if (header.isEmpty()) break
            val i = header.indexOf(':')
            if (i > 0 && header.substring(0, i).equals("Content-Length", ignoreCase = true)) {
                length = header.substring(i + 1).trim().toLongOrNull() ?: 0L
            }
        }
        val method = request[0]
        val path = request[1].substringBefore('?')
        val query = request[1].substringAfter('?', "")

        when {
            method == "GET" && path == "/" ->
                respond(out, "200 OK", "text/html; charset=utf-8", page().toByteArray(Charsets.UTF_8))

            method == "GET" && path.startsWith("/f/") -> {
                val index = path.removePrefix("/f/").toIntOrNull()
                if (index == null || index !in files.indices) notFound(out) else sendFile(out, files[index])
            }

            method == "PUT" && path == "/up" -> {
                val raw = query.split("&").firstOrNull { it.startsWith("name=") }?.substringAfter("=") ?: "file"
                val name = File(URLDecoder.decode(raw, "UTF-8")).name
                    .replace(Regex("[\\\\/:*?\"<>|]"), "_").ifEmpty { "file" }
                val dest = unique(File(Storage.dir(ctx, "Received"), name))
                FileOutputStream(dest).use { fo ->
                    val buf = ByteArray(CHUNK)
                    var left = length
                    while (left > 0) {
                        val n = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n <= 0) break
                        fo.write(buf, 0, n)
                        bytes.addAndGet(n.toLong())
                        left -= n
                    }
                }
                respond(out, "200 OK", "text/plain", "ok".toByteArray())
                onReceived(dest.name)
            }

            else -> notFound(out)
        }
        out.flush()
    }

    private fun unique(f: File): File {
        if (!f.exists()) return f
        val base = f.nameWithoutExtension
        val ext = if (f.extension.isEmpty()) "" else "." + f.extension
        var i = 1
        while (true) {
            val candidate = File(f.parentFile, "$base ($i)$ext")
            if (!candidate.exists()) return candidate
            i++
        }
    }

    private fun respond(out: OutputStream, status: String, type: String, body: ByteArray) {
        out.write("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(body)
    }

    private fun notFound(out: OutputStream) = respond(out, "404 Not Found", "text/plain", "Not found".toByteArray())

    /** Name and size of a picked file; size is -1 when the provider does not report it. */
    private fun describe(uri: Uri): Pair<String, Long> {
        var name = "file"
        var size = -1L
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                    if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                }
            }
        } catch (e: Exception) {
            // Keep the defaults; the file can still be streamed.
        }
        return Pair(name, size)
    }

    private fun sendFile(out: OutputStream, uri: Uri) {
        val (name, size) = describe(uri)
        val input = ctx.contentResolver.openInputStream(uri)
        if (input == null) {
            notFound(out)
            return
        }
        val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        val head = StringBuilder("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n")
        head.append("Content-Disposition: attachment; filename*=UTF-8''").append(encoded).append("\r\n")
        if (size >= 0) head.append("Content-Length: ").append(size).append("\r\n")
        head.append("Connection: close\r\n\r\n")
        out.write(head.toString().toByteArray())
        input.use { source ->
            val buf = ByteArray(CHUNK)
            while (true) {
                val n = source.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                bytes.addAndGet(n.toLong())
            }
        }
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun page(): String {
        val links = StringBuilder()
        files.forEachIndexed { i, uri -> links.append("<a href=\"/f/").append(i).append("\">").append(escape(describe(uri).first)).append("</a>") }
        if (files.isEmpty()) links.append("<p>No files offered.</p>")
        // Uploads run three at a time: several files together use a fast link better than one after another.
        return """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Faisal AI Share</title>
<style>body{font-family:sans-serif;background:#111;color:#eee;padding:16px;max-width:520px;margin:auto}
a{display:block;padding:14px;margin:8px 0;border:1px solid #444;border-radius:12px;color:#8ab4ff;text-decoration:none;word-break:break-all}
input,button{font-size:16px;margin-top:8px;max-width:100%}button{padding:10px 18px}</style></head><body>
<h2>Faisal AI Share</h2><h3>Download</h3>$links
<h3>Send to the phone</h3><input type="file" id="f" multiple><br><button onclick="up()">Send</button><p id="s"></p>
<script>async function up(){var s=document.getElementById('s'),fs=Array.from(document.getElementById('f').files);
if(!fs.length){s.textContent='Choose a file first';return}
var done=0,failed=0,next=0;s.textContent='Sending 0 / '+fs.length+'...';
async function worker(){while(next<fs.length){var f=fs[next++];
try{var r=await fetch('/up?name='+encodeURIComponent(f.name),{method:'PUT',body:f});if(!r.ok)failed++}catch(e){failed++}
done++;s.textContent='Sending '+done+' / '+fs.length+'...'}}
await Promise.all([worker(),worker(),worker()]);
s.textContent=failed?('Done, '+failed+' failed'):'Done'}</script></body></html>"""
    }
}
