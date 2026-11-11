package com.fainet.faisalai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.LocationManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** QR and barcode maker. The code types match res/values/arrays.xml (code_names), in the same order. */
class MakerActivity : BaseActivity() {

    private val formats = listOf(
        BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX, BarcodeFormat.AZTEC, BarcodeFormat.PDF_417,
        BarcodeFormat.CODE_128, BarcodeFormat.CODE_39, BarcodeFormat.EAN_13, BarcodeFormat.EAN_8,
        BarcodeFormat.UPC_A, BarcodeFormat.ITF
    )
    private var made: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_maker)
        bar(R.string.t_maker)
        val type = findViewById<Spinner>(R.id.type)
        val input = findViewById<EditText>(R.id.input)
        val image = findViewById<ImageView>(R.id.code)
        type.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, resources.getStringArray(R.array.code_names)
        )

        findViewById<Button>(R.id.make).setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isEmpty()) {
                toast(getString(R.string.need_text))
                return@setOnClickListener
            }
            val format = formats[type.selectedItemPosition]
            // The first three types are square; the rest are wide.
            val square = type.selectedItemPosition <= 2
            try {
                val bmp = if (square) Codes.make(text, format, 800, 800) else Codes.make(text, format, 1000, 400)
                made = bmp
                image.setImageBitmap(bmp)
                image.visibility = View.VISIBLE
            } catch (e: Exception) {
                made = null
                image.visibility = View.GONE
                toast(getString(R.string.code_bad))
            }
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            val f = save()
            if (f != null) toast(getString(R.string.saved_as, f.name))
        }
        findViewById<Button>(R.id.btnShare).setOnClickListener {
            val f = save() ?: return@setOnClickListener
            val send = Intent(Intent.ACTION_SEND)
                .setType("image/png")
                .putExtra(Intent.EXTRA_STREAM, Storage.uri(this, f))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, getString(R.string.share)))
        }
    }

    /** Saves the code as a PNG in Files. Returns null when there is no code yet or saving failed. */
    private fun save(): File? {
        val bmp = made
        if (bmp == null) {
            toast(getString(R.string.need_text))
            return null
        }
        return try {
            val name = "Code_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png"
            val f = File(Storage.dir(this, "Codes"), name)
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            f
        } catch (e: Exception) {
            toast(getString(R.string.save_failed))
            null
        }
    }
}

/**
 * Currency converter. Rates come from the address in Settings (Exchange rates URL) and
 * are kept on the phone, so converting also works without internet.
 * "USc" is the US cent: one hundredth of a dollar.
 */
class ConverterActivity : BaseActivity() {

