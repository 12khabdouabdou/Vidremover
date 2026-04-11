package com.vidremover.domain.model

data class Image(
    val id: Long,
    val uri: String,
    val name: String,
    val path: String,
    val size: Long,
    val dateAdded: Long,
    val mimeType: String,
    val folderName: String,
    val width: Int,
    val height: Int
)

data class ImageFolder(
    val name: String,
    val path: String,
    val imageCount: Int
)
