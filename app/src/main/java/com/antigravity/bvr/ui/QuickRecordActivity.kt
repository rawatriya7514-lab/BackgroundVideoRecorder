package com.antigravity.bvr.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.antigravity.bvr.service.BackgroundRecordingService
import com.antigravity.bvr.util.PermissionUtils

/**
 * Ultra-fast 1-Tap Quick Record Activity.
 * When launched via home screen icon, app shortcut, or quick settings tile,
 * it immediately toggles background recording and finishes without disturbing the user.
 */
class QuickRecordActivity : Activity() {

    companion object {
        const val PREFS_SETTINGS = "bvr_settings"
        const val KEY_CAMERA_FACING = "pref_camera_facing"
        const val KEY_QUALITY = "pref_quality"
        const val KEY_AUDIO_ENABLED = "pref_audio_enabled"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Disable activity transition animations
        overridePendingTransition(0, 0)

        // Verify runtime permissions first
        if (!PermissionUtils.hasAllPermissions(this)) {
            Toast.makeText(
                this,
                "Permissions needed. Please allow permissions in Background Recorder.",
                Toast.LENGTH_LONG
            ).show()
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(intent)
            finish()
            overridePendingTransition(0, 0)
            return
        }

        val currentState = BackgroundRecordingService.recordingState.value
        if (currentState.isRecording || currentState.isFinalizing) {
            // Stop Recording
            val stopIntent = Intent(this, BackgroundRecordingService::class.java).apply {
                action = BackgroundRecordingService.ACTION_STOP_RECORDING
            }
            startService(stopIntent)
            vibrateDevice(60)
            Toast.makeText(this, "⏹ Recording Stopped & Saved", Toast.LENGTH_SHORT).show()
        } else {
            // Start Recording with user-configured preferences
            val prefs = getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
            val cameraFacing = prefs.getInt(KEY_CAMERA_FACING, BackgroundRecordingService.CAMERA_BACK)
            val quality = prefs.getString(KEY_QUALITY, "1080p") ?: "1080p"
            val audioEnabled = prefs.getBoolean(KEY_AUDIO_ENABLED, true)

            val startIntent = Intent(this, BackgroundRecordingService::class.java).apply {
                action = BackgroundRecordingService.ACTION_START_RECORDING
                putExtra(BackgroundRecordingService.EXTRA_CAMERA_FACING, cameraFacing)
                putExtra(BackgroundRecordingService.EXTRA_QUALITY, quality)
                putExtra(BackgroundRecordingService.EXTRA_ENABLE_AUDIO, audioEnabled)
            }
            ContextCompat.startForegroundService(this, startIntent)
            vibrateDevice(100)
            Toast.makeText(this, "🔴 Background Recording Started", Toast.LENGTH_SHORT).show()
        }

        finish()
        overridePendingTransition(0, 0)
    }

    private fun vibrateDevice(durationMs: Long) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(durationMs)
                }
            }
        } catch (e: Exception) {
            // Vibration permission optional
        }
    }
}