    private var rates: JSONObject? = null
    private lateinit var codes: Array<String>
    private lateinit var amount: EditText
    private lateinit var from: Spinner
    private lateinit var to: Spinner
    private lateinit var result: TextView
    private lateinit var rate: TextView
    private lateinit var updated: TextView
    private val number = DecimalFormat("#,##0.####")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_converter)
        bar(R.string.t_convert)
        codes = resources.getStringArray(R.array.cur_codes)
        amount = findViewById(R.id.amount)
        from = findViewById(R.id.from)
        to = findViewById(R.id.to)
        result = findViewById(R.id.result)
        rate = findViewById(R.id.rate)
        updated = findViewById(R.id.updated)

        val names = resources.getStringArray(R.array.cur_names)
        from.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        to.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        from.setSelection(codes.indexOf("USD").coerceAtLeast(0))
        to.setSelection(codes.indexOf("AFN").coerceAtLeast(0))

        val onPick = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = show()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        from.onItemSelectedListener = onPick
        to.onItemSelectedListener = onPick
        amount.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = show()
        })
        findViewById<ImageButton>(R.id.swap).setOnClickListener {
            val a = from.selectedItemPosition
            from.setSelection(to.selectedItemPosition)
            to.setSelection(a)
        }
        findViewById<Button>(R.id.update).setOnClickListener { fetch() }

        // Use the saved rates at once; refresh them when they are older than 12 hours.
        val sp = Prefs.sp(this)
        rates = try {
            JSONObject(sp.getString("rates_json", "") ?: "")
        } catch (e: Exception) {
            null
        }
        show()
        if (rates == null || System.currentTimeMillis() - sp.getLong("rates_time", 0L) > 12 * 3600 * 1000L) fetch()
    }

    /** Units of this currency per one unit of the base currency. */
    private fun perBase(code: String): Double? {
        val r = rates ?: return null
        if (code == "USc") return if (r.has("USD")) r.optDouble("USD") * 100 else null
        return if (r.has(code)) r.optDouble(code) else null
    }

    private fun show() {
        val time = Prefs.sp(this).getLong("rates_time", 0L)
        updated.text = if (rates == null) getString(R.string.conv_none)
        else getString(R.string.conv_updated, SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(time)))

        val a = codes[from.selectedItemPosition]
        val b = codes[to.selectedItemPosition]
        val ra = perBase(a)
        val rb = perBase(b)
        val value = amount.text.toString().toDoubleOrNull()
        if (ra == null || rb == null || ra == 0.0) {
            result.text = "—"
            rate.text = ""
            return
        }
        result.text = if (value == null) "—" else number.format(value / ra * rb) + " " + b
        rate.text = "1 $a = ${number.format(rb / ra)} $b"
    }

    private fun fetch() {
        val url = Prefs.ratesUrl(this)
        Net.pool.execute {
            var fresh: JSONObject? = null
            try {
                val (code, body) = Net.request("GET", url, emptyMap(), null)
                if (code in 200..299) fresh = JSONObject(body).optJSONObject("rates")
            } catch (e: Exception) {
                fresh = null // offline or the address is wrong; the saved rates stay in use
            }
            Net.main.post {
                if (isDestroyed) return@post
                val got = fresh
                if (got == null || got.length() == 0) {
                    toast(getString(R.string.err_net))
                    return@post
                }
                rates = got
                Prefs.sp(this).edit()
                    .putString("rates_json", got.toString())
                    .putLong("rates_time", System.currentTimeMillis())
                    .apply()
                show()
            }
        }
    }
}

/**
 * Weather for a city: now and the next seven days, from Open-Meteo (no key needed).
 * "Google weather" opens Google's own weather page for the same city.
 */
class WeatherActivity : BaseActivity() {

    private var name = "Kabul"
    private var lat = 34.5281
    private var lon = 69.1723
    private var request = 0

