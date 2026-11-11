package com.fainet.faisalai

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import java.io.File
import java.util.UUID

/** Notes made in the app. Each note is a text file; a copy is kept in Download/Faisal/Notes. */
object NoteStore {
    private fun dir(c: android.content.Context) = File(c.filesDir, "notes").apply { mkdirs() }
    fun file(c: android.content.Context, id: String) = File(dir(c), "$id.txt")
    fun all(c: android.content.Context): List<File> = dir(c).listFiles()?.filter { it.extension == "txt" }?.sortedByDescending { it.lastModified() } ?: emptyList()
    fun title(text: String): String = text.lines().firstOrNull { it.isNotBlank() }?.trim()?.take(60) ?: ""

    fun save(c: android.content.Context, id: String, text: String) {
        val key = "note_uri_$id"
        val sp = Prefs.sp(c)
        sp.getString(key, null)?.let { Public.delete(c, android.net.Uri.parse(it)) }
        val f = file(c, id)
        if (text.isBlank()) {
            f.delete()
            sp.edit().remove(key).apply()
            return
        }
        f.writeText(text, Charsets.UTF_8)
        val safe = title(text).replace(Regex("[^\\p{L}\\p{N} ]+"), "").trim().ifEmpty { "Note" }.take(40)
        val uri = Public.saveText(c, "Notes", "$safe (${id.take(4)}).txt", text)
        sp.edit().apply { if (uri != null) putString(key, uri.toString()) else remove(key) }.apply()
    }

    fun delete(c: android.content.Context, id: String) {
        val key = "note_uri_$id"
        Prefs.sp(c).getString(key, null)?.let { Public.delete(c, android.net.Uri.parse(it)) }
        Prefs.sp(c).edit().remove(key).apply()
        file(c, id).delete()
    }
}

class NotesActivity : BaseActivity() {
    private var files: List<File> = emptyList()
    private lateinit var list: ListView
    private lateinit var empty: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notes)
        bar(R.string.t_notes)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        findViewById<View>(R.id.add).setOnClickListener { startActivity(Intent(this, NoteEditActivity::class.java)) }
        list.setOnItemClickListener { _, _, pos, _ ->
            startActivity(Intent(this, NoteEditActivity::class.java).putExtra("id", files[pos].nameWithoutExtension))
        }
        list.adapter = Adapter()
    }

    override fun onResume() {
        super.onResume()
        files = NoteStore.all(this)
        (list.adapter as BaseAdapter).notifyDataSetChanged()
        empty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = files.size
        override fun getItem(position: Int) = files[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val f = files[position]
            val text = try { f.readText() } catch (e: Exception) { "" }
            val box = (convertView as? LinearLayout) ?: LinearLayout(this@NotesActivity).also {
                it.orientation = LinearLayout.VERTICAL
                it.setBackgroundResource(R.drawable.bg_card)
                it.setPadding(dp(16), dp(12), dp(16), dp(12))
                it.layoutParams = AbsListViewParams()
                val t = TextView(this@NotesActivity); t.id = R.id.rowTitle; t.textSize = 16f; t.maxLines = 1
                t.setTextColor(attrColor(R.attr.colFg)); t.setTypeface(null, android.graphics.Typeface.BOLD)
                val b = TextView(this@NotesActivity); b.id = R.id.rowBody; b.textSize = 14f; b.maxLines = 2
                b.setTextColor(attrColor(R.attr.colMuted))
                val d = TextView(this@NotesActivity); d.id = R.id.rowMark; d.textSize = 12f
                d.setTextColor(attrColor(R.attr.colMuted))
                it.addView(t); it.addView(b); it.addView(d)
            }
            box.findViewById<TextView>(R.id.rowTitle).text = NoteStore.title(text)
            box.findViewById<TextView>(R.id.rowBody).text = text.lines().filter { it.isNotBlank() }.drop(1).joinToString(" ")
            box.findViewById<TextView>(R.id.rowMark).text =
                DateUtils.getRelativeTimeSpanString(f.lastModified(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            return box
        }
        private fun AbsListViewParams() = android.widget.AbsListView.LayoutParams(-1, -2)
    }
}

class NoteEditActivity : BaseActivity() {
    private var id = ""
    private lateinit var body: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_note)
        bar(R.string.t_notes)
        id = intent.getStringExtra("id") ?: UUID.randomUUID().toString()
        body = findViewById(R.id.body)
        val f = NoteStore.file(this, id)
        if (f.exists()) body.setText(f.readText())
        findViewById<Button>(R.id.btnShare).setOnClickListener {
            val text = body.text.toString()
            if (text.isBlank()) return@setOnClickListener
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), getString(R.string.share)))
        }
        findViewById<Button>(R.id.btnDelete).setOnClickListener {
            AlertDialog.Builder(this).setMessage(R.string.n_delete_q)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete) { _, _ ->
                    NoteStore.delete(this, id)
                    deleted = true
                    finish()
                }.show()
        }
    }

    private var deleted = false

    // The note is saved whenever the screen is left.
    override fun onPause() {
        if (!deleted) NoteStore.save(this, id, body.text.toString())
        super.onPause()
    }
}
