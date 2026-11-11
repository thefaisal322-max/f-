package com.fainet.faisalai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Lens (mode "lens"): reads barcodes and QR codes in a photo, identifies what the
 * photo shows, and searches the web for it.
 * Text Reader (mode "ocr"): reads the text in a photo, in any language and script.
 */
class VisionActivity : BaseActivity() {

    private var ocr = false
    private var photo: Bitmap? = null
    private var cameraUri: Uri? = null
    private var busy = false
    private var hasAnswer = false
    private var webQuery = ""

    private lateinit var preview: ImageView
    private lateinit var out: TextView
    private lateinit var go: Button
    private lateinit var codeBox: View
    private lateinit var code: TextView
    private lateinit var btnWeb: Button

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) cameraUri?.let { setPhoto(it) }
    }
    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) setPhoto(uri)
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) openCamera() else toast(getString(R.string.perm_needed))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vision)
        ocr = intent.getStringExtra("mode") == "ocr"
        bar(if (ocr) R.string.t_ocr else R.string.t_lens)

        preview = findViewById(R.id.preview)
        out = findViewById(R.id.out)
        go = findViewById(R.id.go)
        codeBox = findViewById(R.id.codeBox)
        code = findViewById(R.id.code)
        btnWeb = findViewById(R.id.btnWeb)
        val question = findViewById<EditText>(R.id.question)

        findViewById<TextView>(R.id.hint).setText(if (ocr) R.string.ocr_hint else R.string.lens_hint)
        go.setText(if (ocr) R.string.ocr_go else R.string.lens_go)
        if (ocr) question.visibility = View.GONE

        findViewById<Button>(R.id.btnCamera).setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera()
            else cameraPermission.launch(Manifest.permission.CAMERA)
        }
        findViewById<Button>(R.id.btnGallery).setOnClickListener { pickImage.launch("image/*") }
        go.setOnClickListener { run(question.text.toString().trim()) }

        findViewById<Button>(R.id.btnCodeCopy).setOnClickListener { copy(code.text.toString()) }
        findViewById<Button>(R.id.btnCodeOpen).setOnClickListener {
            // A link opens as a page; any other code (e.g. a product barcode) is searched on the web.
            val value = code.text.toString().trim()
            val link = value.startsWith("http://") || value.startsWith("https://")
            startActivity(Intent(this, BrowserActivity::class.java).putExtra(if (link) "url" else "query", value))
        }
        btnWeb.setOnClickListener {
            if (webQuery.isNotEmpty()) startActivity(Intent(this, BrowserActivity::class.java).putExtra("query", webQuery))
        }

        findViewById<Button>(R.id.btnCopy).setOnClickListener { if (hasAnswer) copy(out.text.toString()) }
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            if (hasAnswer) saveNote(if (ocr) "Text" else "Lens", out.text.toString())
        }
        findViewById<Button>(R.id.btnTranslate).setOnClickListener {
            if (hasAnswer) startActivity(Intent(this, TranslateActivity::class.java).putExtra("text", out.text.toString()))
        }
    }

    private fun openCamera() {
        val uri = Storage.uri(this, File(cacheDir, "shot.jpg"))
        cameraUri = uri
        takePicture.launch(uri)
    }

    private fun setPhoto(uri: Uri) {
        val bmp = Storage.loadBitmap(this, uri)
        if (bmp == null) {
            toast(getString(R.string.need_img))
            return
        }
        photo = bmp
        preview.setImageBitmap(bmp)
        preview.visibility = View.VISIBLE
        codeBox.visibility = View.GONE
        btnWeb.visibility = View.GONE
        if (!ocr) {
            // Barcodes and QR codes are read on the phone, without internet.
            Net.pool.execute {
                val found = Codes.scan(bmp)
                Net.main.post {
                    if (found != null && photo === bmp) {
                        code.text = found
                        codeBox.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun run(question: String) {
        val bmp = photo
        if (bmp == null) {
            toast(getString(R.string.need_img))
            return
        }
        if (busy) return
        val lang = Prefs.appLangName(this)
        val system: String
        val prompt: String
        val nameFirst = !ocr && question.isEmpty()
        if (ocr) {
            system = "You are a careful text reader (OCR) for every language and script."
            prompt = "Transcribe all text visible in this image exactly as written, in its original language " +
                "and script, keeping the line breaks. Reply with only the transcribed text. " +
                "If the image has no text, say so in one short sentence in $lang."
        } else {
            system = "You are a visual search assistant, like Google Lens. " + Prefs.style(this)
            prompt = if (question.isNotEmpty()) "Answer this question about the photo: $question"
            else "Identify what this photo shows. On the first line write only its name, as specific as you can " +
                "(product and model, plant or animal species, place or landmark), usable as a web search. " +
                "Then leave a blank line and give the most useful facts about it. Mention any visible text."
        }
        busy = true
        hasAnswer = false
        go.isEnabled = false
        btnWeb.visibility = View.GONE
        out.text = getString(R.string.thinking)
        AI.ask(this, system, listOf(AI.Turn("user", prompt)), bmp) { answer, error ->
            busy = false
            go.isEnabled = true
            hasAnswer = answer != null
            out.text = answer ?: error
            if (answer != null) {
                beep()
                if (!ocr) {
                    webQuery = if (nameFirst) answer.lineSequence().firstOrNull { it.isNotBlank() }?.take(120) ?: "" else question
                    btnWeb.visibility = if (webQuery.isNotEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }
}
