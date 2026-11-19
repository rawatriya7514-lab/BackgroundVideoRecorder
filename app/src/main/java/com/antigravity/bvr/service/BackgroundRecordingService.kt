package com.antigravity.bvr.service

import android.app.Notification
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
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
import androidx.lifecycle.lifecycleScope
import com.antigravity.bvr.BvrApplication
import com.antigravity.bvr.R
import com.antigravity.bvr.ui.MainActivity
import com.antigravity.bvr.util.FormatUtils
import com.antigravity.bvr.util.PermissionUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RecordingServiceState(
    val isRecording: Boolean = false,
    val isFinalizing: Boolean = false,
    val durationSeconds: Long = 0,
    val cameraFacing: Int = 0,
    val quality: String = "1080p",
    val audioEnabled: Boolean = true,
    val lastSavedUri: Uri? = null,
    val errorMessage: String? = null
)

typealias ServiceState = RecordingServiceState

class BackgroundRecordingService : LifecycleService() {

    companion object {
        private const val TAG = "BvrService"
        const val NOTIFICATION_ID = 1001

        // Intent Actions
        const val ACTION_START_RECORDING = "com.antigravity.bvr.action.START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.antigravity.bvr.action.STOP_RECORDING"
        const val ACTION_STATUS_UPDATE = "com.antigravity.bvr.action.STATUS_UPDATE"

        // Intent Extras
        const val EXTRA_CAMERA_FACING = "extra_camera_facing" // 0 = BACK, 1 = FRONT
        const val EXTRA_QUALITY = "extra_quality"             // "1080p" or "720p"
        const val EXTRA_ENABLE_AUDIO = "extra_enable_audio"

        const val CAMERA_BACK = 0
        const val CAMERA_FRONT = 1

        private val _recordingState = MutableStateFlow(RecordingServiceState())
        val recordingState: StateFlow<RecordingServiceState> = _recordingState.asStateFlow()
    }

    private val binder = LocalBinder()
    private var wakeLock: PowerManager.WakeLock? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null

    private var timerJob: Job? = null
    private var durationSeconds = 0L

    private var currentCameraFacing = CAMERA_BACK
    private var currentQuality = "1080p"
    private var currentAudioEnabled = true

