package com.antigravity.bvr.data

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VideoRepository(private val context: Context) {

    sealed class DeleteResult {
        object Success : DeleteResult()
        data class RequiresUserConsent(val intentSender: IntentSender) : DeleteResult()
        data class Error(val exception: Throwable) : DeleteResult()
    }

    /**
     * Queries MediaStore for videos saved in Movies/BackgroundRecorder/ or with prefix BVR_
     */
    suspend fun getRecordedVideos(): List<VideoRecording> = withContext(Dispatchers.IO) {
        val recordings = mutableListOf<VideoRecording>()
        val collection: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Video.Media.RELATIVE_PATH else MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_ADDED
        )

        // Query files stored in Movies/BackgroundRecorder or named BVR_
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?"
        } else {
            "${MediaStore.Video.Media.DATA} LIKE ? OR ${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?"
        }
        val selectionArgs = arrayOf("%BackgroundRecorder%", "BVR_%")
        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        try {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
                } else {
                    cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                }
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val name = cursor.getString(nameColumn) ?: "Video_$id"
                    val path = cursor.getString(pathColumn)
                    val size = cursor.getLong(sizeColumn)
                    val duration = cursor.getLong(durationColumn)
                    val dateAdded = cursor.getLong(dateAddedColumn)
                    val contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)

                    recordings.add(
                        VideoRecording(
                            id = id,
                            uri = contentUri,
                            displayName = name,
                            relativePath = path,
                            sizeBytes = size,
                            durationMs = duration,
                            dateAddedSeconds = dateAdded
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        recordings
    }

    /**
     * Deletes a recording via ContentResolver.
     * Handles Scoped Storage on Android 10+ and RecoverableSecurityException on Android 11+.
     */
    suspend fun deleteRecording(video: VideoRecording): DeleteResult = withContext(Dispatchers.IO) {
        try {
            val rowsDeleted = context.contentResolver.delete(video.uri, null, null)
            if (rowsDeleted > 0) {
                DeleteResult.Success
            } else {
                DeleteResult.Error(Exception("Failed to delete item from MediaStore"))
            }
        } catch (securityException: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val recoverableException = securityException as? RecoverableSecurityException
                if (recoverableException != null) {
                    DeleteResult.RequiresUserConsent(recoverableException.userAction.actionIntent.intentSender)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val deleteRequest = MediaStore.createDeleteRequest(context.contentResolver, listOf(video.uri))
                    DeleteResult.RequiresUserConsent(deleteRequest.intentSender)
                } else {
                    DeleteResult.Error(securityException)
                }
            } else {
                DeleteResult.Error(securityException)
            }
        } catch (t: Throwable) {
            DeleteResult.Error(t)
        }
    }
}
