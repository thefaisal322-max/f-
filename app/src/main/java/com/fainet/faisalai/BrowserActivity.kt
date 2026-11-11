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
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * In-app web browser. Also used for Navigation, where it opens Google Maps
 * and lets the page use the phone's location.
 */
class BrowserActivity : BaseActivity() {

    private lateinit var web: WebView
    private lateinit var url: EditText
    private var geoOrigin: String? = null
    private var geoCallback: GeolocationPermissions.Callback? = null

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val ok = result.values.any { it }
            geoCallback?.invoke(geoOrigin, ok, false)
            geoCallback = null
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)
        web = findViewById(R.id.web)
        url = findViewById(R.id.url)
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
                // tel:, mailto:, intent: and app links go to the matching app, e.g. Google Maps.
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
                if (!url.hasFocus()) url.setText(pageUrl)
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

        findViewById<ImageButton>(R.id.navBack).setOnClickListener { if (web.canGoBack()) web.goBack() else finish() }
        findViewById<ImageButton>(R.id.navForward).setOnClickListener { if (web.canGoForward()) web.goForward() }
        findViewById<ImageButton>(R.id.reload).setOnClickListener { web.reload() }

        url.setOnEditorActionListener { _, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_GO) return@setOnEditorActionListener false
            open(url.text.toString())
            url.clearFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(url.windowToken, 0)
            true
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState)
        } else {
            val start = intent.getStringExtra("url")
            val query = intent.getStringExtra("query")
            when {
                start != null -> web.loadUrl(start)
                query != null -> open(query)
                else -> web.loadUrl("https://www.google.com")
            }
        }
    }

    /** Loads a web address, or searches Google when the text is not an address. */
    private fun open(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        val target = when {
            text.startsWith("http://") || text.startsWith("https://") -> text
            !text.contains(' ') && text.contains('.') -> "https://$text"
            else -> "https://www.google.com/search?q=" + Uri.encode(text)
        }
        web.loadUrl(target)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
