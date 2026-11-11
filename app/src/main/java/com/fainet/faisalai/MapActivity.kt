package com.fainet.faisalai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.net.URLEncoder

/**
 * Navigation: a map of the whole world (OpenStreetMap) that zooms in to single
 * streets. Search finds cities, streets and places; Directions opens the route
 * in Google Maps.
 */
class MapActivity : BaseActivity() {

    private lateinit var map: MapView
    private var me: MyLocationNewOverlay? = null
    private var pin: Marker? = null

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.any { it }) showMe() else toast(getString(R.string.perm_needed))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The map server requires every app to identify itself.
        Configuration.getInstance().load(applicationContext, Prefs.sp(this))
        Configuration.getInstance().userAgentValue = packageName

        setContentView(R.layout.activity_map)
        bar(R.string.t_nav)
        map = findViewById(R.id.map)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.minZoomLevel = 3.0
        map.controller.setZoom(3.5)
        map.controller.setCenter(GeoPoint(30.0, 60.0))
        map.overlays.add(CopyrightOverlay(this))

        val q = findViewById<EditText>(R.id.q)
        q.setOnEditorActionListener { _, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(q.windowToken, 0)
            search(q.text.toString().trim())
            true
        }
        findViewById<Button>(R.id.btnLocate).setOnClickListener {
            if (hasLocation()) showMe()
            else locationPermission.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
        findViewById<Button>(R.id.btnDirections).setOnClickListener {
            // Route to the searched place, or to the middle of the map when nothing was searched.
            val lat = pin?.position?.latitude ?: map.mapCenter.latitude
            val lon = pin?.position?.longitude ?: map.mapCenter.longitude
            startActivity(
                Intent(this, BrowserActivity::class.java)
                    .putExtra("url", "https://www.google.com/maps/dir/?api=1&destination=$lat,$lon")
            )
        }
    }

    private fun hasLocation(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun showMe() {
        val overlay = me ?: MyLocationNewOverlay(GpsMyLocationProvider(this), map).also {
            me = it
            map.overlays.add(it)
        }
        overlay.enableMyLocation()
        val known = overlay.myLocation
        if (known != null) {
            map.controller.setZoom(17.0)
            map.controller.animateTo(known)
        } else {
            overlay.runOnFirstFix {
                runOnUiThread {
                    val fix = overlay.myLocation ?: return@runOnUiThread
                    map.controller.setZoom(17.0)
                    map.controller.animateTo(fix)
                }
            }
        }
    }

    /** Finds a place by name with OpenStreetMap's Nominatim search. */
    private fun search(text: String) {
        if (text.isEmpty()) return
        val language = resources.configuration.locales[0].language
        Net.pool.execute {
            var point: GeoPoint? = null
            var name = text
            var failed = false
            try {
                val (code, body) = Net.request(
                    "GET",
                    "https://nominatim.openstreetmap.org/search?format=json&limit=1&q=" + URLEncoder.encode(text, "UTF-8"),
                    mapOf("User-Agent" to "FaisalAI/2.0 ($packageName)", "Accept-Language" to language),
                    null
                )
                if (code in 200..299) {
                    val first = JSONArray(body).optJSONObject(0)
                    if (first != null) {
                        point = GeoPoint(first.getString("lat").toDouble(), first.getString("lon").toDouble())
                        name = first.optString("display_name", text)
                    }
                } else {
                    failed = true
                }
            } catch (e: Exception) {
                failed = true
            }
            Net.main.post {
                val p = point
                if (isDestroyed) return@post
                if (p == null) {
                    toast(getString(if (failed) R.string.err_net else R.string.not_found))
                    return@post
                }
                pin?.let { map.overlays.remove(it) }
                val marker = Marker(map)
                marker.position = p
                marker.title = name
                marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                map.overlays.add(marker)
                pin = marker
                map.controller.setZoom(16.0)
                map.controller.animateTo(p)
                map.invalidate()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        if (me != null && hasLocation()) me?.enableMyLocation()
    }

    override fun onPause() {
        me?.disableMyLocation()
        map.onPause()
        super.onPause()
    }
}
