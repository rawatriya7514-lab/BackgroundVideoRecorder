package com.antigravity.bvr.data

import android.net.Uri

data class VideoRecording(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val relativePath: String?,
    val sizeBytes: Long,
    val durationMs: Long,
    val dateAddedSeconds: Long
)
