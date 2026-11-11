package com.fainet.faisalai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

/**
 * Live: the smart-glasses mode. The camera stays open; QR codes and barcodes are read
 * as soon as they are in view, and the user can ask about what the camera sees by
 * voice. The answer is shown and spoken.
 */
class LiveActivity : BaseActivity() {

    private val voice = Voice(this)
    private val scanThread = Executors.newSingleThreadExecutor()
    private val reader = MultiFormatReader()

    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var out: TextView
    private lateinit var code: TextView
    private lateinit var mic: ImageButton

    private var busy = false
    private var lastScan = 0L
    private var rotate = false
    private var codeValue = ""

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else {
            toast(getString(R.string.perm_needed))
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BvrService.running) {
            // The camera is busy recording; two users of it at once would stop the recording.
            toast(getString(R.string.stop_bvr_first))
            finish()
            return
        }
        setContentView(R.layout.activity_live)
        bar(R.string.t_live)
        preview = findViewById(R.id.preview)
        status = findViewById(R.id.status)
        out = findViewById(R.id.out)
        code = findViewById(R.id.code)
        mic = findViewById(R.id.mic)

        reader.setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(
                    BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX, BarcodeFormat.EAN_13, BarcodeFormat.EAN_8,
                    BarcodeFormat.UPC_A, BarcodeFormat.UPC_E, BarcodeFormat.CODE_128, BarcodeFormat.CODE_39, BarcodeFormat.ITF
                )
            )
        )

        code.setOnClickListener {
            // A link opens as a page; any other code (e.g. a product barcode) is searched on the web.
            val link = codeValue.startsWith("http://") || codeValue.startsWith("https://")
            startActivity(Intent(this, BrowserActivity::class.java).putExtra(if (link) "url" else "query", codeValue))
        }
        code.setOnLongClickListener {
            copy(codeValue)
            true
        }

        voice.onText = { said -> ask(said) }
        voice.onState = { state ->
            mic.setImageResource(if (state == Voice.State.RECORDING) R.drawable.ic_stop else R.drawable.ic_mic)
            status.setText(
                when (state) {
                    Voice.State.RECORDING -> R.string.listening
                    Voice.State.WORKING -> R.string.thinking
                    else -> R.string.live_hint
                }
            )
        }
        mic.setOnClickListener { if (!busy) voice.toggle() }
        // Type a question about what the camera sees and press send on the keyboard.
        val askBox = findViewById<android.widget.EditText>(R.id.askBox)
        askBox.setOnEditorActionListener { _, actionId, _ ->
            if (actionId != android.view.inputmethod.EditorInfo.IME_ACTION_SEND) return@setOnEditorActionListener false
            val text = askBox.text.toString().trim().ifEmpty { getString(R.string.ask_what) }
            askBox.setText("")
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(askBox.windowToken, 0)
            ask(text)
            true
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val view = Preview.Builder().build()
                view.setSurfaceProvider(preview.surfaceProvider)
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(scanThread) { image -> scan(image) }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, view, analysis)
            } catch (e: Exception) {
                toast(getString(R.string.cam_wait))
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Looks for a QR code or barcode in one camera frame. Runs on the scan thread, about four times a second. */
    private fun scan(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastScan < 250) return
            lastScan = now

            // The first plane of a camera frame is its brightness, which is all a code reader needs.
            val plane = image.planes[0]
            val w = image.width
            val h = image.height
            val stride = plane.rowStride
            val data = ByteArray(stride * h)
            val buffer = plane.buffer
            buffer.get(data, 0, minOf(buffer.remaining(), data.size))

            // Barcodes are only read along rows, so every second frame is checked turned by 90 degrees.
            rotate = !rotate
            val source = if (rotate) {
                val turned = ByteArray(w * h)
                for (y in 0 until h) {
                    val row = y * stride
                    for (x in 0 until w) turned[x * h + (h - 1 - y)] = data[row + x]
                }
                PlanarYUVLuminanceSource(turned, h, w, 0, 0, h, w, false)
            } else {
                PlanarYUVLuminanceSource(data, stride, h, 0, 0, w, h, false)
            }
            val found = try {
                reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
            } catch (e: NotFoundException) {
                null
            } finally {
                reader.reset()
            }
            if (found != null) Net.main.post { showCode(found) }
        } catch (e: Exception) {
            // A frame that cannot be read is skipped; the next one is tried.
        } finally {
            image.close()
        }
    }

    private fun showCode(value: String) {
        if (isDestroyed || value == codeValue) return
        codeValue = value
        code.text = value
        code.visibility = View.VISIBLE
    }

    private fun ask(question: String) {
        if (busy) return
        val frame = preview.bitmap
        if (frame == null) {
            toast(getString(R.string.cam_wait))
            return
        }
        val big = maxOf(frame.width, frame.height)
        val photo = if (big > 1280)
            Bitmap.createScaledBitmap(frame, frame.width * 1280 / big, frame.height * 1280 / big, true)
        else frame

        busy = true
        out.text = question + "\n\n" + getString(R.string.thinking)
        val system = "You are Faisal, an assistant that sees through the phone's camera like smart glasses. " +
            "Answer about what the camera shows. " + Prefs.style(this) + " Keep the answer short enough to be spoken aloud."
        AI.ask(this, system, listOf(AI.Turn("user", question)), photo) { answer, error ->
            busy = false
            if (isDestroyed) return@ask
            out.text = question + "\n\n" + (answer ?: error ?: "")
            if (answer != null) voice.speak(answer)
        }
    }

    override fun onPause() {
        voice.stopSpeaking()
        super.onPause()
    }

    override fun onDestroy() {
        scanThread.shutdown()
        voice.release()
        super.onDestroy()
    }
}
