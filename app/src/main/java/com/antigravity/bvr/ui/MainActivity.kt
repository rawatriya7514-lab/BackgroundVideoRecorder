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
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.antigravity.bvr.R
import com.antigravity.bvr.databinding.ActivityMainBinding
import com.antigravity.bvr.service.BackgroundRecordingService
import com.antigravity.bvr.util.FormatUtils
import com.antigravity.bvr.util.PermissionUtils
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var selectedCameraFacing = BackgroundRecordingService.CAMERA_BACK
    private var selectedQuality = "1080p"
    private var isAudioEnabled = true

    private var pulseAnimation: AlphaAnimation? = null

    // Permission launcher for runtime permissions
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[android.Manifest.permission.CAMERA] ?: false
        val audioGranted = permissions[android.Manifest.permission.RECORD_AUDIO] ?: false

        if (cameraGranted && audioGranted) {
            Toast.makeText(this, "Permissions granted. Ready to record.", Toast.LENGTH_SHORT).show()
        } else {
            showPermissionDeniedExplanation()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        setupListeners()
        observeRecordingState()
        checkConsentAndPermissions()
    }

    override fun onResume() {
        super.onResume()
        updateStorageDisplay()
    }

    private fun setupUI() {
        // Load saved preferences
        val prefs = getSharedPreferences(QuickRecordActivity.PREFS_SETTINGS, Context.MODE_PRIVATE)
        selectedCameraFacing = prefs.getInt(QuickRecordActivity.KEY_CAMERA_FACING, BackgroundRecordingService.CAMERA_BACK)
        selectedQuality = prefs.getString(QuickRecordActivity.KEY_QUALITY, "1080p") ?: "1080p"
        isAudioEnabled = prefs.getBoolean(QuickRecordActivity.KEY_AUDIO_ENABLED, true)

        binding.chipCameraBack.isChecked = (selectedCameraFacing == BackgroundRecordingService.CAMERA_BACK)
        binding.chipCameraFront.isChecked = (selectedCameraFacing == BackgroundRecordingService.CAMERA_FRONT)
        binding.chipQuality1080p.isChecked = (selectedQuality == "1080p")
        binding.chipQuality720p.isChecked = (selectedQuality == "720p")
        binding.switchAudio.isChecked = isAudioEnabled

        // Pulse animation for recording state
        pulseAnimation = AlphaAnimation(0.4f, 1.0f).apply {
            duration = 750
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
        }

        updateStorageDisplay()
    }

    private fun setupListeners() {
        // Start / Stop toggle button
        binding.btnToggleRecord.setOnClickListener {
            handleRecordToggleClick()
        }

        // Camera facing selector chips
        binding.chipCameraBack.setOnClickListener {
            selectedCameraFacing = BackgroundRecordingService.CAMERA_BACK
            binding.chipCameraBack.isChecked = true
            binding.chipCameraFront.isChecked = false
            saveCurrentPreferences()
        }
        binding.chipCameraFront.setOnClickListener {
            selectedCameraFacing = BackgroundRecordingService.CAMERA_FRONT
            binding.chipCameraFront.isChecked = true
            binding.chipCameraBack.isChecked = false
            saveCurrentPreferences()
        }

        // Quality selector chips
        binding.chipQuality1080p.setOnClickListener {
            selectedQuality = "1080p"
            binding.chipQuality1080p.isChecked = true
            binding.chipQuality720p.isChecked = false
            saveCurrentPreferences()
        }
        binding.chipQuality720p.setOnClickListener {
            selectedQuality = "720p"
            binding.chipQuality720p.isChecked = true
            binding.chipQuality1080p.isChecked = false
            saveCurrentPreferences()
        }

        // Audio toggle
        binding.switchAudio.setOnCheckedChangeListener { _, isChecked ->
            isAudioEnabled = isChecked
            saveCurrentPreferences()
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

    private fun handleRecordToggleClick() {
        if (!PermissionUtils.hasAllPermissions(this)) {
            requestPermissionsLauncher.launch(PermissionUtils.getRequiredPermissions())
            return
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

    private fun updateUIState(state: BackgroundRecordingService.ServiceState) {
        if (state.isRecording) {
            // Active Recording UI
            binding.tvStatusBadge.text = "REC"
            binding.tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_recording)
            binding.tvTimer.text = FormatUtils.formatDurationSeconds(state.durationSeconds)

            binding.btnToggleRecord.text = "STOP RECORDING"
            binding.btnToggleRecord.setBackgroundColor(ContextCompat.getColor(this, R.color.record_stop_red))
            binding.btnToggleRecord.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_record_stop, 0, 0, 0)

            // Start pulse on recording badge
            if (binding.tvStatusBadge.animation == null) {
                binding.tvStatusBadge.startAnimation(pulseAnimation)
            }

            // Disable configuration adjustments while recording
            binding.chipCameraBack.isEnabled = false
            binding.chipCameraFront.isEnabled = false
            binding.chipQuality1080p.isEnabled = false
            binding.chipQuality720p.isEnabled = false
            binding.switchAudio.isEnabled = false
            binding.btnPinShortcut.isEnabled = false
            binding.tvScreenOffNote.visibility = View.VISIBLE
        } else if (state.isFinalizing) {
            // Finalizing / Saving state
            binding.tvStatusBadge.text = "SAVING"
            binding.tvStatusBadge.clearAnimation()
            binding.tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_idle)
            binding.btnToggleRecord.text = "FINALIZING..."
            binding.btnToggleRecord.isEnabled = false
        } else {
            // Idle UI
            binding.tvStatusBadge.text = "IDLE"
            binding.tvStatusBadge.clearAnimation()
            binding.tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_idle)
            binding.tvTimer.text = "00:00:00"

            binding.btnToggleRecord.text = "START RECORDING"
            binding.btnToggleRecord.isEnabled = true
            binding.btnToggleRecord.setBackgroundColor(ContextCompat.getColor(this, R.color.record_start_green))
            binding.btnToggleRecord.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_record_start, 0, 0, 0)

            binding.chipCameraBack.isEnabled = true
            binding.chipCameraFront.isEnabled = true
            binding.chipQuality1080p.isEnabled = true
            binding.chipQuality720p.isEnabled = true
            binding.switchAudio.isEnabled = true
            binding.btnPinShortcut.isEnabled = true
            binding.tvScreenOffNote.visibility = View.GONE

            state.lastSavedUri?.let {
                Toast.makeText(this, "Recording saved to Movies/BackgroundRecorder", Toast.LENGTH_LONG).show()
                updateStorageDisplay()
            }

            state.errorMessage?.let { error ->
                Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateStorageDisplay() {
        binding.tvStorageInfo.text = "Storage: ${FormatUtils.getAvailableStorageSpace()}"
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
