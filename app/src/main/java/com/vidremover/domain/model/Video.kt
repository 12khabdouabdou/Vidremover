package com.vidremover.domain.model

interface MediaItem {
    val id: Long
    val uri: String
    val name: String
    val path: String
    val size: Long
    val dateAdded: Long
    val mimeType: String
    val folderName: String
}

data class Video(
    override val id: Long,
    override val uri: String,
    override val name: String,
    override val path: String,
    override val size: Long,
    val duration: Long,
    override val dateAdded: Long,
    override val mimeType: String,
    override val folderName: String
) : MediaItem

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
    val videos: List ,
    val similarity: Float
)
