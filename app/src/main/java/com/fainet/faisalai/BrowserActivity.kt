package com.fainet.faisalai

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.VideoView
import androidx.activity.OnBackPressedCallback
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout

/**
 * The app's own search. Search box on top, then the tabs: AI Says, All, Images, Videos.
 * Results are drawn natively; tapping a web result opens the page inside the app.
 */
class BrowserActivity : BaseActivity() {

    private companion object {
        const val TAB_AI = 0
        const val TAB_ALL = 1
        const val TAB_IMG = 2
        const val TAB_VID = 3
    }

    private class Row(val kind: Int, val title: String = "", val body: String = "", val url: String = "", val thumb: String = "")

    private lateinit var input: EditText
    private lateinit var tabs: TabLayout
    private lateinit var list: RecyclerView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var web: WebView

    private var rows: List<Row> = emptyList()
    private var query: String? = null
    private var mode = TAB_AI
    private var request = 0

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)
        input = findViewById(R.id.url)
        tabs = findViewById(R.id.modes)
        list = findViewById(R.id.list)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        web = findViewById(R.id.web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = WebViewClient()

        val lm = GridLayoutManager(this, 2)
        lm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = if (rows[position].kind == 2) 1 else 2
        }
        list.layoutManager = lm
        list.adapter = Adapter()

        findViewById<ImageButton>(R.id.navBack).setOnClickListener { back() }
        input.setOnEditorActionListener { _, id, _ ->
            if (id != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
            val q = input.text.toString().trim()
            if (q.isNotEmpty()) {
                query = q
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
                run()
            }
            true
        }
        listOf(R.string.ai_says, R.string.tab_all, R.string.tab_images, R.string.tab_videos)
            .forEach { tabs.addTab(tabs.newTab().setText(it), false) }
        tabs.getTabAt(mode)?.select()
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(t: TabLayout.Tab) { mode = t.position; run() }
            override fun onTabUnselected(t: TabLayout.Tab) {}
            override fun onTabReselected(t: TabLayout.Tab) {}
        })
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = back()
        })

        status.setText(R.string.search_first)
        intent.getStringExtra("query")?.let { input.setText(it); query = it; run() }
        intent.getStringExtra("url")?.let { web.visibility = View.VISIBLE; web.loadUrl(it) }
    }

    private fun back() {
        when {
            web.visibility == View.VISIBLE -> if (web.canGoBack()) web.goBack() else closePage()
            else -> finish()
        }
    }

    private fun closePage() {
        web.visibility = View.GONE
        web.loadUrl("about:blank")
    }

    private fun show(r: List<Row>, message: String? = null) {
        rows = r
        list.adapter?.notifyDataSetChanged()
        status.text = message ?: ""
        status.visibility = if (r.isEmpty()) View.VISIBLE else View.GONE
        progress.visibility = View.INVISIBLE
    }

    private fun run() {
        val q = query ?: run { toast(getString(R.string.search_first)); return }
        val my = ++request
        closePage()
        show(emptyList(), getString(R.string.thinking))
        progress.visibility = View.VISIBLE
        when (mode) {
            TAB_AI, TAB_ALL -> Search.web(this, q) { page, error ->
                if (my != request) return@web
                if (page == null) { show(emptyList(), error); return@web }
                val out = ArrayList<Row>()
                if (mode == TAB_AI) {
                    out.add(Row(0, getString(R.string.ai_says), page.answer))
                    page.hits.take(6).forEach { out.add(Row(1, it.site, "", it.url)) }
                } else {
                    page.hits.forEach { out.add(Row(1, it.site, it.snippet, it.url)) }
                    if (out.isEmpty()) out.add(Row(0, getString(R.string.tab_all), page.answer))
                }
                show(out)
            }
            else -> Search.media(this, q, mode == TAB_VID) { media, error ->
                if (my != request) return@media
                if (media == null) { show(emptyList(), error); return@media }
                show(media.map { Row(2, it.title, "", it.url, it.thumb) })
            }
        }
    }

    private fun openRow(r: Row) {
        when {
            r.kind == 1 -> { web.visibility = View.VISIBLE; web.loadUrl(r.url) }
            r.kind == 2 && mode == TAB_VID -> playVideo(r.url)
            r.kind == 2 -> { web.visibility = View.VISIBLE; web.loadUrl(r.url) }
        }
    }

    private fun playVideo(url: String) {
        val d = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val frame = FrameLayout(this)
        val vv = VideoView(this)
        frame.addView(vv, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        val mc = MediaController(this)
        mc.setAnchorView(frame)
        vv.setMediaController(mc)
        vv.setVideoURI(android.net.Uri.parse(url))
        vv.setOnPreparedListener { it.start() }
        vv.setOnErrorListener { _, _, _ -> toast(getString(R.string.err_net)); d.dismiss(); true }
        d.setContentView(frame)
        d.setOnDismissListener { vv.stopPlayback() }
        d.show()
    }

    override fun onDestroy() {
        request++
        web.destroy()
        super.onDestroy()
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemViewType(position: Int) = rows[position].kind
        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder {
            val c = parent.context
            val v: View = if (type == 2) {
                val iv = ImageView(c)
                iv.scaleType = ImageView.ScaleType.CENTER_CROP
                iv.setBackgroundResource(R.drawable.bg_field)
                iv.layoutParams = ViewGroup.MarginLayoutParams(-1, dp(150)).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
                iv
            } else {
                val box = LinearLayout(c)
                box.orientation = LinearLayout.VERTICAL
                box.setPadding(dp(14), dp(12), dp(14), dp(12))
                box.setBackgroundResource(R.drawable.bg_field)
                box.layoutParams = ViewGroup.MarginLayoutParams(-1, -2).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
                val t = TextView(c); t.id = R.id.rowTitle; t.textSize = 15f; t.setTextColor(attrColor(R.attr.colAccent)); t.setTypeface(null, android.graphics.Typeface.BOLD)
                val b = TextView(c); b.id = R.id.rowBody; b.textSize = 15f; b.setTextColor(attrColor(R.attr.colFg)); b.setTextIsSelectable(false)
                box.addView(t); box.addView(b)
                box
            }
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
            val r = rows[i]
            if (r.kind == 2) {
                Img.load(r.thumb, h.itemView as ImageView)
                h.itemView.contentDescription = r.title
            } else {
                h.itemView.findViewById<TextView>(R.id.rowTitle).text = r.title
                val b = h.itemView.findViewById<TextView>(R.id.rowBody)
                b.text = r.body
                b.visibility = if (r.body.isEmpty()) View.GONE else View.VISIBLE
            }
            h.itemView.setOnClickListener { openRow(r) }
        }
    }
}
