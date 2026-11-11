package com.fainet.faisalai

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.util.Locale

/**
 * Voice in and out for one screen. Create it as a field of the activity.
 *
 * Listening: with a Gemini key the app records the microphone and Gemini writes down
 * what was said (this works for Pashto and Dari). Without one it uses the phone's own
 * speech input.
 * Speaking: with a Gemini key Gemini's voice is used; otherwise the phone's voice.
 */
class Voice(private val act: AppCompatActivity) {

    enum class State { IDLE, RECORDING, WORKING }

    /** Called with what the user said. */
    var onText: ((String) -> Unit)? = null

    /** Called when listening starts, when it stops and the words are being worked out, and when it is done. */
    var onState: ((State) -> Unit)? = null

    private var recorder: MediaRecorder? = null
    private val recordFile: File get() = File(act.cacheDir, "voice.aac")

    private var player: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsPending: String? = null
    private var speakRequest = 0 // speech from an older request is not played

    private val micPermission = act.registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startRecording() else toast(R.string.perm_needed)
    }

    private val phoneSpeech = act.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (result.resultCode == Activity.RESULT_OK && !text.isNullOrBlank()) onText?.invoke(text)
    }

    private fun toast(res: Int) = Toast.makeText(act, res, Toast.LENGTH_SHORT).show()

    private fun language(): String = act.resources.configuration.locales[0].language

    // ---------- listening ----------

    /** Starts listening, or stops and delivers the words when already listening. */
    fun toggle() {
        stopSpeaking()
        if (recorder != null) {
            finishRecording()
            return
        }
        if (Prefs.geminiKey(act).isNotEmpty()) {
            val granted = ContextCompat.checkSelfPermission(act, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) startRecording() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            val tag = when (language()) {
                "ps" -> "ps-AF"
                "fa" -> "fa-AF"
                else -> "en-US"
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            try {
                phoneSpeech.launch(intent)
            } catch (e: Exception) {
                toast(R.string.no_recognizer)
            }
        }
    }

    private fun startRecording() {
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(act) else MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(16000)
            r.setAudioEncodingBitRate(32000)
            r.setMaxDuration(60000)
            r.setOutputFile(recordFile.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            // The microphone is in use by another app (for example a BVR recording).
            r.release()
            toast(R.string.perm_needed)
            return
        }
        recorder = r
        onState?.invoke(State.RECORDING)
    }

    private fun finishRecording() {
        val r = recorder ?: return
        recorder = null
        val recorded = try {
            r.stop()
            true
        } catch (e: RuntimeException) {
            false // stopped before any sound was captured
        }
        r.release()
        if (!recorded) {
            onState?.invoke(State.IDLE)
            toast(R.string.no_speech)
            return
        }
        onState?.invoke(State.WORKING)
        AI.transcribe(act, recordFile) { text, error ->
            onState?.invoke(State.IDLE)
            when {
                act.isDestroyed -> Unit
                !text.isNullOrBlank() -> onText?.invoke(text)
                error != null -> Toast.makeText(act, error, Toast.LENGTH_LONG).show()
                else -> toast(R.string.no_speech)
            }
        }
    }

    // ---------- speaking ----------

    fun speak(text: String) {
        stopSpeaking()
        if (text.isBlank()) return
        val request = speakRequest
        if (Prefs.geminiKey(act).isEmpty()) {
            phoneSpeak(text)
            return
        }
        AI.speech(act, text) { wav ->
            if (request != speakRequest || act.isDestroyed) return@speech
            if (wav == null || !play(wav)) phoneSpeak(text)
        }
    }

    private fun play(wav: ByteArray): Boolean = try {
        val file = File(act.cacheDir, "say.wav")
        file.writeBytes(wav)
        val p = MediaPlayer()
        p.setDataSource(file.absolutePath)
        p.setOnCompletionListener {
            it.release()
            if (player === it) player = null
        }
        p.prepare()
        p.start()
        player = p
        true
    } catch (e: Exception) {
        false
    }

    private fun phoneSpeak(text: String) {
        val engine = tts
        if (engine == null) {
            ttsPending = text
            tts = TextToSpeech(act.applicationContext) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                val waiting = ttsPending
                ttsPending = null
                if (waiting != null) phoneSpeak(waiting)
            }
            return
        }
        if (!ttsReady) {
            toast(R.string.no_voice)
            return
        }
        // Phones rarely have a Pashto voice; Dari is the closest one that may be installed.
        val wanted = if (language() == "ps") listOf("ps", "fa") else listOf(language())
        val ok = wanted.any { engine.setLanguage(Locale(it)) >= TextToSpeech.LANG_AVAILABLE }
        if (!ok) {
            toast(R.string.no_voice)
            return
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "faisal")
    }

    fun stopSpeaking() {
        speakRequest++
        try {
            player?.release()
        } catch (e: Exception) {
            // Already released.
        }
        player = null
        tts?.stop()
    }

    /** Call from the activity's onDestroy. */
    fun release() {
        stopSpeaking()
        try {
            recorder?.release()
        } catch (e: Exception) {
            // Already released.
        }
        recorder = null
        tts?.shutdown()
        tts = null
        onText = null
        onState = null
    }
}
