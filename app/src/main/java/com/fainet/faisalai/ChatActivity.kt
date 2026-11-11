package com.fainet.faisalai

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** AI chat, answered by Gemini with the key and model from Settings. */
class ChatActivity : BaseActivity() {

    private val turns = ArrayList<Gemini.Turn>()
    private lateinit var log: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var send: ImageButton
    private var busy = false

    private val historyFile: File get() = File(filesDir, "chat.json")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        val tb = bar(R.string.t_chat)
        tb.menu.add(R.string.clear_chat).setOnMenuItemClickListener {
            if (!busy) {
                turns.clear()
                log.removeAllViews()
                save()
            }
            true
        }

        log = findViewById(R.id.log)
        scroll = findViewById(R.id.scroll)
        input = findViewById(R.id.input)
        send = findViewById(R.id.send)

        load()
        turns.forEach { bubble(it.role, it.text) }

        send.setOnClickListener {
            val text = input.text.toString()
            input.setText("")
            ask(text)
        }
        if (savedInstanceState == null) intent.getStringExtra("q")?.let { ask(it) }
    }

    private fun bubble(role: String, text: String): TextView {
        val user = role == "user"
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 16f
        tv.setTextIsSelectable(true)
        tv.setPadding(dp(12), dp(10), dp(12), dp(10))
        tv.setBackgroundResource(if (user) R.drawable.bg_user else R.drawable.bg_bot)
        tv.setTextColor(ContextCompat.getColor(this, if (user) R.color.on_accent else R.color.fg))
        tv.maxWidth = (resources.displayMetrics.widthPixels * 0.85f).toInt()
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.gravity = if (user) Gravity.END else Gravity.START
        lp.topMargin = dp(8)
        log.addView(tv, lp)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        return tv
    }

    private fun ask(raw: String) {
        val text = raw.trim()
        if (text.isEmpty() || busy) return
        turns.add(Gemini.Turn("user", text))
        bubble("user", text)
        val reply = bubble("model", getString(R.string.thinking))
        busy = true
        send.isEnabled = false

        // Send the recent turns only, and make sure they start with a user turn.
        var context: List<Gemini.Turn> = turns.takeLast(20)
        if (context.first().role != "user") context = context.drop(1)
        val system = "You are Faisal, the assistant inside the Faisal AI Assistance app. " +
            "Be helpful, accurate and friendly. " + Prefs.style(this)

        Gemini.ask(this, system, context, null) { answer, error ->
            busy = false
            send.isEnabled = true
            if (answer != null) {
                turns.add(Gemini.Turn("model", answer))
                reply.text = answer
                beep()
                save()
            } else {
                turns.removeAt(turns.lastIndex) // drop the unanswered question from the history
                reply.text = error
            }
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun load() {
        if (!Prefs.keepHistory(this)) {
            historyFile.delete()
            return
        }
        try {
            val arr = JSONArray(historyFile.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                turns.add(Gemini.Turn(o.getString("r"), o.getString("t")))
            }
        } catch (e: Exception) {
            turns.clear() // no history yet, or the file is unreadable
        }
    }

    private fun save() {
        if (!Prefs.keepHistory(this) || turns.isEmpty()) {
            historyFile.delete()
            return
        }
        val arr = JSONArray()
        turns.takeLast(60).forEach { arr.put(JSONObject().put("r", it.role).put("t", it.text)) }
        try {
            historyFile.writeText(arr.toString())
        } catch (e: Exception) {
            // Storage full: the chat still works for this session.
        }
    }
}
