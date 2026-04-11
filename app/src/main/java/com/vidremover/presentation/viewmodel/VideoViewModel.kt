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

enum class DetectionMode {
    MD5_ONLY,
    PHASH_ONLY,
    BOTH
}

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

    private val _detectionMode = MutableStateFlow(DetectionMode.MD5_ONLY)
    val detectionMode: StateFlow<DetectionMode> = _detectionMode.asStateFlow()

    private val _pHashThreshold = MutableStateFlow(0.9f)
    val pHashThreshold: StateFlow<Float> = _pHashThreshold.asStateFlow()

    private val _selectedVideos = MutableStateFlow<Set<Long>>(emptySet())
    val selectedVideos: StateFlow<Set<Long>> = _selectedVideos.asStateFlow()

    private val _freedSpace = MutableStateFlow(0L)
    val freedSpace: StateFlow<Long> = _freedSpace.asStateFlow()

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

            val duplicates = findDuplicates(videoList, _detectionMode.value) { current, total, file ->
                _scanProgress.value = ScanProgress(current, total, file, false)
            }

            _duplicateGroups.value = duplicates
            _scanProgress.value = ScanProgress(videoList.size, videoList.size, "Complete!", true)
            _isScanning.value = false
        }
    }

    fun setDetectionMode(mode: com.vidremover.domain.usecase.DetectionMode) {
        _detectionMode.value = mode
    }

    fun setpHashThreshold(threshold: Float) {
        _pHashThreshold.value = threshold
    }

    fun toggleVideoSelection(videoId: Long) {
        val current = _selectedVideos.value.toMutableSet()
        if (current.contains(videoId)) {
            current.remove(videoId)
        } else {
            current.add(videoId)
        }
        _selectedVideos.value = current
    }

    fun selectAllDuplicates() {
        val allDuplicateIds = _duplicateGroups.value.flatMap { it.videos.map { v -> v.id } }.toSet()
        _selectedVideos.value = allDuplicateIds
    }

    fun clearSelection() {
        _selectedVideos.value = emptySet()
    }

    private suspend fun findDuplicates(
        videos: List<Video>,
        mode: DetectionMode,
        onProgress: (Int, String, String) -> Unit
    ): List<DuplicateGroup> = withContext(Dispatchers.Default) {
        when (mode) {
            DetectionMode.MD5_ONLY -> findMD5Duplicates(videos, onProgress)
            DetectionMode.PHASH_ONLY -> findpHashDuplicates(videos, onProgress)
            DetectionMode.BOTH -> findBothDuplicates(videos, onProgress)
        }
    }

    private suspend fun findMD5Duplicates(
        videos: List<Video>,
        onProgress: (Int, String, String) -> Unit
    ): List<DuplicateGroup> = withContext(Dispatchers.Default) {
        val groups = mutableMapOf<String, MutableList<Video>>()

        videos.forEachIndexed { index, video ->
            onProgress(index, videos.size, video.name)

            try {
                val hash = repository.computeMD5Hash(video)
                groups.getOrPut(hash) { mutableListOf() }.add(video)
            } catch (e: Exception) {
                // Skip unhashable videos
            }
        }

        groups.filter { it.value.size > 1 }
            .map { (hash, videoList) ->
                DuplicateGroup(
                    id = "md5_$hash",
                    videos = videoList.sortedByDescending { it.size },
                    similarity = 1.0f
                )
            }
    }

    private suspend fun findpHashDuplicates(
        videos: List<Video>,
        onProgress: (Int, String, String) -> Unit
    ): List<DuplicateGroup> = withContext(Dispatchers.Default) {
        val groups = mutableMapOf<String, MutableList<Video>>()

        videos.forEachIndexed { index, video ->
            onProgress(index, videos.size, video.name)

            try {
                val hash = repository.computepHash(video)
                groups.getOrPut(hash) { mutableListOf() }.add(video)
            } catch (e: Exception) {
                // Skip unhashable videos
            }
        }

        groups.filter { it.value.size > 1 }
            .map { (hash, videoList) ->
                DuplicateGroup(
                    id = "phash_$hash",
                    videos = videoList.sortedByDescending { it.size },
                    similarity = _pHashThreshold.value
                )
            }
    }

    private suspend fun findBothDuplicates(
        videos: List<Video>,
        onProgress: (Int, String, String) -> Unit
    ): List<DuplicateGroup> = withContext(Dispatchers.Default) {
        // First find MD5 duplicates
        val md5Groups = findMD5Duplicates(videos, onProgress).associateBy { it.id }
        
        // Then find pHash duplicates
        val pHashGroups = findpHashDuplicates(videos, onProgress).associateBy { it.id }
        
        // Combine groups
        val allGroups = (md5Groups.values + pHashGroups.values)
            .flatMap { it.videos }
            .groupBy { it.id }
            .map { (_, videoList) ->
                val bestSimilarity = if (md5Groups.containsKey("md5_${videoList.firstOrNull()?.id}")) 1.0f else _pHashThreshold.value
                DuplicateGroup(
                    id = videoList.first().id.toString(),
                    videos = videoList.distinctBy { it.id }.sortedByDescending { it.size },
                    similarity = bestSimilarity
                )
            }
            .filter { it.videos.size > 1 }
        
        allGroups
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
        val success = repository.deleteVideo(video)
        if (success) {
            _freedSpace.value += video.size
        }
        return success
    }

    suspend fun deleteSelectedVideos(): Int {
        var deletedCount = 0
        val videosToDelete = _videos.value.filter { _selectedVideos.value.contains(it.id) }
        
        videosToDelete.forEach { video ->
            val success = repository.deleteVideo(video)
            if (success) {
                deletedCount++
                _freedSpace.value += video.size
            }
        }
        
        _selectedVideos.value = emptySet()
        return deletedCount
    }

    fun formatSize(size: Long): String = repository.formatFileSize(size)
    fun formatDuration(duration: Long): String = repository.formatDuration(duration)
}