package com.fainet.faisalai

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Base64
import android.util.TypedValue
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.FileProvider
import androidx.core.os.LocaleListCompat
import androidx.preference.PreferenceManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.MultiFormatWriter
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.applyNight(Prefs.theme(this))
        // Count the screens on show, to know when the whole app goes to the background (for the PIN).
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(a: android.app.Activity) {
                if (started == 0) Lock.appForeground()
                started++
            }
            override fun onActivityStopped(a: android.app.Activity) {
                started--
                if (started <= 0) Lock.appBackground()
            }
            override fun onActivityCreated(a: android.app.Activity, b: Bundle?) {}
            override fun onActivityResumed(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
    }
}

/** Everything the Settings screen stores. */
object Prefs {
    fun sp(c: Context): SharedPreferences = PreferenceManager.getDefaultSharedPreferences(c)
    private fun str(c: Context, key: String, def: String = ""): String = (sp(c).getString(key, def) ?: def).trim()

    /** One of: classic, black, white, neon. Values from version 1 are mapped. */
    fun theme(c: Context): String = when (str(c, "theme", "classic")) {
        "black", "dark" -> "black"
        "white", "light" -> "white"
        "neon" -> "neon"
        else -> "classic"
    }

    fun themeRes(theme: String): Int = when (theme) {
        "black" -> R.style.Theme_FaisalAI_Black
        "white" -> R.style.Theme_FaisalAI_White
        "neon" -> R.style.Theme_FaisalAI_Neon
        else -> R.style.Theme_FaisalAI_Classic
    }

