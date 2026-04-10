package com.vidremover.presentation.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vidremover.data.repository.VideoRepository
import com.vidremover.domain.model.DuplicateGroup
import com.vidremover.domain.model.ScanProgress
import com.vidremover.domain.model.Video
import com.vidremover.domain.model.VideoFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

class VideoViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = VideoRepository(application)

    private val _videos = MutableStateFlow<List<Video>>(emptyList())
    val videos: StateFlow<List<Video>> = _videos.asStateFlow()

    private val _folders = MutableStateFlow<List<VideoFolder>>(emptyList())
    val folders: StateFlow<List<VideoFolder>> = _folders.asStateFlow()

    private val _duplicateGroups = MutableStateFlow<List<DuplicateGroup>>(emptyList())
    val duplicateGroups: StateFlow<List<DuplicateGroup>> = _duplicateGroups.asStateFlow()

    private val _scanProgress = MutableStateFlow(ScanProgress(0, 0, "", false))
    val scanProgress: StateFlow<ScanProgress> = _scanProgress.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _selectedFolders = MutableStateFlow<Set<String>>(emptySet())
    val selectedFolders: StateFlow<Set<String>> = _selectedFolders.asStateFlow()

    private val _scanAll = MutableStateFlow(true)
    val scanAll: StateFlow<Boolean> = _scanAll.asStateFlow()

    fun loadFolders() {
        viewModelScope.launch {
            _folders.value = repository.getFolders()
        }
    }

    fun toggleFolderSelection(folderPath: String) {
        val current = _selectedFolders.value.toMutableSet()
        if (current.contains(folderPath)) {
            current.remove(folderPath)
        } else {
            current.add(folderPath)
        }
        _selectedFolders.value = current
    }

    fun setScanAll(scanAll: Boolean) {
        _scanAll.value = scanAll
    }

    fun startScan() {
        viewModelScope.launch {
            _isScanning.value = true
            _scanProgress.value = ScanProgress(0, 0, "", false)
            _duplicateGroups.value = emptyList()

            val videoList = if (_scanAll.value) {
                repository.getAllVideos()
            } else {
                repository.getVideosFromFolders(_selectedFolders.value.toList())
            }

            _videos.value = videoList
            _scanProgress.value = ScanProgress(0, videoList.size, "Starting scan...", false)

            val duplicates = findDuplicates(videoList) { current, total, file ->
                _scanProgress.value = ScanProgress(current, total, file, false)
            }

            _duplicateGroups.value = duplicates
            _scanProgress.value = ScanProgress(videoList.size, videoList.size, "Complete!", true)
            _isScanning.value = false
        }
    }

    private suspend fun findDuplicates(
        videos: List<Video>,
        onProgress: (Int, String, String) -> Unit
    ): List<DuplicateGroup> = withContext(Dispatchers.Default) {
        val groups = mutableMapOf<String, MutableList<Video>>()

        videos.forEachIndexed { index, video ->
            onProgress(index, videos.size, video.name)

            val hash = computeVideoHash(video)
            if (!groups.containsKey(hash)) {
                groups[hash] = mutableListOf()
            }
            groups[hash]?.add(video)
        }

        groups.filter { it.value.size > 1 }
            .map { (hash, videoList) ->
                DuplicateGroup(
                    id = hash,
                    videos = videoList.sortedByDescending { it.size },
                    similarity = 1.0f
                )
            }
    }

    private fun computeVideoHash(video: Video): String {
        return try {
            val file = java.io.File(video.path)
            if (!file.exists()) return video.id.toString()

            val digest = MessageDigest.getInstance("MD5")
            
            val fileSize = file.length()
            val sampleSize = minOf(1024 * 1024, fileSize).toInt()
            val sampleStep = maxOf(1, (fileSize / sampleSize).toInt())

            java.io.RandomAccessFile(file, "r").use { raf ->
                var bytesRead = 0L
                val buffer = ByteArray(8192)
                
                while (bytesRead < fileSize && bytesRead < sampleSize * 10) {
                    raf.seek(bytesRead)
                    val read = raf.read(buffer)
                    if (read > 0) {
                        digest.update(buffer, 0, minOf(read, sampleSize - (bytesRead.toInt())))
                    }
                    bytesRead += sampleStep
                }
            }

            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            video.id.toString()
        }
    }

    suspend fun deleteVideo(video: Video): Boolean {
        return repository.deleteVideo(video)
    }

    fun formatSize(size: Long): String = repository.formatFileSize(size)
    fun formatDuration(duration: Long): String = repository.formatDuration(duration)
}