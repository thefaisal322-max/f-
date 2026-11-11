package com.fainet.faisalai

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.FileProvider
import androidx.core.os.LocaleListCompat
import androidx.preference.PreferenceManager
import com.google.android.material.appbar.MaterialToolbar
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
import java.util.concurrent.Executors

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.applyTheme(Prefs.sp(this).getString("theme", "system") ?: "system")
    }
}

/** Everything the Settings screen stores. */
object Prefs {
    const val DEFAULT_MODEL = "gemini-flash-latest"

    fun sp(c: Context): SharedPreferences = PreferenceManager.getDefaultSharedPreferences(c)
    fun key(c: Context): String = (sp(c).getString("api_key", "") ?: "").trim()
    fun model(c: Context): String = (sp(c).getString("model", DEFAULT_MODEL) ?: "").trim().ifEmpty { DEFAULT_MODEL }
    fun sound(c: Context): Boolean = sp(c).getBoolean("sound", false)
    fun keepHistory(c: Context): Boolean = sp(c).getBoolean("keep_history", true)
    fun frontCam(c: Context): Boolean = sp(c).getString("bvr_cam", "back") == "front"
    fun quality(c: Context): String = sp(c).getString("bvr_quality", "HD") ?: "HD"

    fun applyTheme(value: String) {
        AppCompatDelegate.setDefaultNightMode(
            when (value) {
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    fun applyLang(tag: String) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

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

/** Gemini REST client. No SDK, so there is nothing extra to keep up to date. */
object Gemini {
    class Turn(val role: String, val text: String) // role: "user" or "model"

    private val pool = Executors.newCachedThreadPool()
    private val main = Handler(Looper.getMainLooper())

    /** Calls done(answer, error) on the main thread; exactly one of the two is non-null. */
    fun ask(ctx: Context, system: String, turns: List<Turn>, image: Bitmap?, done: (String?, String?) -> Unit) {
        val key = Prefs.key(ctx)
        if (key.isEmpty()) {
            done(null, ctx.getString(R.string.no_key))
            return
        }
        val model = Prefs.model(ctx)
        pool.execute {
            var answer: String? = null
            var error: String? = null
            try {
                answer = call(key, model, system, turns, image)
            } catch (e: IOException) {
                error = ctx.getString(R.string.err_net)
            } catch (e: Exception) {
                error = e.message ?: ctx.getString(R.string.err_net)
            }
            main.post { done(answer, error) }
        }
    }

    private fun call(key: String, model: String, system: String, turns: List<Turn>, image: Bitmap?): String {
        val contents = JSONArray()
        turns.forEachIndexed { i, t ->
            val parts = JSONArray().put(JSONObject().put("text", t.text))
            if (image != null && i == turns.lastIndex) {
                val bytes = ByteArrayOutputStream()
                image.compress(Bitmap.CompressFormat.JPEG, 85, bytes)
                parts.put(
                    JSONObject().put(
                        "inline_data",
                        JSONObject().put("mime_type", "image/jpeg")
                            .put("data", Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP))
                    )
                )
            }
            contents.put(JSONObject().put("role", t.role).put("parts", parts))
        }
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)

        val con = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .openConnection() as HttpURLConnection
        try {
            con.requestMethod = "POST"
            con.connectTimeout = 20000
            con.readTimeout = 120000
            con.doOutput = true
            con.setRequestProperty("Content-Type", "application/json")
            con.setRequestProperty("x-goog-api-key", key)
            con.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = con.responseCode
            val stream = if (code in 200..299) con.inputStream else con.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val json = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
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
        } finally {
            con.disconnect()
        }
    }
}

/** Files the app keeps: saved notes and files received from another phone. */
object Storage {
    private fun root(c: Context): File = c.getExternalFilesDir(null) ?: c.filesDir

    fun dir(c: Context, name: String): File = File(root(c), name).apply { mkdirs() }

    fun saveNote(c: Context, prefix: String, text: String): File {
        val safe = prefix.replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_').ifEmpty { "note" }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val f = File(dir(c, "Notes"), "${safe}_$stamp.txt")
        f.writeText(text, Charsets.UTF_8)
        return f
    }

    fun all(c: Context): List<File> =
        listOf("Notes", "Received")
            .flatMap { dir(c, it).listFiles()?.filter { f -> f.isFile } ?: emptyList() }
            .sortedByDescending { it.lastModified() }

    fun eraseAll(c: Context) {
        listOf("Notes", "Received").forEach { d -> dir(c, d).listFiles()?.forEach { it.delete() } }
        File(c.filesDir, "chat.json").delete()
    }

    fun uri(c: Context, f: File): Uri = FileProvider.getUriForFile(c, c.packageName + ".files", f)

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

/** Shared helpers for every screen. */
open class BaseActivity : AppCompatActivity() {
    protected fun bar(title: Int): MaterialToolbar {
        val tb = findViewById<MaterialToolbar>(R.id.bar)
        tb.setTitle(title)
        tb.setNavigationOnClickListener { finish() }
        return tb
    }

    protected fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

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