    /** Black and Neon are dark, White is light, Classic follows the phone. */
    fun applyNight(theme: String) {
        AppCompatDelegate.setDefaultNightMode(
            when (theme) {
                "black", "neon" -> AppCompatDelegate.MODE_NIGHT_YES
                "white" -> AppCompatDelegate.MODE_NIGHT_NO
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    fun applyLang(tag: String) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    fun provider(c: Context): String = str(c, "provider", "auto")
    // Keys come from build variables; the Settings fields remain as an override.
    fun cfAccount(c: Context): String = str(c, "cf_account").ifEmpty { BuildConfig.CF_ACCOUNT_ID }
    fun cfToken(c: Context): String = str(c, "cf_token").ifEmpty { BuildConfig.CF_API_TOKEN }
    fun cfModel(c: Context): String = str(c, "cf_model").ifEmpty { "@cf/mistralai/mistral-small-3.1-24b-instruct" }
    fun geminiKey(c: Context): String = str(c, "gemini_key").ifEmpty { str(c, "api_key") }.ifEmpty { BuildConfig.GEMINI_API_KEY }
    fun geminiModel(c: Context): String = str(c, "model").ifEmpty { "gemini-flash-latest" }
    fun ratesUrl(c: Context): String = str(c, "rates_url").ifEmpty { "https://open.er-api.com/v6/latest/USD" }
    fun ttsModel(c: Context): String = str(c, "tts_model").ifEmpty { "gemini-3.8-flash-tts" }
    fun speak(c: Context): Boolean = sp(c).getBoolean("speak", false)

    fun sound(c: Context): Boolean = sp(c).getBoolean("sound", false)
    fun keepHistory(c: Context): Boolean = sp(c).getBoolean("keep_history", true)
    fun frontCam(c: Context): Boolean = sp(c).getString("bvr_cam", "back") == "front"
    fun quality(c: Context): String = sp(c).getString("bvr_quality", "HD") ?: "HD"

    /** English name of the language the app is shown in, for AI instructions. */
    fun appLangName(c: Context): String =
        when (c.resources.configuration.locales[0].language) {
            "ps" -> "Pashto"
            "fa" -> "Dari"
            else -> "English"
        }

    /** Answer length and language instructions built from Settings. */
    fun style(c: Context): String {
        val len = when (sp(c).getString("len", "medium")) {
            "short" -> "Keep every answer to one to three sentences."
            "long" -> "Give thorough, detailed answers."
            else -> "Keep answers concise: a short paragraph or a few lines."
        }
        val lang = if (sp(c).getString("reply_lang", "app") == "user")
            "Answer in the language of the user's latest message."
        else "Always answer in ${appLangName(c)}."
        return "$len $lang Write plain text without Markdown symbols."
    }
}

/** Plain HTTP helper and the shared background threads. */
object Net {
    val pool: ExecutorService = Executors.newCachedThreadPool()
    val main = Handler(Looper.getMainLooper())

    /** Returns the HTTP status and the response text. Throws IOException when the server cannot be reached. */
    fun request(method: String, url: String, headers: Map<String, String>, body: String?): Pair<Int, String> {
        val con = URL(url).openConnection() as HttpURLConnection
        try {
            con.requestMethod = method
            con.connectTimeout = 20000
            con.readTimeout = 120000
            headers.forEach { (k, v) -> con.setRequestProperty(k, v) }
            if (body != null) {
                con.doOutput = true
                con.setRequestProperty("Content-Type", "application/json")
                con.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = con.responseCode
            val stream = if (code in 200..299) con.inputStream else con.errorStream
            return Pair(code, stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "")
        } finally {
            con.disconnect()
        }
    }
}

/**
 * The app's AI. It talks to Cloudflare Workers AI, Mistral or Google Gemini,
 * whichever is chosen in Settings and has a key.
 */
object AI {
    /** Who the assistant says it is. */
    const val IDENTITY = "Your name is F+ (F plus). If asked who you are, say: \"I am F+.\" " +
        "If asked when you were made or who made you, say: \"I was made by Faisal Hadawal in 2026, and he powered me with faiNET.\" " +
        "Never say you are Gemini, Mistral, Claude, ChatGPT or a product of any other company. "

    /** role is "user" or "model". [show] is what the chat bubble displays when it differs from what is sent. */
    class Turn(val role: String, val text: String, val show: String = text)

    /** Calls done(answer, error) on the main thread; exactly one of the two is non-null. */
    fun ask(ctx: Context, system: String, turns: List<Turn>, image: Bitmap?, done: (String?, String?) -> Unit) {
        val provider = pick(ctx, image != null)
        if (provider == null) {
            done(null, ctx.getString(R.string.no_key))
            return
        }
        Net.pool.execute {
            var answer: String? = null
            var error: String? = null
            try {
                answer = when (provider) {
                    "cloudflare" -> openAi(
                        "https://api.cloudflare.com/client/v4/accounts/${Prefs.cfAccount(ctx)}/ai/v1/chat/completions",
                        Prefs.cfToken(ctx), Prefs.cfModel(ctx), system, turns, image, imageAsObject = true
                    )
                    else -> gemini(Prefs.geminiKey(ctx), Prefs.geminiModel(ctx), system, turns, image)
                }
            } catch (e: IOException) {
                error = ctx.getString(R.string.err_net)
            } catch (e: Exception) {
                error = e.message ?: ctx.getString(R.string.err_net)
            }
            Net.main.post { done(answer, error) }
        }
    }

    /**
     * Which provider answers. Photos go to Gemini when it has a key, because it reads
     * images most reliably. Otherwise the provider chosen in Settings is used, and if
     * that one has no key, the first provider that does.
     */
    private fun pick(ctx: Context, hasImage: Boolean): String? {
        val cf = Prefs.cfAccount(ctx).isNotEmpty() && Prefs.cfToken(ctx).isNotEmpty()
        val gemini = Prefs.geminiKey(ctx).isNotEmpty()
        if (hasImage && gemini) return "gemini"
        when (Prefs.provider(ctx)) {
            "cloudflare" -> if (cf) return "cloudflare"
            "gemini" -> if (gemini) return "gemini"
        }
        return when {
            cf -> "cloudflare"
            gemini -> "gemini"
            else -> null
        }
    }

    private fun jpegBase64(image: Bitmap): String {
        val bytes = ByteArrayOutputStream()
        image.compress(Bitmap.CompressFormat.JPEG, 85, bytes)
        return Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
    }

    private fun parse(text: String): JSONObject = try { JSONObject(text) } catch (e: Exception) { JSONObject() }

    /** Cloudflare and Mistral both speak the OpenAI chat-completions format. */
    private fun openAi(
        url: String, key: String, model: String, system: String,
        turns: List<Turn>, image: Bitmap?, imageAsObject: Boolean
    ): String {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        turns.forEachIndexed { i, t ->
            val role = if (t.role == "user") "user" else "assistant"
            if (image != null && i == turns.lastIndex) {
                val data = "data:image/jpeg;base64," + jpegBase64(image)
                val part = JSONObject().put("type", "image_url")
                if (imageAsObject) part.put("image_url", JSONObject().put("url", data)) else part.put("image_url", data)
                val content = JSONArray().put(JSONObject().put("type", "text").put("text", t.text)).put(part)
                messages.put(JSONObject().put("role", role).put("content", content))
            } else {
                messages.put(JSONObject().put("role", role).put("content", t.text))
            }
        }
        val body = JSONObject().put("model", model).put("messages", messages)
        val (code, text) = Net.request("POST", url, mapOf("Authorization" to "Bearer $key"), body.toString())
        val json = parse(text)
        if (code !in 200..299) {
            val msg = json.optJSONObject("error")?.optString("message")
                ?: json.optJSONArray("errors")?.optJSONObject(0)?.optString("message")
                ?: json.optString("message").ifEmpty { json.optString("detail") }
            throw RuntimeException(msg.ifEmpty { "HTTP $code" })
        }
        val content = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.opt("content")
        val answer = when (content) {
            is String -> content
            is JSONArray -> {
                val sb = StringBuilder()
                for (i in 0 until content.length()) {
                    val p = content.optJSONObject(i) ?: continue
                    if (p.optString("type") == "text") sb.append(p.optString("text"))
                }
                sb.toString()
            }
            else -> ""
        }
        if (answer.isBlank()) throw RuntimeException("The AI returned no text.")
        return answer.trim()
    }

    /**
     * Turns a voice recording (AAC) into text with Gemini. Calls done(text, error) on the
     * main thread; text is null when nothing was said or the request failed.
     */
    fun transcribe(ctx: Context, audio: File, done: (String?, String?) -> Unit) {
        val key = Prefs.geminiKey(ctx)
        val model = Prefs.geminiModel(ctx)
        Net.pool.execute {
            var text: String? = null
            var error: String? = null
            try {
                val parts = JSONArray()
                    .put(
                        JSONObject().put(
                            "text",
                            "Write down exactly what is said in this recording, in the language that is spoken. " +
                                "Reply with only those words. If nothing is said, reply with the single word NONE."
                        )
                    )
                    .put(
                        JSONObject().put(
                            "inline_data",
                            JSONObject().put("mime_type", "audio/aac")
                                .put("data", Base64.encodeToString(audio.readBytes(), Base64.NO_WRAP))
                        )
                    )
                val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
                val heard = geminiPost(key, model, body)
                if (heard != "NONE") text = heard
            } catch (e: IOException) {
                error = ctx.getString(R.string.err_net)
            } catch (e: Exception) {
                error = e.message ?: ctx.getString(R.string.err_net)
            }
            Net.main.post { done(text, error) }
        }
    }

    /**
     * Speaks text with Gemini's voice model, which covers Pashto and Dari.
     * Calls done(wav) on the main thread with a WAV file's bytes, or null when it failed.
     */
    fun speech(ctx: Context, text: String, done: (ByteArray?) -> Unit) {
        val key = Prefs.geminiKey(ctx)
        val model = Prefs.ttsModel(ctx)
        Net.pool.execute {
            var wav: ByteArray? = null
            try {
                val content = JSONArray().put(JSONObject().put("type", "text").put("text", text.take(2000)))
                val body = JSONObject()
                    .put("model", model)
                    .put("input", JSONArray().put(JSONObject().put("type", "user_input").put("content", content)))
                    .put("response_format", JSONObject().put("type", "audio"))
                    .put(
                        "generation_config",
                        JSONObject().put("speech_config", JSONArray().put(JSONObject().put("voice", "Kore")))
                    )
                val (code, reply) = Net.request(
                    "POST", "https://generativelanguage.googleapis.com/v1beta/interactions",
                    mapOf("x-goog-api-key" to key), body.toString()
                )
                if (code in 200..299) {
                    val steps = parse(reply).optJSONArray("steps")
                    if (steps != null) {
                        for (i in 0 until steps.length()) {
                            val step = steps.optJSONObject(i) ?: continue
                            if (step.optString("type") != "model_output") continue
                            val items = step.optJSONArray("content") ?: continue
                            for (j in 0 until items.length()) {
                                val item = items.optJSONObject(j) ?: continue
                                if (item.optString("type") == "audio" && wav == null) {
                                    wav = Base64.decode(item.optString("data"), Base64.DEFAULT)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                wav = null // the caller falls back to the phone's own voice
            }
            Net.main.post { done(wav) }
        }
    }

    private fun gemini(key: String, model: String, system: String, turns: List<Turn>, image: Bitmap?): String {
        val contents = JSONArray()
        turns.forEachIndexed { i, t ->
            val parts = JSONArray().put(JSONObject().put("text", t.text))
            if (image != null && i == turns.lastIndex) {
                parts.put(
                    JSONObject().put(
                        "inline_data",
                        JSONObject().put("mime_type", "image/jpeg").put("data", jpegBase64(image))
                    )
                )
            }
            contents.put(JSONObject().put("role", t.role).put("parts", parts))
        }
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
        return geminiPost(key, model, body)
    }

    private fun geminiPost(key: String, model: String, body: JSONObject): String {
        val (code, text) = Net.request(
            "POST", "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent",
            mapOf("x-goog-api-key" to key), body.toString()
        )
        val json = parse(text)
        if (code !in 200..299) {
            val msg = json.optJSONObject("error")?.optString("message") ?: ""
            throw RuntimeException(msg.ifEmpty { "HTTP $code" })
        }
        val parts = json.optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts")
        val sb = StringBuilder()
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                if (!p.optBoolean("thought", false)) sb.append(p.optString("text", ""))
            }
        }
        if (sb.isBlank()) throw RuntimeException("The AI returned no text.")
        return sb.toString().trim()
    }
}

/** Barcodes and QR codes: reading them from a photo and drawing a QR code. */
object Codes {
    /** Returns the content of the first barcode or QR code in the photo, or null when there is none. */
    fun scan(bmp: Bitmap): String? {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val source = RGBLuminanceSource(w, h, px)
        val hints = mapOf<DecodeHintType, Any>(
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.ALSO_INVERTED to true
        )
        for (binary in listOf(BinaryBitmap(HybridBinarizer(source)), BinaryBitmap(GlobalHistogramBinarizer(source)))) {
            try {
                return MultiFormatReader().decode(binary, hints).text
            } catch (e: Exception) {
                // No code found with this method; try the next one.
            }
        }
        return null
    }

    /**
     * Draws any code type. Throws when the text does not fit the type
     * (for example letters in an EAN-13 code).
     */
    fun make(text: String, format: BarcodeFormat, width: Int, height: Int): Bitmap {
        val hints = mapOf<EncodeHintType, Any>(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 2)
        val matrix = MultiFormatWriter().encode(text, format, width, height, hints)
        val w = matrix.width
        val h = matrix.height
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) px[y * w + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
        }
        val small = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        // Some types come out only a few pixels wide; enlarge them without blurring.
        val factor = maxOf(1, 600 / maxOf(w, 1))
        return if (factor > 1) Bitmap.createScaledBitmap(small, w * factor, h * factor, false) else small
    }

    fun qr(text: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val px = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) px[y * size + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }
}

/** Files the app keeps: saved notes, files received from another phone, and chats. */
object Storage {
    private fun root(c: Context): File = c.getExternalFilesDir(null) ?: c.filesDir

    fun dir(c: Context, name: String): File = File(root(c), name).apply { mkdirs() }

    fun chatsDir(c: Context): File = File(c.filesDir, "chats").apply { mkdirs() }

    fun saveNote(c: Context, prefix: String, text: String): File {
        val safe = prefix.replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_').ifEmpty { "note" }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val f = File(dir(c, "Notes"), "${safe}_$stamp.txt")
        f.writeText(text, Charsets.UTF_8)
        // Saved results also go to Download/Faisal/Notes.
        Public.save(c, "Notes", f.name, f, "text/plain")
        return f
    }

    fun all(c: Context): List<File> =
        listOf("Received", "Scans", "Codes")
            .flatMap { dir(c, it).listFiles()?.filter { f -> f.isFile } ?: emptyList() }
            .sortedByDescending { it.lastModified() }

    fun eraseAll(c: Context) {
        listOf("Notes", "Received", "Scans", "Codes").forEach { d -> dir(c, d).listFiles()?.forEach { it.delete() } }
        chatsDir(c).listFiles()?.forEach { it.delete() }
        File(c.filesDir, "chat.json").delete()
    }

    fun uri(c: Context, f: File): Uri = FileProvider.getUriForFile(c, c.packageName + ".files", f)

    /** The file name a picked document reports, or "file". */
    fun displayName(c: Context, uri: Uri): String {
        try {
            c.contentResolver.query(uri, null, null, null, null)?.use { cur ->
                val i = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cur.moveToFirst() && i >= 0 && !cur.isNull(i)) return cur.getString(i)
            }
        } catch (e: Exception) {
            // The provider gave no name; use the default.
        }
        return "file"
    }

    /** Loads a photo scaled so its longer side is at most [max] pixels. */
    fun loadBitmap(c: Context, uri: Uri, max: Int = 1280): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(c.contentResolver, uri)) { d, info, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val big = maxOf(info.size.width, info.size.height)
                if (big > max) {
                    val s = max.toFloat() / big
                    d.setTargetSize(
                        (info.size.width * s).toInt().coerceAtLeast(1),
                        (info.size.height * s).toInt().coerceAtLeast(1)
                    )
                }
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > max) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }
    } catch (e: Exception) {
        null
    }
}

/** Shared helpers for every screen, including the theme chosen in Settings. */
open class BaseActivity : AppCompatActivity() {
    private var appliedTheme = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        appliedTheme = Prefs.theme(this)
        setTheme(Prefs.themeRes(appliedTheme))
        super.onCreate(savedInstanceState)
    }

    override fun onStart() {
        super.onStart()
        // With a PIN set, every screen asks for it until the PIN is entered.
        if (this !is PinActivity && !Lock.unlocked && Lock.hasPin(this)) startActivity(Intent(this, PinActivity::class.java))
    }

    protected fun scanTabs(index: Int) {
        val tabs = findViewById<com.google.android.material.tabs.TabLayout>(R.id.scanTabs) ?: return
        listOf(R.string.t_scan, R.string.t_ocr, R.string.t_lens).forEach { tabs.addTab(tabs.newTab().setText(it), false) }
        tabs.getTabAt(index)?.select()
        tabs.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(t: com.google.android.material.tabs.TabLayout.Tab) {
                if (t.position == index) return
                val next = when (t.position) {
                    0 -> Intent(this@BaseActivity, ScannerActivity::class.java)
                    1 -> Intent(this@BaseActivity, VisionActivity::class.java).putExtra("mode", "ocr")
                    else -> Intent(this@BaseActivity, VisionActivity::class.java).putExtra("mode", "lens")
                }
                startActivity(next)
                finish()
            }
            override fun onTabUnselected(t: com.google.android.material.tabs.TabLayout.Tab) {}
            override fun onTabReselected(t: com.google.android.material.tabs.TabLayout.Tab) {}
        })
    }

    override fun onResume() {
        super.onResume()
        // The theme was changed in Settings while this screen was in the background.
        if (Prefs.theme(this) != appliedTheme) recreate()
    }

    protected fun bar(title: Int): MaterialToolbar {
        val tb = findViewById<MaterialToolbar>(R.id.bar)
        tb.setTitle(title)
        tb.setNavigationOnClickListener { finish() }
        return tb
    }

    protected fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** A color of the current theme, e.g. attrColor(R.attr.colFg). */
    protected fun attrColor(attr: Int): Int {
        val tv = TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    protected fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    protected fun copy(text: String) {
        if (text.isBlank()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("text", text))
        toast(getString(R.string.copied))
    }

    protected fun saveNote(prefix: String, text: String) {
        if (text.isBlank()) return
        Storage.saveNote(this, prefix, text)
        toast(getString(R.string.saved))
    }

    protected fun beep() {
        if (!Prefs.sound(this)) return
        try {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60).startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        } catch (e: Exception) {
            // No tone available on this device; the answer is still shown.
        }
    }
}