    private lateinit var place: TextView
    private lateinit var temp: TextView
    private lateinit var condition: TextView
    private lateinit var details: TextView
    private lateinit var days: TextView

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.any { it }) useMyLocation() else toast(getString(R.string.perm_needed))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_weather)
        bar(R.string.t_weather)
        place = findViewById(R.id.place)
        temp = findViewById(R.id.temp)
        condition = findViewById(R.id.condition)
        details = findViewById(R.id.details)
        days = findViewById(R.id.days)

        val sp = Prefs.sp(this)
        name = sp.getString("weather_name", name) ?: name
        lat = sp.getFloat("weather_lat", lat.toFloat()).toDouble()
        lon = sp.getFloat("weather_lon", lon.toFloat()).toDouble()

        val city = findViewById<EditText>(R.id.city)
        city.setOnEditorActionListener { _, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(city.windowToken, 0)
            findCity(city.text.toString().trim())
            true
        }
        findViewById<Button>(R.id.btnLocate).setOnClickListener {
            if (hasLocation()) useMyLocation()
            else locationPermission.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
        findViewById<Button>(R.id.btnGoogle).setOnClickListener {
            startActivity(Intent(this, BrowserActivity::class.java).putExtra("query", "weather $name"))
        }
        load()
    }

    private fun hasLocation(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun useMyLocation() {
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        val fix = try {
            manager.getProviders(true).mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.time }
        } catch (e: SecurityException) {
            null
        }
        if (fix == null) {
            // The phone has no recent position (location is off or was never used).
            toast(getString(R.string.not_found))
            return
        }
        setPlace(getString(R.string.my_location), fix.latitude, fix.longitude)
    }

    private fun setPlace(newName: String, newLat: Double, newLon: Double) {
        name = newName
        lat = newLat
        lon = newLon
        Prefs.sp(this).edit()
            .putString("weather_name", name)
            .putFloat("weather_lat", lat.toFloat())
            .putFloat("weather_lon", lon.toFloat())
            .apply()
        load()
    }

    private fun findCity(text: String) {
        if (text.isEmpty()) return
        val language = resources.configuration.locales[0].language
        Net.pool.execute {
            var found: JSONObject? = null
            var failed = false
            try {
                val (code, body) = Net.request(
                    "GET",
                    "https://geocoding-api.open-meteo.com/v1/search?count=1&language=$language&name=" +
                        URLEncoder.encode(text, "UTF-8"),
                    emptyMap(), null
                )
                if (code in 200..299) found = JSONObject(body).optJSONArray("results")?.optJSONObject(0) else failed = true
            } catch (e: Exception) {
                failed = true
            }
            Net.main.post {
                if (isDestroyed) return@post
                val hit = found
                if (hit == null) {
                    toast(getString(if (failed) R.string.err_net else R.string.not_found))
                    return@post
                }
                val country = hit.optString("country")
                val label = hit.optString("name", text) + if (country.isNotEmpty()) ", $country" else ""
                setPlace(label, hit.optDouble("latitude"), hit.optDouble("longitude"))
            }
        }
    }

    /** The WMO weather codes Open-Meteo reports, grouped into nine descriptions. */
    private fun describe(code: Int): String = getString(
        when (code) {
            0, 1 -> R.string.w_clear
            2 -> R.string.w_partly
            3 -> R.string.w_cloudy
            45, 48 -> R.string.w_fog
            in 51..57 -> R.string.w_drizzle
            in 61..67 -> R.string.w_rain
            in 71..77, 85, 86 -> R.string.w_snow
            in 80..82 -> R.string.w_showers
            in 95..99 -> R.string.w_thunder
            else -> R.string.w_cloudy
        }
    )

    private fun load() {
        val mine = ++request
        place.text = name
        temp.text = "…"
        condition.text = ""
        details.text = ""
        days.text = ""
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=7"
        Net.pool.execute {
            var json: JSONObject? = null
            try {
                val (code, body) = Net.request("GET", url, emptyMap(), null)
                if (code in 200..299) json = JSONObject(body)
            } catch (e: Exception) {
                json = null
            }
            Net.main.post {
                if (isDestroyed || mine != request) return@post
                val now = json?.optJSONObject("current")
                if (now == null) {
                    temp.text = "—"
                    condition.text = getString(R.string.err_net)
                    return@post
                }
                temp.text = Math.round(now.optDouble("temperature_2m")).toString() + "°C"
                condition.text = describe(now.optInt("weather_code"))
                details.text = listOf(
                    getString(R.string.w_feels, Math.round(now.optDouble("apparent_temperature")).toString()),
                    getString(R.string.w_humidity, now.optInt("relative_humidity_2m").toString()),
                    getString(R.string.w_wind, Math.round(now.optDouble("wind_speed_10m")).toString())
                ).joinToString("\n")

                val daily = json?.optJSONObject("daily")
                val dates = daily?.optJSONArray("time")
                val codes = daily?.optJSONArray("weather_code")
                val highs = daily?.optJSONArray("temperature_2m_max")
                val lows = daily?.optJSONArray("temperature_2m_min")
                if (dates != null && codes != null && highs != null && lows != null) {
                    val parse = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    val print = SimpleDateFormat("EEE d MMM", resources.configuration.locales[0])
                    val lines = ArrayList<String>()
                    for (i in 0 until dates.length()) {
                        val day = try {
                            parse.parse(dates.optString(i))?.let { print.format(it) } ?: dates.optString(i)
                        } catch (e: Exception) {
                            dates.optString(i)
                        }
                        lines.add(
                            "$day   ${Math.round(highs.optDouble(i))}° / ${Math.round(lows.optDouble(i))}°   " +
                                describe(codes.optInt(i))
                        )
                    }
                    days.text = lines.joinToString("\n")
                }
            }
        }
    }
}
