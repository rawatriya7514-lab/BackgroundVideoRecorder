package com.antigravity.bvr.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.antigravity.bvr.R
import com.antigravity.bvr.databinding.ActivityMainBinding
import com.antigravity.bvr.service.BackgroundRecordingService
import com.antigravity.bvr.service.RecordingServiceState
import com.antigravity.bvr.util.FormatUtils
import com.antigravity.bvr.util.PermissionUtils
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var selectedCameraFacing = BackgroundRecordingService.CAMERA_BACK
    private var selectedQuality = "1080p"
    private var isAudioEnabled = true
    private var isPreviewVisible = false

    private var previewCameraProvider: ProcessCameraProvider? = null
    private var pulseAnimation: AlphaAnimation? = null

    // Permission launcher for runtime permissions
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[android.Manifest.permission.CAMERA] ?: false
        val audioGranted = permissions[android.Manifest.permission.RECORD_AUDIO] ?: false

        if (cameraGranted && audioGranted) {
            Toast.makeText(this, "Permissions granted. Ready to record.", Toast.LENGTH_SHORT).show()
            if (isPreviewVisible) {
                startCameraPreview()
            }
        } else {
            showPermissionDeniedExplanation()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Enable edge-to-edge layout safely
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWindowInsets()
        setupUI()
        setupListeners()
        observeRecordingState()
        checkConsentAndPermissions()
    }

    override fun onResume() {
        super.onResume()
        updateStorageDisplay()
    }

    override fun onPause() {
        super.onPause()
        stopCameraPreview()
    }

    /**
     * Fixes top status bar / notch / camera cutout overlap and bottom gesture navigation bar overlap.
     */
    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBarInset = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBarInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars())

            binding.headerBar.setPadding(
                binding.headerBar.paddingLeft,
                statusBarInset.top + 8,
                binding.headerBar.paddingRight,
                8
            )

            binding.nestedScrollView.setPadding(
                binding.nestedScrollView.paddingLeft,
                binding.nestedScrollView.paddingTop,
                binding.nestedScrollView.paddingRight,
                navBarInset.bottom + 16
            )

            insets
        }
    }

    private fun setupUI() {
        // Load saved preferences
        val prefs = getSharedPreferences(QuickRecordActivity.PREFS_SETTINGS, Context.MODE_PRIVATE)
        selectedCameraFacing = prefs.getInt(QuickRecordActivity.KEY_CAMERA_FACING, BackgroundRecordingService.CAMERA_BACK)
        selectedQuality = prefs.getString(QuickRecordActivity.KEY_QUALITY, "1080p") ?: "1080p"
        isAudioEnabled = prefs.getBoolean(QuickRecordActivity.KEY_AUDIO_ENABLED, true)

        // Initialize Camera Lens Selection
        if (selectedCameraFacing == BackgroundRecordingService.CAMERA_FRONT) {
            binding.toggleGroupCamera.check(R.id.btn_select_front)
            binding.tvSelectedCameraText.text = "Front (Selfie)"
        } else {
            binding.toggleGroupCamera.check(R.id.btn_select_rear)
            binding.tvSelectedCameraText.text = "Rear (Main)"
        }

        // Initialize Resolution Selection
        when (selectedQuality) {
            "4K" -> {
                binding.toggleGroupQuality.check(R.id.btn_quality_4k)
                binding.tvSelectedResText.text = "4K Ultra HD"
            }
            "720p" -> {
                binding.toggleGroupQuality.check(R.id.btn_quality_720p)
                binding.tvSelectedResText.text = "720p HD"
            }
            else -> {
                binding.toggleGroupQuality.check(R.id.btn_quality_1080p)
                binding.tvSelectedResText.text = "1080p Full HD"
            }
        }

        // Initialize Audio Switch
        binding.switchAudio.isChecked = isAudioEnabled
        updateAudioUiState(isAudioEnabled)

        updateActiveSummaryText()

        // Pulse animation for recording state
        pulseAnimation = AlphaAnimation(0.35f, 1.0f).apply {
            duration = 750
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
        }

        updateStorageDisplay()
    }

    private fun setupListeners() {
        // Hero Trigger Button (Start / Stop)
        binding.btnHeroRecord.setOnClickListener {
            handleRecordToggleClick()
        }

        // Camera Lens Segmented Toggle
        binding.toggleGroupCamera.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                selectedCameraFacing = if (checkedId == R.id.btn_select_front) {
                    BackgroundRecordingService.CAMERA_FRONT
                } else {
                    BackgroundRecordingService.CAMERA_BACK
                }
                binding.tvSelectedCameraText.text = if (selectedCameraFacing == BackgroundRecordingService.CAMERA_FRONT) {
                    "Front (Selfie)"
                } else {
                    "Rear (Main)"
                }
                updateActiveSummaryText()
                saveCurrentPreferences()

                if (isPreviewVisible) {
                    startCameraPreview()
                }
            }
        }

        // Resolution Segmented Toggle
        binding.toggleGroupQuality.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.btn_quality_4k -> {
                        selectedQuality = "4K"
                        binding.tvSelectedResText.text = "4K Ultra HD"
                    }
                    R.id.btn_quality_720p -> {
                        selectedQuality = "720p"
                        binding.tvSelectedResText.text = "720p HD"
                    }
                    else -> {
                        selectedQuality = "1080p"
                        binding.tvSelectedResText.text = "1080p Full HD"
                    }
                }
                updateActiveSummaryText()
                saveCurrentPreferences()
            }
        }

        // Audio Toggle Switch
        binding.switchAudio.setOnCheckedChangeListener { _, isChecked ->
            isAudioEnabled = isChecked
            updateAudioUiState(isChecked)
            updateActiveSummaryText()
            saveCurrentPreferences()
        }

        // Framing Preview Collapsible Card
        binding.llPreviewHeader.setOnClickListener {
            toggleFramingPreview()
        }

        // 1-Tap Home Screen Shortcut Pinning
        binding.btnPinShortcut.setOnClickListener {
            pinQuickRecordShortcut()
        }

        // Gallery navigation button
        binding.btnOpenGallery.setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }

        // Info / Consent dialog button
        binding.btnInfoConsent.setOnClickListener {
            showConsentDialog(forceShow = true)
        }
    }

    private fun updateAudioUiState(enabled: Boolean) {
        if (enabled) {
            binding.ivMicStatus.setImageResource(R.drawable.ic_mic_on)
            binding.ivMicStatus.setColorFilter(ContextCompat.getColor(this, R.color.brand_cyan))
            binding.tvAudioSubtext.text = "Enabled (Captures stereo sound)"
        } else {
            binding.ivMicStatus.setImageResource(R.drawable.ic_mic_off)
            binding.ivMicStatus.setColorFilter(ContextCompat.getColor(this, R.color.text_muted))
            binding.tvAudioSubtext.text = "Muted (No audio track recorded)"
        }
    }

    private fun updateActiveSummaryText() {
        val lens = if (selectedCameraFacing == BackgroundRecordingService.CAMERA_FRONT) "Front" else "Rear"
        val audio = if (isAudioEnabled) "Audio On" else "Muted"
        binding.tvActiveSummary.text = "$lens • $selectedQuality • $audio"
    }

    private fun toggleFramingPreview() {
        isPreviewVisible = !isPreviewVisible
        if (isPreviewVisible) {
            binding.flPreviewContainer.visibility = View.VISIBLE
            binding.tvPreviewToggleAction.text = "Hide"
            startCameraPreview()
        } else {
            binding.flPreviewContainer.visibility = View.GONE
            binding.tvPreviewToggleAction.text = "Show"
            stopCameraPreview()
        }
    }

    private fun startCameraPreview() {
        if (!PermissionUtils.hasCameraPermission(this)) {
            requestPermissionsLauncher.launch(PermissionUtils.getRequiredPermissions())
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                previewCameraProvider = cameraProviderFuture.get()
                val cameraSelector = if (selectedCameraFacing == BackgroundRecordingService.CAMERA_FRONT) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }
                previewCameraProvider?.unbindAll()
                previewCameraProvider?.bindToLifecycle(this, cameraSelector, preview)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCameraPreview() {
        try {
            previewCameraProvider?.unbindAll()
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun handleRecordToggleClick() {
        if (!PermissionUtils.hasAllPermissions(this)) {
            requestPermissionsLauncher.launch(PermissionUtils.getRequiredPermissions())
            return
        }

        // Always release preview before service starts recording
        stopCameraPreview()
        if (isPreviewVisible) {
            binding.flPreviewContainer.visibility = View.GONE
            binding.tvPreviewToggleAction.text = "Show"
            isPreviewVisible = false
        }

        val currentState = BackgroundRecordingService.recordingState.value
        if (currentState.isRecording || currentState.isFinalizing) {
            // Send Stop Intent
            val stopIntent = Intent(this, BackgroundRecordingService::class.java).apply {
                action = BackgroundRecordingService.ACTION_STOP_RECORDING
            }
            startService(stopIntent)
        } else {
            // Send Start Intent
            val startIntent = Intent(this, BackgroundRecordingService::class.java).apply {
                action = BackgroundRecordingService.ACTION_START_RECORDING
                putExtra(BackgroundRecordingService.EXTRA_CAMERA_FACING, selectedCameraFacing)
                putExtra(BackgroundRecordingService.EXTRA_QUALITY, selectedQuality)
                putExtra(BackgroundRecordingService.EXTRA_ENABLE_AUDIO, isAudioEnabled)
            }
            ContextCompat.startForegroundService(this, startIntent)
        }
    }

    private fun observeRecordingState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                BackgroundRecordingService.recordingState.collect { state ->
                    updateUIState(state)
                }
            }
        }
    }

    private fun updateUIState(state: RecordingServiceState) {
        if (state.isRecording) {
            // Active Recording UI
            binding.tvStatusBadge.text = "REC"
            binding.tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_recording)
            binding.tvTimer.text = FormatUtils.formatDurationSeconds(state.durationSeconds)

            // Hero Button Active State
            binding.btnHeroRecord.setBackgroundResource(R.drawable.bg_record_btn_active)
            binding.btnHeroRecord.setImageResource(R.drawable.ic_record_stop)
            binding.btnHeroRecord.isEnabled = true
            binding.tvBtnStatusLabel.text = "TAP TO STOP & SAVE"
            binding.tvBtnStatusLabel.setTextColor(ContextCompat.getColor(this, R.color.record_stop_red))

            // Start pulse animation on outer ring
            binding.vPulseRing.visibility = View.VISIBLE
            if (binding.vPulseRing.animation == null) {
                binding.vPulseRing.startAnimation(pulseAnimation)
            }

            // Lock controls during capture to ensure stream integrity
            binding.btnSelectRear.isEnabled = false
            binding.btnSelectFront.isEnabled = false
            binding.btnQuality4k.isEnabled = false
            binding.btnQuality1080p.isEnabled = false
            binding.btnQuality720p.isEnabled = false
            binding.switchAudio.isEnabled = false
            binding.btnPinShortcut.isEnabled = false
            binding.cardPreview.isEnabled = false
            binding.tvScreenOffNote.visibility = View.VISIBLE
        } else if (state.isFinalizing) {
            // Finalizing / Saving state
            binding.tvStatusBadge.text = "SAVING"
            binding.vPulseRing.clearAnimation()
            binding.vPulseRing.visibility = View.GONE
            binding.tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_idle)
            binding.tvBtnStatusLabel.text = "FINALIZING & SAVING..."
            binding.tvBtnStatusLabel.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
            binding.btnHeroRecord.isEnabled = false
        } else {
            // Idle Standby UI
            binding.tvStatusBadge.text = "STANDBY"
            binding.vPulseRing.clearAnimation()
            binding.vPulseRing.visibility = View.GONE
            binding.tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_idle)
            binding.tvTimer.text = "00:00:00"

            // Hero Button Idle State
            binding.btnHeroRecord.setBackgroundResource(R.drawable.bg_record_btn_idle)
            binding.btnHeroRecord.setImageResource(R.drawable.ic_record_start)
            binding.btnHeroRecord.isEnabled = true
            binding.tvBtnStatusLabel.text = "TAP TO START RECORDING"
            binding.tvBtnStatusLabel.setTextColor(ContextCompat.getColor(this, R.color.record_start_green))

            // Re-enable controls
            binding.btnSelectRear.isEnabled = true
            binding.btnSelectFront.isEnabled = true
            binding.btnQuality4k.isEnabled = true
            binding.btnQuality1080p.isEnabled = true
            binding.btnQuality720p.isEnabled = true
            binding.switchAudio.isEnabled = true
            binding.btnPinShortcut.isEnabled = true
            binding.cardPreview.isEnabled = true
            binding.tvScreenOffNote.visibility = View.GONE

            state.lastSavedUri?.let {
                Toast.makeText(this, "Recording saved to Movies/BackgroundRecorder", Toast.LENGTH_LONG).show()
                updateStorageDisplay()
            }

            state.errorMessage?.let { errorMsg: String ->
                Toast.makeText(this, errorMsg as CharSequence, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateStorageDisplay() {
        val freeSpace = FormatUtils.getAvailableStorageSpace()
        binding.tvHeaderStorage.text = freeSpace
    }

    private fun checkConsentAndPermissions() {
        if (!ConsentDialog.hasUserAcceptedConsent(this)) {
            showConsentDialog(forceShow = false)
        } else {
            if (!PermissionUtils.hasAllPermissions(this)) {
                requestPermissionsLauncher.launch(PermissionUtils.getRequiredPermissions())
            }
        }
    }

    private fun showConsentDialog(forceShow: Boolean) {
        val dialog = ConsentDialog(
            context = this,
            onAccepted = {
                if (!PermissionUtils.hasAllPermissions(this)) {
                    requestPermissionsLauncher.launch(PermissionUtils.getRequiredPermissions())
                }
            },
            onDeclined = {
                if (!forceShow) {
                    Toast.makeText(this, "Background recording requires consent to operate.", Toast.LENGTH_SHORT).show()
                }
            }
        )
        dialog.show()
    }

    private fun showPermissionDeniedExplanation() {
        AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage("Camera, Audio, and Notification permissions are required for background recording. Please grant them in App Settings to proceed.")
            .setPositiveButton("Grant") { _, _ ->
                requestPermissionsLauncher.launch(PermissionUtils.getRequiredPermissions())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveCurrentPreferences() {
        val prefs = getSharedPreferences(QuickRecordActivity.PREFS_SETTINGS, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(QuickRecordActivity.KEY_CAMERA_FACING, selectedCameraFacing)
            .putString(QuickRecordActivity.KEY_QUALITY, selectedQuality)
            .putBoolean(QuickRecordActivity.KEY_AUDIO_ENABLED, isAudioEnabled)
            .apply()
    }

    private fun pinQuickRecordShortcut() {
        if (!PermissionUtils.hasAllPermissions(this)) {
            Toast.makeText(this, "Please allow permissions first.", Toast.LENGTH_SHORT).show()
            requestPermissionsLauncher.launch(PermissionUtils.getRequiredPermissions())
            return
        }

        if (ShortcutManagerCompat.isRequestPinShortcutSupported(this)) {
            val pinShortcutIntent = Intent(this, QuickRecordActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            val pinShortcutInfo = ShortcutInfoCompat.Builder(this, "shortcut_quick_record_pinned")
                .setShortLabel("Quick Record")
                .setLongLabel("1-Tap Background Record")
                .setIcon(IconCompat.createWithResource(this, R.drawable.ic_quick_record))
                .setIntent(pinShortcutIntent)
                .build()

            ShortcutManagerCompat.requestPinShortcut(this, pinShortcutInfo, null)
            Toast.makeText(this, "Quick Record shortcut requested for Home Screen!", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(
                this,
                "Dedicated 'Quick Record' icon is available in your app drawer or by long-pressing the app icon.",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
