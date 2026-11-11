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
 * Lens (mode "lens"): identify what a photo shows.
 * Text Reader (mode "ocr"): read the text in a photo, in any script including Pashto and Dari.
 */
class VisionActivity : BaseActivity() {

    private var ocr = false
    private var photo: Bitmap? = null
    private var cameraUri: Uri? = null
    private var busy = false
    private var hasAnswer = false

    private lateinit var preview: ImageView
    private lateinit var out: TextView
    private lateinit var go: Button

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
        if (ocr) {
            system = "You are a careful text reader (OCR)."
            prompt = "Transcribe all text visible in this image exactly as written, in its original language " +
                "and script, keeping the line breaks. Reply with only the transcribed text. " +
                "If the image has no text, say so in one short sentence in $lang."
        } else {
            system = "You are a visual search assistant, like Google Lens. " + Prefs.style(this)
            prompt = if (question.isNotEmpty()) "Answer this question about the photo: $question"
            else "Identify what this photo shows. Name the main object, product, plant, animal, place or landmark " +
                "as specifically as you can, then give the most useful facts about it. Mention any visible text."
        }
        busy = true
        hasAnswer = false
        go.isEnabled = false
        out.text = getString(R.string.thinking)
        Gemini.ask(this, system, listOf(Gemini.Turn("user", prompt)), bmp) { answer, error ->
            busy = false
            go.isEnabled = true
            hasAnswer = answer != null
            out.text = answer ?: error
            if (answer != null) beep()
        }
    }
}
