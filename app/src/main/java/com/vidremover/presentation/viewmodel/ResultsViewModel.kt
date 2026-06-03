package com.vidremover.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vidremover.domain.model.DuplicateGroup
import com.vidremover.domain.model.Video
import com.vidremover.domain.repository.VideoRepository
import com.vidremover.domain.usecase.DeleteVideosUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ResultsViewModel @Inject constructor(
    private val repository: VideoRepository,
    private val deleteVideosUseCase: DeleteVideosUseCase,
    private val duplicateStateHolder: DuplicateStateHolder
) : ViewModel() {

    val duplicateGroups: StateFlow<List<DuplicateGroup>> = duplicateStateHolder.duplicateGroups

    private val _selectedVideoIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedVideoIds: StateFlow<Set<Long>> = _selectedVideoIds.asStateFlow()

    private val _expandedGroups = MutableStateFlow<Set<String>>(emptySet())
    val expandedGroups: StateFlow<Set<String>> = _expandedGroups.asStateFlow()

    private val _isDeleting = MutableStateFlow(false)
    val isDeleting: StateFlow<Boolean> = _isDeleting.asStateFlow()

    private val _deletedCount = MutableStateFlow(0)
    val deletedCount: StateFlow<Int> = _deletedCount.asStateFlow()

    private val _freedSpace = MutableStateFlow(0L)
    val freedSpace: StateFlow<Long> = _freedSpace.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        val groups = duplicateStateHolder.duplicateGroups.value
        if (groups.isNotEmpty()) {
            _expandedGroups.value = setOf(groups.first().id)
        }
    }

    fun toggleGroupExpansion(groupId: String) {
        val current = _expandedGroups.value.toMutableSet()
        if (current.contains(groupId)) {
            current.remove(groupId)
        } else {
            current.add(groupId)
        }
        _expandedGroups.value = current
    }

    fun expandAllGroups() {
        _expandedGroups.value = duplicateGroups.value.map { it.id }.toSet()
    }

    fun collapseAllGroups() {
        _expandedGroups.value = emptySet()
    }

    fun toggleVideoSelection(videoId: Long) {
        val current = _selectedVideoIds.value.toMutableSet()
        if (current.contains(videoId)) {
            current.remove(videoId)
        } else {
            current.add(videoId)
        }
        _selectedVideoIds.value = current
    }

    fun selectAllInGroup(groupId: String) {
        val group = duplicateGroups.value.find { it.id == groupId }
        group?.let {
            val current = _selectedVideoIds.value.toMutableSet()
            val videosToSelect = it.videos.drop(1).map { video -> video.id }
            current.addAll(videosToSelect)
            _selectedVideoIds.value = current
        }
    }

    fun deselectAllInGroup(groupId: String) {
        val group = duplicateGroups.value.find { it.id == groupId }
        group?.let {
            val current = _selectedVideoIds.value.toMutableSet()
            it.videos.forEach { video ->
                current.remove(video.id)
            }
            _selectedVideoIds.value = current
        }
    }

    fun selectAllDuplicates() {
        val allDuplicates = duplicateGroups.value
            .flatMap { it.videos.drop(1) }
            .map { it.id }
            .toSet()
        _selectedVideoIds.value = allDuplicates
    }

    fun clearSelection() {
        _selectedVideoIds.value = emptySet()
    }

    fun calculateSelectedSize(): Long {
        return duplicateGroups.value
            .flatMap { it.videos }
            .filter { _selectedVideoIds.value.contains(it.id) }
            .sumOf { it.size }
    }

    fun getSelectedCount(): Int = _selectedVideoIds.value.size

    fun getTotalDuplicateCount(): Int {
        return duplicateGroups.value.sumOf { it.videos.size - 1 }
    }

    fun getTotalPotentialSavings(): Long {
        return duplicateGroups.value.sumOf { group ->
            group.videos.drop(1).sumOf { it.size }
        }
    }

    suspend fun deleteSelectedVideos(): Int {
        val videosToDelete = duplicateGroups.value
            .flatMap { it.videos }
            .filter { _selectedVideoIds.value.contains(it.id) }

        if (videosToDelete.isEmpty()) return 0

        _isDeleting.value = true
        var deletedCount = 0
        var freedBytes = 0L

        try {
            videosToDelete.forEach { video ->
                val success = when (video) {
                    is com.vidremover.domain.model.Video -> repository.deleteVideo(video)
                    is com.vidremover.domain.model.Image -> repository.deleteImage(video)
                    else -> false
                }
                if (success) {
                    deletedCount++
                    freedBytes += video.size
                }
            }

            _deletedCount.value += deletedCount
            _freedSpace.value += freedBytes

            val deletedIds = videosToDelete.map { it.id }.toSet()
            val remainingGroups = duplicateGroups.value
                .map { group ->
                    DuplicateGroup(
                        id = group.id,
                        videos = group.videos.filter { it.id !in deletedIds },
                        similarity = group.similarity
                    )
                }
                .filter { it.videos.size > 1 }

            duplicateStateHolder.setDuplicateGroups(remainingGroups)
            _selectedVideoIds.value = emptySet()

        } catch (e: Exception) {
            _error.value = "Failed to delete videos: ${e.message}"
        } finally {
            _isDeleting.value = false
        }

        return deletedCount
    }

    suspend fun deleteVideo(video: Video): Boolean {
        return try {
            val success = repository.deleteVideo(video)
            if (success) {
                _freedSpace.value += video.size
                _deletedCount.value += 1

                val remainingGroups = duplicateGroups.value
                    .map { group ->
                        DuplicateGroup(
                            id = group.id,
                            videos = group.videos.filter { it.id != video.id },
                            similarity = group.similarity
                        )
                    }
                    .filter { it.videos.size > 1 }

                duplicateStateHolder.setDuplicateGroups(remainingGroups)
                _selectedVideoIds.value = _selectedVideoIds.value - video.id
            }
            success
        } catch (e: Exception) {
            _error.value = "Failed to delete video: ${e.message}"
            false
        }
    }

    fun formatSize(size: Long): String = repository.formatFileSize(size)

    fun formatDuration(durationMs: Long): String = repository.formatDuration(durationMs)

    fun clearError() {
        _error.value = null
    }

    fun resetStats() {
        _deletedCount.value = 0
        _freedSpace.value = 0L
    }
}