    inner class LocalBinder : Binder() {
        fun getService(): BackgroundRecordingService = this@BackgroundRecordingService
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "BackgroundRecordingService onCreate")
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START_RECORDING -> {
                currentCameraFacing = intent.getIntExtra(EXTRA_CAMERA_FACING, CAMERA_BACK)
                currentQuality = intent.getStringExtra(EXTRA_QUALITY) ?: "1080p"
                currentAudioEnabled = intent.getBooleanExtra(EXTRA_ENABLE_AUDIO, true)
                startBackgroundRecording()
            }
            ACTION_STOP_RECORDING -> {
                stopBackgroundRecording()
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Initializes foreground service and starts camera recording without visible preview.
     */
    private fun startBackgroundRecording() {
        if (_recordingState.value.isRecording) {
            Log.w(TAG, "Recording is already active.")
            return
        }

        // Start Foreground immediately with camera & microphone foreground service types for Android 14+
        val notification = buildNotification(0L)
        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundType)

        // Initialize CameraX and bind directly to this LifecycleService
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                setupAndStartVideoCapture()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get CameraProvider", e)
                _recordingState.value = _recordingState.value.copy(
                    isRecording = false,
                    errorMessage = "Failed to initialize camera: ${e.localizedMessage}"
                )
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setupAndStartVideoCapture() {
        val provider = cameraProvider ?: return

        // 1. Configure Camera Selector
        val cameraSelector = if (currentCameraFacing == CAMERA_FRONT) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        // 2. Configure Quality Selector
        val quality = when (currentQuality) {
            "720p" -> Quality.HD
            else -> Quality.FHD
        }
        val qualitySelector = QualitySelector.from(
            quality,
            androidx.camera.video.FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
        )

        // 3. Build Recorder & VideoCapture use case
        val recorder = Recorder.Builder()
            .setQualitySelector(qualitySelector)
            .build()
        videoCapture = VideoCapture.withOutput(recorder)

        try {
            provider.unbindAll()
            // Bind VideoCapture directly to this LifecycleService (no PreviewView required!)
            provider.bindToLifecycle(
                this,
                cameraSelector,
                videoCapture
            )
            Log.d(TAG, "VideoCapture bound to LifecycleService successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Use case binding failed", e)
            _recordingState.value = _recordingState.value.copy(
                isRecording = false,
                errorMessage = "Camera binding error: ${e.localizedMessage}"
            )
            stopSelf()
            return
        }

        // 4. Configure MediaStore target file in Movies/BackgroundRecorder
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val videoFileName = "BVR_$timeStamp.mp4"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, videoFileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/BackgroundRecorder"
                )
            }
        }

        val mediaStoreOutput = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        // 5. Prepare and start recording
        val pendingRecording = videoCapture?.output?.prepareRecording(this, mediaStoreOutput)
            ?: run {
                Log.e(TAG, "Unable to prepare recording output")
                stopSelf()
                return
            }

        // Enable audio if requested and permission is granted
        if (currentAudioEnabled && PermissionUtils.hasAudioPermission(this)) {
            pendingRecording.withAudioEnabled()
        }

        activeRecording = pendingRecording.start(ContextCompat.getMainExecutor(this)) { recordEvent ->
            when (recordEvent) {
                is VideoRecordEvent.Start -> {
                    Log.d(TAG, "Video recording started")
                    durationSeconds = 0L
                    _recordingState.value = ServiceState(
                        isRecording = true,
                        isFinalizing = false,
                        durationSeconds = 0,
                        cameraFacing = currentCameraFacing,
                        quality = currentQuality,
                        audioEnabled = currentAudioEnabled
                    )
                    startTimer()
                }
                is VideoRecordEvent.Finalize -> {
                    Log.d(TAG, "Video recording finalized")
                    stopTimer()
                    val savedUri = recordEvent.outputResults.outputUri
                    val hasError = recordEvent.hasError()
                    val errorMsg = if (hasError) "Recording error: ${recordEvent.error}" else null

                    if (hasError) {
                        Log.e(TAG, "Finalize error code: ${recordEvent.error}")
                    } else {
                        Log.d(TAG, "Recording saved to: $savedUri")
                    }

                    _recordingState.value = _recordingState.value.copy(
                        isRecording = false,
                        isFinalizing = false,
                        lastSavedUri = savedUri,
                        errorMessage = errorMsg
                    )

                    // Teardown foreground service once file is saved
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                is VideoRecordEvent.Status -> {
                    // Ongoing recording status check
                }
            }
        }
    }

    /**
     * Halts the active video recording. The file will finalize asynchronously.
     */
    private fun stopBackgroundRecording() {
        if (!_recordingState.value.isRecording && !_recordingState.value.isFinalizing) {
            stopSelf()
            return
        }

        _recordingState.value = _recordingState.value.copy(isFinalizing = true)
        try {
            activeRecording?.stop()
            activeRecording = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping active recording", e)
            stopSelf()
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = lifecycleScope.launch {
            while (_recordingState.value.isRecording) {
                delay(1000L)
                durationSeconds++
                _recordingState.value = _recordingState.value.copy(durationSeconds = durationSeconds)
                updateNotification(durationSeconds)
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    private fun buildNotification(durationSec: Long): Notification {
        val durationFormatted = FormatUtils.formatDurationSeconds(durationSec)
        val facingText = if (currentCameraFacing == CAMERA_FRONT) "Front" else "Back"
        val qualityText = currentQuality

        // Intent to launch MainActivity when clicking notification
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action intent to stop recording from notification button
        val stopIntent = Intent(this, BackgroundRecordingService::class.java).apply {
            action = ACTION_STOP_RECORDING
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, BvrApplication.CHANNEL_ID)
            .setContentTitle("Background Video Recording")
            .setContentText("REC $durationFormatted • $facingText Camera • $qualityText")
            .setSubText("Screen-Off Safe")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                R.drawable.ic_record_stop,
                "Stop Recording",
                stopPendingIntent
            )
            .build()
    }

    private fun updateNotification(durationSec: Long) {
        val notification = buildNotification(durationSec)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Prevents CPU sleep while recording even if user presses the power button or locks screen.
     */
    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "BackgroundVideoRecorder::RecordingWakeLock"
            ).apply {
                setReferenceCounted(false)
                // 6 hours safety timeout
                acquire(6 * 60 * 60 * 1000L)
            }
            Log.d(TAG, "Partial WakeLock acquired")
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "Partial WakeLock released")
            }
        }
        wakeLock = null
    }

    override fun onDestroy() {
        Log.d(TAG, "BackgroundRecordingService onDestroy")
        stopTimer()
        try {
            activeRecording?.stop()
        } catch (e: Exception) {
            // Ignore
        }
        cameraProvider?.unbindAll()
        releaseWakeLock()
        _recordingState.value = ServiceState(isRecording = false)
        super.onDestroy()
    }
}
