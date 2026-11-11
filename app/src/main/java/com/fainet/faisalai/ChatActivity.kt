package com.fainet.faisalai

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * AI chat. Bottom row: attach button, text box, send button.
 * Side menu: "New chat" at the top, then the saved chats.
 */
class ChatActivity : BaseActivity() {

    private val voice = Voice(this)
    private val turns = ArrayList<AI.Turn>()
    private var chatId = ""
    private var busy = false

    private var pendingImage: Bitmap? = null
    private var pendingText: String? = null
    private var pendingName = ""

    private var chatFiles: List<File> = emptyList()

    private lateinit var drawer: DrawerLayout
    private lateinit var log: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var send: ImageButton
    private lateinit var attached: TextView
    private lateinit var chats: ListView
    private lateinit var welcome: TextView

    private fun updateWelcome() {
        welcome.visibility = if (turns.isEmpty()) View.VISIBLE else View.GONE
    }

    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) attach(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        drawer = findViewById(R.id.drawer)
        log = findViewById(R.id.log)
        scroll = findViewById(R.id.scroll)
        input = findViewById(R.id.input)
        send = findViewById(R.id.send)
        attached = findViewById(R.id.attached)
        chats = findViewById(R.id.chats)

        welcome = findViewById(R.id.welcome)
        findViewById<ImageButton>(R.id.menuBtn).setOnClickListener { drawer.openDrawer(GravityCompat.START) }
        // The glasses button opens the live, talking camera assistant.
        findViewById<ImageButton>(R.id.liveBtn).setOnClickListener {
            startActivity(android.content.Intent(this, LiveActivity::class.java))
        }

        findViewById<Button>(R.id.newChat).setOnClickListener {
            if (!busy) newChat()
            drawer.closeDrawer(GravityCompat.START)
        }
        chats.setOnItemClickListener { _, _, pos, _ ->
            if (!busy) openChat(chatFiles[pos].nameWithoutExtension)
            drawer.closeDrawer(GravityCompat.START)
        }
        chats.setOnItemLongClickListener { _, _, pos, _ ->
            if (!busy) {
                val f = chatFiles[pos]
                val current = f.nameWithoutExtension == chatId
                f.delete()
                if (current) newChat() else refreshSide()
            }
            true
        }

        findViewById<ImageButton>(R.id.attach).setOnClickListener { picker.launch("*/*") }

        // Microphone: tap, speak, tap again. The words are sent like a typed message.
        val mic = findViewById<ImageButton>(R.id.mic)
        mic.setOnClickListener { if (!busy) voice.toggle() }
        voice.onText = { said -> ask(said) }
        voice.onState = { state ->
            mic.setImageResource(if (state == Voice.State.RECORDING) R.drawable.ic_stop else R.drawable.ic_mic)
            input.setHint(
                when (state) {
                    Voice.State.RECORDING -> R.string.listening
                    Voice.State.WORKING -> R.string.thinking
                    else -> R.string.chat_hint2
                }
            )
        }
        send.setOnClickListener {
            val text = input.text.toString()
            input.setText("")
            ask(text)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawer.isDrawerOpen(GravityCompat.START)) drawer.closeDrawer(GravityCompat.START) else finish()
            }
        })

        if (!Prefs.keepHistory(this)) Storage.chatsDir(this).listFiles()?.forEach { it.delete() }

        val question = if (savedInstanceState == null) intent.getStringExtra("q") else null
        val last = Prefs.sp(this).getString("chat_current", null)
        if (question != null || last == null) newChat() else openChat(last)
        if (question != null) ask(question)
    }

    override fun onPause() {
        voice.stopSpeaking()
        super.onPause()
    }

    override fun onDestroy() {
        voice.release()
        super.onDestroy()
    }

    // ---------- chats ----------

    private fun file(id: String): File = File(Storage.chatsDir(this), "$id.json")

    private fun newChat() {
        chatId = System.currentTimeMillis().toString()
        turns.clear()
        log.removeAllViews()
        clearAttachment()
        refreshSide()
        updateWelcome()
    }

    private fun openChat(id: String) {
        chatId = id
        turns.clear()
        log.removeAllViews()
        clearAttachment()
        try {
            val arr = JSONObject(file(id).readText()).getJSONArray("turns")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val text = o.getString("t")
                turns.add(AI.Turn(o.getString("r"), text, o.optString("s", text)))
            }
        } catch (e: Exception) {
            turns.clear() // the chat was deleted or is unreadable; start empty
        }
        turns.forEach { bubble(it.role, it.show) }
        Prefs.sp(this).edit().putString("chat_current", id).apply()
        refreshSide()
        updateWelcome()
    }

    private fun save() {
        if (!Prefs.keepHistory(this) || turns.isEmpty()) return
        val arr = JSONArray()
        turns.takeLast(80).forEach { arr.put(JSONObject().put("r", it.role).put("t", it.text).put("s", it.show)) }
        val title = turns.first().show.lineSequence().firstOrNull { it.isNotBlank() }?.take(40) ?: ""
        try {
            file(chatId).writeText(JSONObject().put("title", title).put("turns", arr).toString())
            Prefs.sp(this).edit().putString("chat_current", chatId).apply()
        } catch (e: Exception) {
            // Storage full: the chat still works for this session.
        }
        refreshSide()
    }

    private fun refreshSide() {
        chatFiles = Storage.chatsDir(this).listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        val titles = chatFiles.map { f ->
            val title = try {
                JSONObject(f.readText()).optString("title")
            } catch (e: Exception) {
                ""
            }
            title.ifEmpty { getString(R.string.new_chat) }
        }
        chats.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, titles)
    }

    // ---------- attachments ----------

    private fun attach(uri: Uri) {
        val type = contentResolver.getType(uri) ?: ""
        val name = Storage.displayName(this, uri)
        val textLike = type.startsWith("text/") || type.contains("json") || type.contains("xml") ||
            Regex("\\.(txt|md|csv|json|xml|log|html?)$", RegexOption.IGNORE_CASE).containsMatchIn(name)
        when {
            type.startsWith("image/") -> {
                val bmp = Storage.loadBitmap(this, uri)
                if (bmp == null) {
                    toast(getString(R.string.unsupported_file))
                    return
                }
                clearAttachment()
                pendingImage = bmp
                pendingName = getString(R.string.photo)
            }
            textLike -> {
                val text = try {
                    contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { r ->
                        val buf = CharArray(20000)
                        val n = r.read(buf)
                        if (n > 0) String(buf, 0, n) else ""
                    } ?: ""
                } catch (e: Exception) {
                    ""
                }
                if (text.isBlank()) {
                    toast(getString(R.string.unsupported_file))
                    return
                }
                clearAttachment()
                pendingText = text
                pendingName = name
            }
            else -> {
                toast(getString(R.string.unsupported_file))
                return
            }
        }
        attached.text = getString(R.string.attached, pendingName)
        attached.visibility = View.VISIBLE
    }

    private fun clearAttachment() {
        pendingImage = null
        pendingText = null
        pendingName = ""
        attached.visibility = View.GONE
    }

    // ---------- messages ----------

    private fun bubble(role: String, text: String): TextView {
        val user = role == "user"
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 16f
        tv.setTextIsSelectable(true)
        tv.setPadding(dp(12), dp(10), dp(12), dp(10))
        tv.setBackgroundResource(if (user) R.drawable.bg_user else R.drawable.bg_bot)
        tv.setTextColor(attrColor(if (user) R.attr.colOnAccent else R.attr.colFg))
        tv.maxWidth = (resources.displayMetrics.widthPixels * 0.85f).toInt()
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.gravity = if (user) Gravity.END else Gravity.START
        lp.topMargin = dp(8)
        log.addView(tv, lp)
        scrollDown()
        return tv
    }

    private fun photoBubble(bmp: Bitmap) {
        val iv = ImageView(this)
        iv.setImageBitmap(bmp)
        iv.adjustViewBounds = true
        iv.maxHeight = dp(180)
        iv.maxWidth = dp(220)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.END
        lp.topMargin = dp(8)
        log.addView(iv, lp)
    }

    private fun scrollDown() {
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun ask(raw: String) {
        val text = raw.trim()
        val image = pendingImage
        val fileText = pendingText
        if (busy || (text.isEmpty() && image == null && fileText == null)) return

        // What the AI receives, and the shorter version the bubble shows.
        val question = text.ifEmpty { if (image != null) "Describe this photo." else "Summarize this file." }
        val sent = if (fileText != null) "$question\n\n[File: $pendingName]\n$fileText" else question
        val label = if (image != null || fileText != null) getString(R.string.attached, pendingName) else ""
        val shown = listOf(text, label).filter { it.isNotEmpty() }.joinToString("\n")

        if (image != null) photoBubble(image)
        turns.add(AI.Turn("user", sent, shown))
        updateWelcome()
        bubble("user", shown)
        clearAttachment()
        val reply = bubble("model", getString(R.string.thinking))
        busy = true
        send.isEnabled = false

        // Send the recent turns only, and make sure they start with a user turn.
        var context: List<AI.Turn> = turns.takeLast(20)
        if (context.first().role != "user") context = context.drop(1)
        val system = "You are Faisal, the assistant inside the Faisal AI Assistance app. " +
            "Be helpful, accurate and friendly. " + Prefs.style(this)

        AI.ask(this, system, context, image) { answer, error ->
            busy = false
            send.isEnabled = true
            if (answer != null) {
                turns.add(AI.Turn("model", answer))
                reply.text = answer
                beep()
                save()
                if (Prefs.speak(this) && !isDestroyed) voice.speak(answer)
            } else {
                turns.removeAt(turns.lastIndex) // drop the unanswered question from the history
                updateWelcome()
                reply.text = error
            }
            scrollDown()
        }
    }
}
