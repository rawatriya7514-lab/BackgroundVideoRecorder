package com.antigravity.bvr.util

import android.os.Environment
import android.os.StatFs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FormatUtils {

    /**
     * Formats duration in seconds or milliseconds to HH:MM:SS or MM:SS string.
     */
    fun formatDurationSeconds(totalSeconds: Long): String {
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    fun formatDurationMillis(millis: Long): String {
        return formatDurationSeconds(millis / 1000)
    }

    /**
     * Formats bytes to human-readable size (KB, MB, GB).
     */
    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 MB"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.getDefault(), "%.2f GB", gb)
            mb >= 1.0 -> String.format(Locale.getDefault(), "%.1f MB", mb)
            else -> String.format(Locale.getDefault(), "%.0f KB", kb)
        }
    }

    /**
     * Formats epoch timestamp to readable date string.
     */
    fun formatDate(timestampMs: Long): String {
        val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
        return sdf.format(Date(timestampMs))
    }

    /**
     * Returns remaining available storage space on device as human-readable string.
     */
    fun getAvailableStorageSpace(): String {
        return try {
            val stat = StatFs(Environment.getExternalStorageDirectory().path)
            val bytesAvailable = stat.availableBlocksLong * stat.blockSizeLong
            formatFileSize(bytesAvailable) + " free"
        } catch (e: Exception) {
            "Storage OK"
        }
    }
}
