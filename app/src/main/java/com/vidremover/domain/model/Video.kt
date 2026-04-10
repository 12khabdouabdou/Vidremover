package com.vidremover.domain.model

data class Video(
    val id: Long,
    val uri: String,
    val name: String,
    val path: String,
    val size: Long,
    val duration: Long,
    val dateAdded: Long,
    val mimeType: String,
    val folderName: String
)

data class VideoFolder(
    val name: String,
    val path: String,
    val videoCount: Int
)

data class ScanProgress(
    val current: Int,
    val total: Int,
    val currentFile: String,
    val isComplete: Boolean = false
)

data class DuplicateGroup(
    val id: String,
    val videos: List<Video>,
    val similarity: Float
)