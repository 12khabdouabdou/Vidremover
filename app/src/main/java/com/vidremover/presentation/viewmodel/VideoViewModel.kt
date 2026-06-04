package com.vidremover.presentation.viewmodel

import android.content.Context
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
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val repository: MediaRepository,
    private val computeMD5HashUseCase: ComputeMD5HashUseCase,
    private val computePHashUseCase: ComputePHashUseCase,
    private val duplicateStateHolder: DuplicateStateHolder
) : ViewModel() {

    private val _videos = MutableStateFlow<List<Video>>(emptyList())
    val videos: StateFlow<List<Video>> = _videos.asStateFlow()

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
        videos: List<Video>,
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
        videos: List<Video>,
        onProgress: (Int, Int, String) -> Unit
    ): List<DuplicateGroup> = withContext(Dispatchers.Default) {
        val groups = mutableMapOf<String, MutableList<Video>>()

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
        videos: List<Video>,
        onProgress: (Int, Int, String) -> Unit
    ): List<DuplicateGroup> = withContext(Dispatchers.Default) {
        val hashes = mutableListOf<Pair<Video, String>>()

        videos.forEachIndexed { index, video ->
            onProgress(index, videos.size, video.name)
            try {
                val hash = computePHashUseCase(video)
                hashes.add(video to hash)
            } catch (e: Exception) {
            }
        }

        val visited = BooleanArray(hashes.size)
        val groups = mutableListOf<DuplicateGroup>()
        val threshold = _pHashThreshold.value

        for (i in hashes.indices) {
            if (visited[i]) continue
            visited[i] = true

            val (video1, hash1) = hashes[i]
            val currentGroup = mutableListOf(video1)

            for (j in i + 1 until hashes.size) {
                if (visited[j]) continue

                val (video2, hash2) = hashes[j]
                if (computePHashUseCase.compareHashes(hash1, hash2) >= threshold) {
                    currentGroup.add(video2)
                    visited[j] = true
                }
            }

            if (currentGroup.size > 1) {
                groups.add(
                    DuplicateGroup(
                        id = "phash_${hash1.take(10)}_${System.currentTimeMillis()}",
                        videos = currentGroup.sortedByDescending { it.size },
                        similarity = threshold
                    )
                )
            }
        }

        groups
    }

    private suspend fun findVideoBothDuplicates(
        videos: List<Video>,
        onProgress: (Int, Int, String) -> Unit
    ): List<DuplicateGroup> = withContext(Dispatchers.Default) {
        val md5Groups = findVideoMD5Duplicates(videos, onProgress)
        val videosInMD5 = md5Groups.flatMap { it.videos }.map { it.id }.toSet()
        val remainingVideos = videos.filter { it.id !in videosInMD5 }
        
        val pHashGroups = if (remainingVideos.isNotEmpty()) {
            findVideopHashDuplicates(remainingVideos, onProgress)
        } else emptyList()
        
        (md5Groups + pHashGroups).sortedByDescending { it.videos.size }
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
        val groups = mutableMapOf<String, MutableList<Image>>()

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
        val hashes = mutableListOf<Pair<Image, String>>()

        images.forEachIndexed { index, image ->
            onProgress(index, images.size, image.name)
            try {
                val hash = computeImagePHash(image)
                hashes.add(image to hash)
            } catch (e: Exception) {
            }
        }

        val visited = BooleanArray(hashes.size)
        val groups = mutableListOf<DuplicateGroup>()
        val threshold = _pHashThreshold.value

        for (i in hashes.indices) {
            if (visited[i]) continue
            visited[i] = true

            val (image1, hash1) = hashes[i]
            val currentGroup = mutableListOf(image1)

            for (j in i + 1 until hashes.size) {
                if (visited[j]) continue

                val (image2, hash2) = hashes[j]
                if (computePHashUseCase.compareHashes(hash1, hash2) >= threshold) {
                    currentGroup.add(image2)
                    visited[j] = true
                }
            }

            if (currentGroup.size > 1) {
                groups.add(
                    DuplicateGroup(
                        id = "phash_${hash1.take(10)}_${System.currentTimeMillis()}",
                        videos = currentGroup.sortedByDescending { it.size },
                        similarity = threshold
                    )
                )
            }
        }

        groups
    }

    private suspend fun findImageBothDuplicates(
        images: List<Image>,
        onProgress: (Int, Int, String) -> Unit
    ): List<DuplicateGroup> = withContext(Dispatchers.Default) {
        val md5Groups = findImageMD5Duplicates(images, onProgress)
        val imagesInMD5 = md5Groups.flatMap { it.videos }.map { it.id }.toSet()
        val remainingImages = images.filter { it.id !in imagesInMD5 }
        
        val pHashGroups = if (remainingImages.isNotEmpty()) {
            findImagepHashDuplicates(remainingImages, onProgress)
        } else emptyList()
        
        (md5Groups + pHashGroups).sortedByDescending { it.videos.size }
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
            digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
        } catch (e: Exception) {
            image.id.toString()
        }
    }

    private fun computeImagePHash(image: Image): String {
        return try {
            computePHashUseCase.computeImagePHash(context, image.uri) ?: image.id.toString()
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
