package com.fainet.faisalai

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** BVR screen: start and stop background video recording. */
class BvrActivity : BaseActivity() {

    private lateinit var status: TextView
    private lateinit var toggle: Button

    private fun needed(): Array<String> {
        val list = arrayListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT <= 28) list.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        return list.toTypedArray()
    }

    private fun allGranted(): Boolean =
        needed().all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (allGranted()) startRecording() else toast(getString(R.string.perm_needed))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bvr)
        bar(R.string.t_bvr)
        status = findViewById(R.id.status)
        toggle = findViewById(R.id.toggle)
        toggle.setOnClickListener {
            if (BvrService.running) {
                startService(Intent(this, BvrService::class.java).setAction(BvrService.STOP))
            } else if (allGranted()) {
                startRecording()
            } else {
                permissions.launch(needed())
            }
        }
    }

    private fun startRecording() {
        ContextCompat.startForegroundService(this, Intent(this, BvrService::class.java).setAction(BvrService.START))
    }

    private fun refresh() {
        status.setText(if (BvrService.running) R.string.bvr_running else R.string.bvr_idle)
        toggle.setText(if (BvrService.running) R.string.bvr_stop else R.string.bvr_start)
    }

    override fun onResume() {
        super.onResume()
        BvrService.listener = { refresh() }
        refresh()
    }

    override fun onPause() {
        BvrService.listener = null
        super.onPause()
    }
}

/**
 * Records video with sound as a foreground service, so it keeps going while
 * other apps are open or the screen is off. Android requires the ongoing
 * notification for the whole recording; it carries the Stop button.
 */
class BvrService : LifecycleService() {

    companion object {
        const val START = "com.fainet.faisalai.BVR_START"
        const val STOP = "com.fainet.faisalai.BVR_STOP"
        private const val CHANNEL = "bvr"
        private const val NOTIFICATION_ID = 7

        @Volatile
        var running = false
        var listener: (() -> Unit)? = null
    }

    private var recording: Recording? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == STOP) {
            stopRecording()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        try {
            val type = if (Build.VERSION.SDK_INT >= 30)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
        } catch (e: Exception) {
            // Android refused the foreground service (for example a missing permission).
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        listener?.invoke()
        startRecording()
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.bvr_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val stop = PendingIntent.getService(this, 1, Intent(this, BvrService::class.java).setAction(STOP), flags)
        val open = PendingIntent.getActivity(this, 2, Intent(this, BvrActivity::class.java), flags)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_bvr)
            .setContentTitle(getString(R.string.bvr_notif_title))
            .setContentText(getString(R.string.bvr_notif_text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, getString(R.string.bvr_stop), stop)
            .build()
    }

    @SuppressLint("MissingPermission") // BvrActivity starts this service only after the permissions are granted.
    private fun startRecording() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val quality = when (Prefs.quality(this)) {
                    "SD" -> Quality.SD
                    "FHD" -> Quality.FHD
                    else -> Quality.HD
                }
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(quality)))
                    .build()
                val capture = VideoCapture.withOutput(recorder)
                val camera = if (Prefs.frontCam(this)) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                provider.unbindAll()
                provider.bindToLifecycle(this, camera, capture)

                val name = "FaisalAI_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/FaisalAI")
                }
                val output = MediaStoreOutputOptions
                    .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                    .setContentValues(values)
                    .build()
                recording = recorder.prepareRecording(this, output)
                    .withAudioEnabled()
                    .start(ContextCompat.getMainExecutor(this)) { event ->
                        if (event is VideoRecordEvent.Finalize) shutDown()
                    }
            } catch (e: Exception) {
                shutDown() // camera busy or unavailable
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopRecording() {
        val r = recording
        if (r != null) {
            recording = null
            r.stop() // the Finalize event then calls shutDown()
        } else {
            shutDown()
        }
    }

    private fun shutDown() {
        running = false
        listener?.invoke()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        recording?.stop()
        recording = null
        running = false
        listener?.invoke()
        super.onDestroy()
    }
}
