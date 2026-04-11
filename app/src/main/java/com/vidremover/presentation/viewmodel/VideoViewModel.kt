package com.vidremover.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vidremover.domain.model.DuplicateGroup
import com.vidremover.domain.model.Image
import com.vidremover.domain.model.ScanProgress
import com.vidremover.domain.model.Video
import com.vidremover.domain.model.VideoFolder
import com.vidremover.domain.repository.MediaRepository
import com.vidremover.domain.usecase.ComputeMD5HashUseCase
import com.vidremover.domain.usecase.ComputePHashUseCase
import com.vidremover.domain.usecase.DetectionMode
import com.vidremover.domain.usecase.MediaType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.inject.Inject

@HiltViewModel
class VideoViewModel @Inject constructor(
    private val repository: MediaRepository,
    private val computeMD5HashUseCase: ComputeMD5HashUseCase,
    private val computePHashUseCase: ComputePHashUseCase,
    private val duplicateStateHolder: DuplicateStateHolder
) : ViewModel() {

    private val _videos = MutableStateFlow<List >(emptyList())
    val videos: StateFlow<List > = _videos.asStateFlow()

    private val _images = MutableStateFlow<List<Image>>(emptyList())
    val images: StateFlow<List<Image>> = _images.asStateFlow()

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

    val detectionMode: StateFlow<DetectionMode> = duplicateStateHolder.detectionMode
    val mediaType: StateFlow<MediaType> = duplicateStateHolder.mediaType

    private val _pHashThreshold = MutableStateFlow(0.9f)

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

            when (mediaType.value) {
                MediaType.VIDEOS -> {
                    val videoList = if (_scanAll.value) {
                        repository.getAllVideos()
                    } else {
                        repository.getVideosFromFolders(_selectedFolders.value.toList())
                    }
                    _videos.value = videoList
                    _scanProgress.value = ScanProgress(0, videoList.size, "Starting scan...", false)
                    val duplicates = findVideoDuplicates(videoList, detectionMode.value) { current, total, file ->
                        _scanProgress.value = ScanProgress(current, total, file, false)
                    }
                    _duplicateGroups.value = duplicates
                    duplicateStateHolder.setDuplicateGroups(duplicates)
                    _scanProgress.value = ScanProgress(videoList.size, videoList.size, "Complete!", true)
                }
                MediaType.IMAGES -> {
                    val imageList = if (_scanAll.value) {
                        repository.getAllImages()
                    } else {
                        repository.getImagesFromFolders(_selectedFolders.value.toList())
                    }
                    _images.value = imageList
                    _scanProgress.value = ScanProgress(0, imageList.size, "Starting scan...", false)
                    val duplicates = findImageDuplicates(imageList, detectionMode.value) { current, total, file ->
                        _scanProgress.value = ScanProgress(current, total, file, false)
                    }
                    _duplicateGroups.value = duplicates
                    duplicateStateHolder.setDuplicateGroups(duplicates)
                    _scanProgress.value = ScanProgress(imageList.size, imageList.size, "Complete!", true)
                }
            }
            _isScanning.value = false
        }
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

    private suspend fun findVideoDuplicates(
        videos: List ,
        mode: DetectionMode,
        onProgress: (Int, Int, String) -> Unit
    ): List<DuplicateGroup> = withContext(Dispatchers.Default) {
        when (mode) {
            DetectionMode.MD5_ONLY -> findVideoMD5Duplicates(videos, onProgress)
            DetectionMode.PHASH_ONLY -> findVideopHashDuplicates(videos, onProgress)
            DetectionMode.BOTH -> findVideoBothDuplicates(videos, onProgress)
        }
    }

    private suspend fun findVideoMD5Duplicates(
        videos: List ,
        onProgress: (Int, Int, String) -> Unit
    ): List<DuplicateGroup> = withContext(Dispatchers.Default) {
        val groups = mutableMapOf<String, MutableList >()

        videos.forEachIndexed { index, video ->
            onProgress(index, videos.size, video.name)
            try {
                val hash = repository.computeMD5Hash(video)
                groups.getOrPut(hash) { mutableListOf() }.add(video)
            } catch (e: Exception) {
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

    private suspend fun findVideopHashDuplicates(
        videos: List ,
        onProgress: (Int, Int, String) -> Unit
    ): List<DuplicateGroup> = withContext(Dispatchers.Default) {
        val groups = mutableMapOf<String, MutableList >()

        videos.forEachIndexed { index, video ->
            onProgress(index, videos.size, video.name)
            try {
                val hash = computePHashUseCase(video)
                groups.getOrPut(hash) { mutableListOf() }.add(video)
            } catch (e: Exception) {
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

    private suspend fun findVideoBothDuplicates(
        videos: List ,
        onProgress: (Int, Int, String) -> Unit
    ): List<DuplicateGroup> = withContext(Dispatchers.Default) {
        val md5Groups = findVideoMD5Duplicates(videos, onProgress).associateBy { it.id }
        val pHashGroups = findVideopHashDuplicates(videos, onProgress).associateBy { it.id }

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

    private suspend fun findImageDuplicates(
        images: List<Image>,
        mode: DetectionMode,
        onProgress: (Int, Int, String) -> Unit
    ): List<DuplicateGroup> = withContext(Dispatchers.Default) {
        when (mode) {
            DetectionMode.MD5_ONLY -> findImageMD5Duplicates(images, onProgress)
            DetectionMode.PHASH_ONLY -> findImagepHashDuplicates(images, onProgress)
            DetectionMode.BOTH -> findImageBothDuplicates(images, onProgress)
        }
    }

    private suspend fun findImageMD5Duplicates(
        images: List<Image>,
        onProgress: (Int, Int, String) -> Unit
    ): List<DuplicateGroup> = withContext(Dispatchers.Default) {
        val groups = mutableMapOf<String, MutableList >()

        images.forEachIndexed { index, image ->
            onProgress(index, images.size, image.name)
            try {
                val hash = computeImageMD5Hash(image)
                groups.getOrPut(hash) { mutableListOf() }.add(image)
            } catch (e: Exception) {
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

    private suspend fun findImagepHashDuplicates(
        images: List<Image>,
        onProgress: (Int, Int, String) -> Unit
    ): List<DuplicateGroup> = withContext(Dispatchers.Default) {
        val groups = mutableMapOf<String, MutableList >()

        images.forEachIndexed { index, image ->
            onProgress(index, images.size, image.name)
            try {
                val hash = computeImagePHash(image)
                groups.getOrPut(hash) { mutableListOf() }.add(image)
            } catch (e: Exception) {
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

    private suspend fun findImageBothDuplicates(
        images: List<Image>,
        onProgress: (Int, Int, String) -> Unit
    ): List<DuplicateGroup> = withContext(Dispatchers.Default) {
        val md5Groups = findImageMD5Duplicates(images, onProgress).associateBy { it.id }
        val pHashGroups = findImagepHashDuplicates(images, onProgress).associateBy { it.id }

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

    private fun computeImageMD5Hash(image: Image): String {
        return try {
            val file = java.io.File(image.path)
            if (!file.exists()) return image.id.toString()

            val digest = MessageDigest.getInstance("MD5")
            java.io.FileInputStream(file).use { fis ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            digest.digest().joinToString("") { byte -> "x02x".format(byte) }
        } catch (e: Exception) {
            image.id.toString()
        }
    }

    private fun computeImagePHash(image: Image): String {
        return try {
            val file = java.io.File(image.path)
            if (!file.exists()) return image.id.toString()
            image.path.hashCode().toString(16)
        } catch (e: Exception) {
            image.id.toString()
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
