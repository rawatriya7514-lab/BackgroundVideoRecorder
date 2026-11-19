package com.antigravity.bvr.service

import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.antigravity.bvr.R
import com.antigravity.bvr.ui.QuickRecordActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@RequiresApi(Build.VERSION_CODES.N)
class QuickRecordTileService : TileService() {

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
        scope.launch {
            BackgroundRecordingService.recordingState.collect {
                updateTileState()
            }
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, QuickRecordActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = android.app.PendingIntent.getActivity(
                this,
                0,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val isRecording = BackgroundRecordingService.recordingState.value.isRecording
        if (isRecording) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "REC Active"
            tile.icon = Icon.createWithResource(this, R.drawable.ic_record_stop)
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "Quick Record"
            tile.icon = Icon.createWithResource(this, R.drawable.ic_quick_record)
        }
        tile.updateTile()
    }
}
