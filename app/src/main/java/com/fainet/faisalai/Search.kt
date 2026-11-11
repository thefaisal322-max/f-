package com.fainet.faisalai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The app's own search. Web results come from Gemini with Google Search grounding,
 * so the app needs only the Gemini key. Images and videos come from Wikimedia Commons.
 */
object Search {
    class Hit(val site: String, val url: String, val snippet: String)
    class Page(val answer: String, val hits: List<Hit>)
    class Media(val title: String, val thumb: String, val url: String)

    private val pages = HashMap<String, Page>()
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** done(page, error) runs on the main thread; exactly one is non-null. */
    fun web(ctx: android.content.Context, q: String, done: (Page?, String?) -> Unit) {
        pages[q]?.let { done(it, null); return }
        val key = Prefs.geminiKey(ctx)
        if (key.isEmpty()) { done(null, ctx.getString(R.string.no_key)); return }
        Net.pool.execute {
            var page: Page? = null
            var error: String? = null
            try {
                val body = JSONObject()
                    .put("system_instruction", JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text",
                        "Search the web and answer the question with up-to-date, factual information. " + Prefs.style(ctx)))))
                    .put("contents", org.json.JSONArray().put(JSONObject().put("role", "user")
                        .put("parts", org.json.JSONArray().put(JSONObject().put("text", q)))))
                    .put("tools", org.json.JSONArray().put(JSONObject().put("google_search", JSONObject())))
                val (code, text) = Net.request(
                    "POST", "https://generativelanguage.googleapis.com/v1beta/models/${Prefs.geminiModel(ctx)}:generateContent",
                    mapOf("x-goog-api-key" to key), body.toString()
                )
                val json = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
                if (code !in 200..299) {
                    throw RuntimeException(json.optJSONObject("error")?.optString("message").orEmpty().ifEmpty { "HTTP $code" })
                }
                val cand = json.optJSONArray("candidates")?.optJSONObject(0)
                val parts = cand?.optJSONObject("content")?.optJSONArray("parts")
                val sb = StringBuilder()
                if (parts != null) for (i in 0 until parts.length()) {
                    val p = parts.optJSONObject(i) ?: continue
                    if (!p.optBoolean("thought", false)) sb.append(p.optString("text", ""))
                }
                val meta = cand?.optJSONObject("groundingMetadata")
                val chunks = meta?.optJSONArray("groundingChunks")
                val snippets = HashMap<Int, StringBuilder>()
                val sup = meta?.optJSONArray("groundingSupports")
                if (sup != null) for (i in 0 until sup.length()) {
                    val o = sup.optJSONObject(i) ?: continue
                    val seg = o.optJSONObject("segment")?.optString("text").orEmpty()
                    val idx = o.optJSONArray("groundingChunkIndices") ?: continue
                    for (k in 0 until idx.length()) {
                        val sn = snippets.getOrPut(idx.optInt(k)) { StringBuilder() }
                        if (sn.length < 320) sn.append(if (sn.isEmpty()) "" else " ").append(seg)
                    }
                }
                val hits = ArrayList<Hit>()
                if (chunks != null) for (i in 0 until chunks.length()) {
                    val w = chunks.optJSONObject(i)?.optJSONObject("web") ?: continue
                    hits.add(Hit(w.optString("title"), w.optString("uri"), snippets[i]?.toString().orEmpty()))
                }
                if (sb.isBlank() && hits.isEmpty()) throw RuntimeException(ctx.getString(R.string.no_results))
                page = Page(sb.toString().trim(), hits)
                pages[q] = page
            } catch (e: IOException) {
                error = ctx.getString(R.string.err_net)
            } catch (e: Exception) {
                error = e.message ?: ctx.getString(R.string.err_net)
            }
            Net.main.post { done(page, error) }
        }
    }

    /** Images or videos from Wikimedia Commons. */
    fun media(ctx: android.content.Context, q: String, video: Boolean, done: (List<Media>?, String?) -> Unit) {
        Net.pool.execute {
            var list: List<Media>? = null
            var error: String? = null
            try {
                // Commons is searched in English; translate other languages first when possible.
                var term = q
                if (q.any { it.code > 127 } && Prefs.geminiKey(ctx).isNotEmpty() || Prefs.cfToken(ctx).isNotEmpty() && q.any { it.code > 127 }) {
                    val latch = java.util.concurrent.CountDownLatch(1)
                    var en: String? = null
                    Net.main.post {
                        AI.ask(ctx, "Translate the search query to English. Reply with only the English keywords.",
                            listOf(AI.Turn("user", q)), null) { a, _ -> en = a; latch.countDown() }
                    }
                    latch.await(25, java.util.concurrent.TimeUnit.SECONDS)
                    en?.trim()?.takeIf { it.isNotEmpty() }?.let { term = it.lines().first() }
                }
                val kind = if (video) " filetype:video" else " filetype:bitmap"
                val url = "https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=search&gsrnamespace=6" +
                    "&gsrlimit=36&gsrsearch=" + enc(term + kind) + "&prop=imageinfo&iiprop=url%7Cmime&iiurlwidth=420&origin=*"
                val (code, text) = Net.request("GET", url, mapOf("User-Agent" to "FaisalAI/3.0 (Android)"), null)
                if (code !in 200..299) throw RuntimeException("HTTP $code")
                val pagesJson = JSONObject(text).optJSONObject("query")?.optJSONObject("pages")
                val out = ArrayList<Pair<Int, Media>>()
                if (pagesJson != null) for (k in pagesJson.keys()) {
                    val p = pagesJson.getJSONObject(k)
                    val info = p.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
                    val thumb = info.optString("thumburl")
                    if (thumb.isEmpty()) continue
                    out.add(Pair(p.optInt("index"), Media(p.optString("title").removePrefix("File:").substringBeforeLast('.'), thumb, info.optString("url"))))
                }
                list = out.sortedBy { it.first }.map { it.second }
                if (list.isEmpty()) throw RuntimeException(ctx.getString(R.string.no_results))
            } catch (e: IOException) {
                error = ctx.getString(R.string.err_net)
            } catch (e: Exception) {
                error = e.message ?: ctx.getString(R.string.err_net)
            }
            Net.main.post { done(list, error) }
        }
    }
}

/** Small cached image loader for result thumbnails. */
object Img {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun load(url: String, view: ImageView) {
        view.tag = url
        val hit = cache.get(url)
        if (hit != null) { view.setImageBitmap(hit); return }
        view.setImageDrawable(null)
        Net.pool.execute {
            try {
                val con = URL(url).openConnection() as HttpURLConnection
                con.setRequestProperty("User-Agent", "FaisalAI/3.0 (Android)")
                con.connectTimeout = 15000
                con.readTimeout = 30000
                val bmp = con.inputStream.use { BitmapFactory.decodeStream(it) }
                con.disconnect()
                if (bmp != null) {
                    cache.put(url, bmp)
                    Net.main.post { if (view.tag == url) view.setImageBitmap(bmp) }
                }
            } catch (e: Exception) {
                // The thumbnail stays empty.
            }
        }
    }
}
