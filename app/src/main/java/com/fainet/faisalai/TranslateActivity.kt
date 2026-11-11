package com.fainet.faisalai

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView

/** Translator between the languages listed in res/values/arrays.xml (tr_names / tr_values). */
class TranslateActivity : BaseActivity() {

    private var busy = false
    private var hasAnswer = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_translate)
        bar(R.string.t_tr)

        val names = resources.getStringArray(R.array.tr_names)
        val values = resources.getStringArray(R.array.tr_values)
        val from = findViewById<Spinner>(R.id.from)
        val to = findViewById<Spinner>(R.id.to)
        val input = findViewById<EditText>(R.id.input)
        val out = findViewById<TextView>(R.id.out)
        val go = findViewById<Button>(R.id.go)

        // The first entry of "from" is "Detect language"; the rest match "to" shifted by one.
        from.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf(getString(R.string.tr_auto)) + names)
        to.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names.toList())
        // Default target: Pashto when the app is in English, otherwise English.
        to.setSelection(if (Prefs.appLangName(this) == "English") 0 else 2)

        intent.getStringExtra("text")?.let { input.setText(it) }

        findViewById<ImageButton>(R.id.swap).setOnClickListener {
            if (from.selectedItemPosition == 0) return@setOnClickListener // nothing to swap with "Detect language"
            val source = from.selectedItemPosition - 1
            from.setSelection(to.selectedItemPosition + 1)
            to.setSelection(source)
            if (hasAnswer) {
                input.setText(out.text.toString())
                out.text = ""
                hasAnswer = false
            }
        }

        go.setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isEmpty()) {
                toast(getString(R.string.need_text))
                return@setOnClickListener
            }
            if (busy) return@setOnClickListener
            val source = if (from.selectedItemPosition == 0) "" else "from ${values[from.selectedItemPosition - 1]} "
            val target = values[to.selectedItemPosition]
            busy = true
            hasAnswer = false
            go.isEnabled = false
            out.text = getString(R.string.thinking)
            AI.ask(
                this,
                "You are a professional translator. Reply with only the translation, without notes or quotes.",
                listOf(AI.Turn("user", "Translate the text below ${source}into $target.\n\n${text.take(12000)}")),
                null
            ) { answer, error ->
                busy = false
                go.isEnabled = true
                hasAnswer = answer != null
                out.text = answer ?: error
                if (answer != null) beep()
            }
        }
        findViewById<Button>(R.id.btnCopy).setOnClickListener { if (hasAnswer) copy(out.text.toString()) }
        findViewById<Button>(R.id.btnSave).setOnClickListener { if (hasAnswer) saveNote("Translation", out.text.toString()) }
    }
}
