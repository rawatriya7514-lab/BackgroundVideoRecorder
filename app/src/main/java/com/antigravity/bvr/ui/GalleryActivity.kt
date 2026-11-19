package com.antigravity.bvr.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.antigravity.bvr.data.VideoRecording
import com.antigravity.bvr.data.VideoRepository
import com.antigravity.bvr.databinding.ActivityGalleryBinding
import kotlinx.coroutines.launch

class GalleryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGalleryBinding
    private lateinit var repository: VideoRepository
    private lateinit var adapter: VideoAdapter

    private var pendingDeleteVideo: VideoRecording? = null
    private lateinit var intentSenderLauncher: ActivityResultLauncher<IntentSenderRequest>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            binding.toolbarGallery.setPadding(0, statusBars.top, 0, 0)
            binding.rvRecordings.setPadding(
                binding.rvRecordings.paddingLeft,
                binding.rvRecordings.paddingTop,
                binding.rvRecordings.paddingRight,
                navBars.bottom + 16
            )
            insets
        }

        repository = VideoRepository(this)

        // Setup delete permission request launcher (Scoped storage / Android 11+)
        intentSenderLauncher = registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                Toast.makeText(this, "Recording deleted", Toast.LENGTH_SHORT).show()
                loadVideos()
            }
        }

        setupToolbar()
        setupRecyclerView()
        setupListeners()
        loadVideos()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbarGallery)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        binding.toolbarGallery.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupRecyclerView() {
        adapter = VideoAdapter(
            scope = lifecycleScope,
            onPlayClick = { video -> playVideo(video) },
            onShareClick = { video -> shareVideo(video) },
            onDeleteClick = { video -> confirmDelete(video) }
        )
        binding.rvRecordings.layoutManager = LinearLayoutManager(this)
        binding.rvRecordings.adapter = adapter
    }

    private fun setupListeners() {
        binding.swipeRefreshLayout.setOnRefreshListener {
            loadVideos()
        }
    }

    private fun loadVideos() {
        binding.swipeRefreshLayout.isRefreshing = true
        lifecycleScope.launch {
            val list = repository.getRecordedVideos()
            adapter.submitList(list)
            binding.swipeRefreshLayout.isRefreshing = false

            if (list.isEmpty()) {
                binding.tvEmptyState.visibility = View.VISIBLE
                binding.rvRecordings.visibility = View.GONE
            } else {
                binding.tvEmptyState.visibility = View.GONE
                binding.rvRecordings.visibility = View.VISIBLE
            }
            binding.toolbarGallery.subtitle = "${list.size} recordings"
        }
    }

    private fun playVideo(video: VideoRecording) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(video.uri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Play Recording"))
        } catch (e: Exception) {
            Toast.makeText(this, "No video player available: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareVideo(video: VideoRecording) {
        try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, video.uri)
                putExtra(Intent.EXTRA_SUBJECT, video.displayName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Share Recording"))
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to share: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(video: VideoRecording) {
        AlertDialog.Builder(this)
            .setTitle("Delete Recording")
            .setMessage("Are you sure you want to permanently delete \"${video.displayName}\"?")
            .setPositiveButton("Delete") { _, _ ->
                performDelete(video)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performDelete(video: VideoRecording) {
        lifecycleScope.launch {
            when (val result = repository.deleteRecording(video)) {
                is VideoRepository.DeleteResult.Success -> {
                    Toast.makeText(this@GalleryActivity, "Recording deleted", Toast.LENGTH_SHORT).show()
                    loadVideos()
                }
                is VideoRepository.DeleteResult.RequiresUserConsent -> {
                    pendingDeleteVideo = video
                    val request = IntentSenderRequest.Builder(result.intentSender).build()
                    intentSenderLauncher.launch(request)
                }
                is VideoRepository.DeleteResult.Error -> {
                    Toast.makeText(
                        this@GalleryActivity,
                        "Failed to delete: ${result.exception.localizedMessage}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}
