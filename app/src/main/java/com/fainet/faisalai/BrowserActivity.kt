package com.fainet.faisalai

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.material.tabs.TabLayout

/**
 * Web browser. The search box is at the top; under it are the result tabs:
 * AI Says (an AI answer built from live web results), All, Images, Videos, News.
 */
class BrowserActivity : BaseActivity() {

    private companion object {
        const val TAB_AI = 0
        const val TAB_ALL = 1
        // Google's result-type parameter for each tab; AI Says does not use one.
        val SUFFIX = listOf("", "", "&tbm=isch", "&tbm=vid", "&tbm=nws")
    }

    private lateinit var web: WebView
    private lateinit var url: EditText
    private lateinit var modes: TabLayout
    private lateinit var aiPanel: View
    private lateinit var aiText: TextView

    private var query: String? = null // the current search, or null while browsing a page
    private var mode = TAB_ALL
    private var aiRequest = 0 // answers from an older request are ignored

    private var geoOrigin: String? = null
    private var geoCallback: GeolocationPermissions.Callback? = null

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            geoCallback?.invoke(geoOrigin, result.values.any { it }, false)
            geoCallback = null
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)
        web = findViewById(R.id.web)
        url = findViewById(R.id.url)
        modes = findViewById(R.id.modes)
        aiPanel = findViewById(R.id.aiPanel)
        aiText = findViewById(R.id.aiText)
        val progress = findViewById<ProgressBar>(R.id.progress)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.setGeolocationEnabled(true)
        web.settings.builtInZoomControls = true
        web.settings.displayZoomControls = false

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.scheme == "http" || u.scheme == "https") return false
                // tel:, mailto:, intent: and app links go to the matching app.
                try {
                    val intent = Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME)
                    try {
                        startActivity(intent)
                    } catch (e: Exception) {
                        intent.getStringExtra("browser_fallback_url")?.let { view.loadUrl(it) }
                    }
                } catch (e: Exception) {
                    // Unknown link type: stay on the current page.
                }
                return true
            }

            override fun onPageStarted(view: WebView, pageUrl: String, favicon: Bitmap?) {
                if (!url.hasFocus() && query == null) url.setText(pageUrl)
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress >= 100) View.INVISIBLE else View.VISIBLE
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                val granted = ContextCompat.checkSelfPermission(
                    this@BrowserActivity, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
                    this@BrowserActivity, Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    callback.invoke(origin, true, false)
                } else {
                    geoOrigin = origin
                    geoCallback = callback
                    locationPermission.launch(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                    )
                }
            }
        }

        findViewById<ImageButton>(R.id.navBack).setOnClickListener { back() }
        findViewById<ImageButton>(R.id.navForward).setOnClickListener { if (web.canGoForward()) web.goForward() }
        findViewById<ImageButton>(R.id.reload).setOnClickListener { if (mode == TAB_AI) showResults() else web.reload() }

        url.setOnEditorActionListener { _, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_GO) return@setOnEditorActionListener false
            open(url.text.toString())
            url.clearFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(url.windowToken, 0)
            true
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = back()
        })

        listOf(R.string.ai_says, R.string.tab_all, R.string.tab_images, R.string.tab_videos, R.string.tab_news)
            .forEach { modes.addTab(modes.newTab().setText(it), false) }

        val startQuery: String?
        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState)
            query = savedInstanceState.getString("query")
            mode = savedInstanceState.getInt("mode", TAB_ALL)
            startQuery = null
        } else {
            startQuery = intent.getStringExtra("query")
            if (intent.getBooleanExtra("ai", false)) mode = TAB_AI
        }
        modes.getTabAt(mode)?.select()
        modes.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                mode = tab.position
                showResults()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) = showResults()
        })

        if (savedInstanceState == null) {
            val startUrl = intent.getStringExtra("url")
            when {
                startUrl != null -> open(startUrl)
                startQuery != null -> open(startQuery)
                else -> web.loadUrl("https://www.google.com")
            }
        } else if (mode == TAB_AI && query != null) {
            showResults()
        }
    }

    private fun back() {
        when {
            aiPanel.visibility == View.VISIBLE -> modes.getTabAt(TAB_ALL)?.select()
            web.canGoBack() -> web.goBack()
            else -> finish()
        }
    }

    /** Opens a web address, or searches when the text is not an address. */
    private fun open(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        val address = when {
            text.startsWith("http://") || text.startsWith("https://") -> text
            !text.contains(' ') && text.contains('.') -> "https://$text"
            else -> null
        }
        if (address != null) {
            query = null
            aiRequest++
            aiPanel.visibility = View.GONE
            web.visibility = View.VISIBLE
            web.loadUrl(address)
        } else {
            query = text
            url.setText(text)
            showResults()
        }
    }

    /** Shows the current search in the selected tab. */
    private fun showResults() {
        val q = query
        if (q == null) {
            if (mode == TAB_AI) toast(getString(R.string.search_first))
            return
        }
        if (mode == TAB_AI) {
            aiSays(q)
        } else {
            aiRequest++
            aiPanel.visibility = View.GONE
            web.visibility = View.VISIBLE
            web.loadUrl("https://www.google.com/search?q=" + Uri.encode(q) + SUFFIX[mode])
        }
    }

    private fun aiSays(q: String) {
        val request = ++aiRequest
        web.visibility = View.GONE
        aiPanel.visibility = View.VISIBLE
        aiText.text = getString(R.string.thinking)
        val system = "You answer web search questions clearly and accurately. " + Prefs.style(this)

        if (Prefs.tavilyKey(this).isEmpty()) {
            // No live web results: the AI answers from what it knows, and the screen says how to add them.
            AI.ask(this, system, listOf(AI.Turn("user", q)), null) { answer, error ->
                if (request != aiRequest) return@ask
                aiText.text = (answer ?: error ?: "") + "\n\n" + getString(R.string.tavily_hint)
            }
            return
        }
        Tavily.search(this, q) { hits, error ->
            if (request != aiRequest) return@search
            if (hits == null || hits.isEmpty()) {
                aiText.text = error ?: getString(R.string.err_net)
                return@search
            }
            val results = hits.mapIndexed { i, h -> "[${i + 1}] ${h.title}\n${h.url}\n${h.content.take(1200)}" }
                .joinToString("\n\n")
            val sources = getString(R.string.sources) + "\n" +
                hits.mapIndexed { i, h -> "${i + 1}. ${h.title}\n${h.url}" }.joinToString("\n")
            AI.ask(
                this,
                "$system Use only the web results you are given and cite them like [1], [2].",
                listOf(AI.Turn("user", "Question: $q\n\nWeb results:\n$results")),
                null
            ) { answer, aiError ->
                if (request != aiRequest) return@ask
                aiText.text = (answer ?: aiError ?: "") + "\n\n" + sources
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
        outState.putString("query", query)
        outState.putInt("mode", mode)
    }

    override fun onDestroy() {
        aiRequest++
        web.destroy()
        super.onDestroy()
    }
}
