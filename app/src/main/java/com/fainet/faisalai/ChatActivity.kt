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
        turns.forEachIndexed { i, t -> bubble(t.role, t.show, i) }
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

    private val rating = HashMap<Int, Int>() // turn index -> 1 liked, -1 disliked

    /** A message: the bubble and, under it, small action buttons. [index] is its place in [turns]. */
    private fun bubble(role: String, text: String, index: Int = turns.size): TextView {
        val user = role == "user"
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 16f
        tv.setTextIsSelectable(true)
        tv.setPadding(dp(12), dp(10), dp(12), dp(10))
        tv.setBackgroundResource(if (user) R.drawable.bg_user else R.drawable.bg_bot)
        tv.setTextColor(attrColor(if (user) R.attr.colOnAccent else R.attr.colFg))
        tv.maxWidth = (resources.displayMetrics.widthPixels * 0.85f).toInt()

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.tag = index
        box.addView(tv, LinearLayout.LayoutParams(-2, -2).apply { gravity = if (user) Gravity.END else Gravity.START })
        val actions = LinearLayout(this)
        actions.orientation = LinearLayout.HORIZONTAL
        actions.tag = "actions"
        actions.visibility = if (text == getString(R.string.thinking)) View.GONE else View.VISIBLE
        fun action(icon: Int, label: Int, tint: Boolean = false, run: (ImageButton) -> Unit): ImageButton {
            val b = ImageButton(this)
            b.setImageResource(icon)
            b.setBackgroundResource(android.R.color.transparent)
            b.contentDescription = getString(label)
            b.setColorFilter(attrColor(if (tint) R.attr.colAccent else R.attr.colMuted))
            b.setPadding(dp(8), dp(6), dp(8), dp(6))
            b.setOnClickListener { run(b) }
            actions.addView(b, LinearLayout.LayoutParams(dp(38), dp(34)))
            return b
        }
        if (user) {
            action(R.drawable.ic_a_edit, R.string.a_edit) { edit(box.tag as Int) }
            action(R.drawable.ic_a_copy, R.string.a_copy) { copy(tv.text.toString()) }
        } else {
            action(R.drawable.ic_a_copy, R.string.a_copy) { copy(tv.text.toString()) }
            action(R.drawable.ic_a_share, R.string.a_share) {
                startActivity(android.content.Intent.createChooser(
                    android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, tv.text.toString()),
                    getString(R.string.share)))
            }
            lateinit var like: ImageButton
            lateinit var dislike: ImageButton
            fun paint() {
                val r = rating[box.tag as Int] ?: 0
                like.setColorFilter(attrColor(if (r == 1) R.attr.colAccent else R.attr.colMuted))
                dislike.setColorFilter(attrColor(if (r == -1) R.attr.colAccent else R.attr.colMuted))
            }
            like = action(R.drawable.ic_a_like, R.string.a_like) {
                val k = box.tag as Int
                rating[k] = if (rating[k] == 1) 0 else 1
                paint()
            }
            dislike = action(R.drawable.ic_a_dislike, R.string.a_dislike) {
                val k = box.tag as Int
                rating[k] = if (rating[k] == -1) 0 else -1
                paint()
            }
            action(R.drawable.ic_a_replay, R.string.a_replay) { replay(box.tag as Int) }
            paint()
        }
        box.addView(actions, LinearLayout.LayoutParams(-2, -2).apply { gravity = if (user) Gravity.END else Gravity.START })

        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(8)
        log.addView(box, lp)
        scrollDown()
        return tv
    }

    /** Removes the turns from [index] on, and their messages and photos on screen. */
    private fun cut(index: Int) {
        while (turns.size > index) turns.removeAt(turns.lastIndex)
        for (i in log.childCount - 1 downTo 0) {
            val t = log.getChildAt(i).tag
            if (t is Int && t >= index) log.removeViewAt(i)
        }
        rating.keys.removeAll { it >= index }
        updateWelcome()
    }

    /** Edit a question: it goes back into the text box and the chat continues from there. */
    private fun edit(index: Int) {
        if (busy || index >= turns.size) return
        val t = turns[index]
        val plain = if (t.text.contains("\n\n[File:")) t.text.substringBefore("\n\n[File:") else t.show
        cut(index)
        save()
        input.setText(plain)
        input.setSelection(input.text.length)
        input.requestFocus()
    }

    /** Ask the question again and get a new answer. */
    private fun replay(index: Int) {
        if (busy || index < 1 || index >= turns.size) return
        cut(index)
        respond(null)
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
        iv.tag = turns.size
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
        bubble("user", shown, turns.size - 1)
        clearAttachment()
        respond(image)
    }

    /** Gets the AI's answer to the chat so far and shows it. */
    private fun respond(image: Bitmap?) {
        val reply = bubble("model", getString(R.string.thinking))
        val actions = (reply.parent as View).findViewWithTag<View>("actions")
        busy = true
        send.isEnabled = false

        // Send the recent turns only, and make sure they start with a user turn.
        var context: List<AI.Turn> = turns.takeLast(20)
        if (context.first().role != "user") context = context.drop(1)
        val system = AI.IDENTITY + "You are the assistant inside the Faisal AI Assistance app. " +
            "Be helpful, accurate and friendly. " + Prefs.style(this)

        AI.ask(this, system, context, image) { answer, error ->
            busy = false
            send.isEnabled = true
            actions?.visibility = View.VISIBLE
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
