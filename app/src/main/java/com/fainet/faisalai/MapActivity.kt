package com.fainet.faisalai

import android.Manifest
import android.app.AlertDialog
import android.graphics.Color
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import org.json.JSONObject
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Polyline
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
 * streets. Search finds places (pick one from the list), long-press drops a pin,
 * places can be saved, and Route draws the driving route inside the app.
 */
class MapActivity : BaseActivity() {

    private lateinit var map: MapView
    private var me: MyLocationNewOverlay? = null
    private var pin: Marker? = null
    private var pinName = ""
    private var routeLine: Polyline? = null
    private lateinit var results: ListView
    private lateinit var info: TextView

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
        results = findViewById(R.id.results)
        info = findViewById(R.id.info)
        // Press and hold anywhere on the map to drop a pin there.
        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean { results.visibility = View.GONE; return false }
            override fun longPressHelper(p: GeoPoint): Boolean { dropPin(p, ""); reverse(p); return true }
        }))
        findViewById<Button>(R.id.btnSave).setOnClickListener { savePlace() }
        findViewById<Button>(R.id.btnSaved).setOnClickListener { showSaved() }
        findViewById<Button>(R.id.btnDirections).setOnClickListener { route() }
    }

    // ---------- pin, saved places ----------

    private fun dropPin(p: GeoPoint, name: String) {
        pin?.let { map.overlays.remove(it) }
        routeLine?.let { map.overlays.remove(it) }
        routeLine = null
        val marker = Marker(map)
        marker.position = p
        marker.title = name
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        map.overlays.add(marker)
        pin = marker
        pinName = name
        info.text = name
        info.visibility = if (name.isEmpty()) View.GONE else View.VISIBLE
        map.invalidate()
    }

    private fun reverse(p: GeoPoint) {
        val language = resources.configuration.locales[0].language
        Net.pool.execute {
            val name = try {
                val (code, body) = Net.request(
                    "GET", "https://nominatim.openstreetmap.org/reverse?format=json&lat=${p.latitude}&lon=${p.longitude}",
                    mapOf("User-Agent" to "FaisalAI/3.0 ($packageName)", "Accept-Language" to language), null
                )
                if (code in 200..299) JSONObject(body).optString("display_name") else ""
            } catch (e: Exception) { "" }
            Net.main.post {
                val cur = pin?.position
                if (!isDestroyed && name.isNotEmpty() && cur != null && cur.latitude == p.latitude && cur.longitude == p.longitude) {
                    pinName = name
                    pin?.title = name
                    info.text = name
                    info.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun places(): JSONArray = try { JSONArray(Prefs.sp(this).getString("places", "[]")) } catch (e: Exception) { JSONArray() }

    private fun savePlace() {
        val p = pin?.position
        if (p == null) { toast(getString(R.string.map_no_pin)); return }
        val arr = places()
        arr.put(JSONObject().put("n", pinName.ifEmpty { "%.5f, %.5f".format(p.latitude, p.longitude) }).put("la", p.latitude).put("lo", p.longitude))
        Prefs.sp(this).edit().putString("places", arr.toString()).apply()
        toast(getString(R.string.map_saved_ok))
    }

    private fun showSaved() {
        val arr = places()
        if (arr.length() == 0) { toast(getString(R.string.map_none_saved)); return }
        val names = (0 until arr.length()).map { arr.getJSONObject(it).optString("n") }
        AlertDialog.Builder(this)
            .setTitle(R.string.map_pick)
            .setItems(names.toTypedArray()) { _, which ->
                val o = arr.getJSONObject(which)
                val p = GeoPoint(o.getDouble("la"), o.getDouble("lo"))
                dropPin(p, o.optString("n"))
                map.controller.setZoom(16.0)
                map.controller.animateTo(p)
            }
            .setNeutralButton(R.string.map_delete) { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle(R.string.map_delete)
                    .setItems(names.toTypedArray()) { _, which ->
                        val keep = JSONArray()
                        for (i in 0 until arr.length()) if (i != which) keep.put(arr.getJSONObject(i))
                        Prefs.sp(this).edit().putString("places", keep.toString()).apply()
                    }.show()
            }
            .show()
    }

    /** Draws the driving route from my location to the pin, using OSRM (no key needed). */
    private fun route() {
        val to = pin?.position
        if (to == null) { toast(getString(R.string.map_no_pin)); return }
        val from = me?.myLocation
        if (from == null) {
            toast(getString(R.string.map_need_me))
            if (hasLocation()) showMe()
            else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            return
        }
        Net.pool.execute {
            var line: List<GeoPoint>? = null
            var summary = ""
            try {
                val (code, body) = Net.request(
                    "GET",
                    "https://router.project-osrm.org/route/v1/driving/${from.longitude},${from.latitude};${to.longitude},${to.latitude}?overview=full&geometries=geojson",
                    mapOf("User-Agent" to "FaisalAI/3.0 ($packageName)"), null
                )
                if (code in 200..299) {
                    val r = JSONObject(body).optJSONArray("routes")?.optJSONObject(0)
                    if (r != null) {
                        val coords = r.getJSONObject("geometry").getJSONArray("coordinates")
                        line = (0 until coords.length()).map { GeoPoint(coords.getJSONArray(it).getDouble(1), coords.getJSONArray(it).getDouble(0)) }
                        val km = r.getDouble("distance") / 1000.0
                        val min = (r.getDouble("duration") / 60.0).toInt()
                        summary = "%.1f km · %d h %02d min".format(km, min / 60, min % 60)
                    }
                }
            } catch (e: Exception) { }
            Net.main.post {
                if (isDestroyed) return@post
                val pts = line
                if (pts == null || pts.isEmpty()) { toast(getString(R.string.map_no_route)); return@post }
                routeLine?.let { map.overlays.remove(it) }
                val pl = Polyline()
                pl.setPoints(pts)
                pl.outlinePaint.color = Color.parseColor("#2F6BFF")
                pl.outlinePaint.strokeWidth = 14f
                map.overlays.add(pl)
                routeLine = pl
                info.text = summary + (if (pinName.isNotEmpty()) "\n$pinName" else "")
                info.visibility = View.VISIBLE
                map.zoomToBoundingBox(org.osmdroid.util.BoundingBox.fromGeoPoints(pts).increaseByScale(1.3f), true)
                map.invalidate()
            }
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

    /** Finds places by name with OpenStreetMap's Nominatim search and lists the matches. */
    private fun search(text: String) {
        if (text.isEmpty()) return
        val language = resources.configuration.locales[0].language
        Net.pool.execute {
            val found = ArrayList<Triple<String, Double, Double>>()
            var failed = false
            try {
                val (code, body) = Net.request(
                    "GET",
                    "https://nominatim.openstreetmap.org/search?format=json&limit=7&q=" + URLEncoder.encode(text, "UTF-8"),
                    mapOf("User-Agent" to "FaisalAI/3.0 ($packageName)", "Accept-Language" to language),
                    null
                )
                if (code in 200..299) {
                    val arr = JSONArray(body)
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        found.add(Triple(o.optString("display_name", text), o.getString("lat").toDouble(), o.getString("lon").toDouble()))
                    }
                } else failed = true
            } catch (e: Exception) {
                failed = true
            }
            Net.main.post {
                if (isDestroyed) return@post
                if (found.isEmpty()) {
                    toast(getString(if (failed) R.string.err_net else R.string.not_found))
                    return@post
                }
                fun go(t: Triple<String, Double, Double>) {
                    results.visibility = View.GONE
                    val p = GeoPoint(t.second, t.third)
                    dropPin(p, t.first)
                    map.controller.setZoom(16.0)
                    map.controller.animateTo(p)
                }
                if (found.size == 1) { go(found[0]); return@post }
                results.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, found.map { it.first })
                results.setOnItemClickListener { _, _, pos, _ -> go(found[pos]) }
                results.visibility = View.VISIBLE
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
