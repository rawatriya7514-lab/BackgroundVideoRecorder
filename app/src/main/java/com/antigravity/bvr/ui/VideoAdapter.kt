package com.antigravity.bvr.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.antigravity.bvr.R
import com.antigravity.bvr.data.VideoRecording
import com.antigravity.bvr.util.FormatUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VideoAdapter(
    private val scope: CoroutineScope,
    private val onPlayClick: (VideoRecording) -> Unit,
    private val onShareClick: (VideoRecording) -> Unit,
    private val onDeleteClick: (VideoRecording) -> Unit
) : ListAdapter<VideoRecording, VideoAdapter.VideoViewHolder>(DiffCallback) {

    object DiffCallback : DiffUtil.ItemCallback<VideoRecording>() {
        override fun areItemsTheSame(oldItem: VideoRecording, newItem: VideoRecording): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: VideoRecording, newItem: VideoRecording): Boolean {
            return oldItem == newItem
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_video_record, parent, false)
        return VideoViewHolder(view)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VideoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivThumbnail: ImageView = itemView.findViewById(R.id.iv_video_thumbnail)
        private val tvTitle: TextView = itemView.findViewById(R.id.tv_video_title)
        private val tvDuration: TextView = itemView.findViewById(R.id.tv_video_duration)
        private val tvMeta: TextView = itemView.findViewById(R.id.tv_video_meta)
        private val btnPlay: View = itemView.findViewById(R.id.btn_play_video)
        private val btnShare: View = itemView.findViewById(R.id.btn_share_video)
        private val btnDelete: View = itemView.findViewById(R.id.btn_delete_video)

        fun bind(video: VideoRecording) {
            tvTitle.text = video.displayName
            tvDuration.text = FormatUtils.formatDurationMillis(video.durationMs)
            tvMeta.text = "${FormatUtils.formatDate(video.dateAddedSeconds * 1000)} • ${FormatUtils.formatFileSize(video.sizeBytes)}"

            // Reset image placeholder
            ivThumbnail.setImageResource(R.drawable.ic_play)

            // Async load video thumbnail
            loadThumbnailAsync(itemView.context, video, ivThumbnail)

            btnPlay.setOnClickListener { onPlayClick(video) }
            itemView.setOnClickListener { onPlayClick(video) }
            btnShare.setOnClickListener { onShareClick(video) }
            btnDelete.setOnClickListener { onDeleteClick(video) }
        }

        private fun loadThumbnailAsync(context: Context, video: VideoRecording, imageView: ImageView) {
            scope.launch {
                val bitmap: Bitmap? = withContext(Dispatchers.IO) {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            context.contentResolver.loadThumbnail(
                                video.uri,
                                Size(320, 240),
                                null
                            )
                        } else {
                            null
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap)
                    imageView.scaleType = ImageView.ScaleType.CENTER_CROP
                }
            }
        }
    }
}
