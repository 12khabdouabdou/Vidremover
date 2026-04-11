package com.vidremover.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vidremover.domain.model.VideoFolder
import com.vidremover.domain.repository.VideoRepository
import com.vidremover.domain.usecase.DetectionMode
import com.vidremover.domain.usecase.MediaType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Home screen.
 * Manages scan options, folder selection, and detection mode preferences.
 *
 * @property repository The video repository for accessing video data
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: VideoRepository,
    private val duplicateStateHolder: DuplicateStateHolder
) : ViewModel() {

    private val _folders = MutableStateFlow<List<VideoFolder>>(emptyList())
    val folders: StateFlow<List<VideoFolder>> = _folders.asStateFlow()

    private val _scanAll = MutableStateFlow(true)
    val scanAll: StateFlow<Boolean> = _scanAll.asStateFlow()

    private val _selectedFolders = MutableStateFlow<Set<String>>(emptySet())
    val selectedFolders: StateFlow<Set<String>> = _selectedFolders.asStateFlow()

    private val _detectionMode = MutableStateFlow(DetectionMode.BOTH)
    val detectionMode: StateFlow<DetectionMode> = _detectionMode.asStateFlow()

    private val _mediaType = MutableStateFlow(MediaType.VIDEOS)
    val mediaType: StateFlow<MediaType> = _mediaType.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        loadFolders()
    }

    /**
     * Loads available video folders from the repository.
     */
    fun loadFolders() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                _folders.value = repository.getFolders()
                _error.value = null
            } catch (e: Exception) {
                _error.value = "Failed to load folders: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Sets whether to scan all videos or selected folders only.
     *
     * @param scanAll true to scan all videos, false to scan selected folders
     */
    fun setScanAll(scanAll: Boolean) {
        _scanAll.value = scanAll
        if (scanAll) {
            _selectedFolders.value = emptySet()
        }
    }

    /**
     * Toggles the selection of a folder for scanning.
     *
     * @param folderPath The path of the folder to toggle
     */
    fun toggleFolderSelection(folderPath: String) {
        val current = _selectedFolders.value.toMutableSet()
        if (current.contains(folderPath)) {
            current.remove(folderPath)
        } else {
            current.add(folderPath)
        }
        _selectedFolders.value = current

        // If we have selected folders, automatically switch to folder selection mode
        if (current.isNotEmpty() && _scanAll.value) {
            _scanAll.value = false
        }
    }

    /**
     * Selects all folders for scanning.
     */
    fun selectAllFolders() {
        _selectedFolders.value = _folders.value.map { it.path }.toSet()
        _scanAll.value = false
    }

    /**
     * Clears all folder selections.
     */
    fun clearFolderSelection() {
        _selectedFolders.value = emptySet()
    }

    /**
     * Sets the duplicate detection mode.
     *
     * @param mode The detection mode to use
     */
    fun setDetectionMode(mode: DetectionMode) {
        _detectionMode.value = mode
        duplicateStateHolder.setDetectionMode(mode)
    }

    fun setMediaType(type: MediaType) {
        _mediaType.value = type
        duplicateStateHolder.setMediaType(type)
    }

    fun getFoldersToScan(): List<String> {
        return if (_scanAll.value) {
            emptyList()
        } else {
            _selectedFolders.value.toList()
        }
    }

    /**
     * Validates that the current selection is ready for scanning.
     *
     * @return true if ready to scan, false otherwise
     */
    fun canStartScan(): Boolean {
        return if (_scanAll.value) {
            true
        } else {
            _selectedFolders.value.isNotEmpty()
        }
    }

    /**
     * Gets a user-friendly description of the current scan configuration.
     *
     * @return Description string
     */
    fun getScanDescription(): String {
        return when (_detectionMode.value) {
            DetectionMode.MD5_ONLY -> "Exact duplicates (MD5)"
            DetectionMode.PHASH_ONLY -> "Similar videos (pHash)"
            DetectionMode.BOTH -> "Exact + Similar videos"
        }
    }

    /**
     * Clears any error message.
     */
    fun clearError() {
        _error.value = null
    }
}
