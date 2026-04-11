package com.vidremover.data.model

data class ImageDto(
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

data class ImageFolderDto(
    val name: String,
    val path: String,
    val imageCount: Int
)

fun ImageDto.toDomain(): com.vidremover.domain.model.Image {
    return com.vidremover.domain.model.Image(
        id = id,
        uri = uri,
        name = name,
        path = path,
        size = size,
        dateAdded = dateAdded,
        mimeType = mimeType,
        folderName = folderName,
        width = width,
        height = height
    )
}

fun ImageFolderDto.toDomain(): com.vidremover.domain.model.ImageFolder {
    return com.vidremover.domain.model.ImageFolder(
        name = name,
        path = path,
        imageCount = imageCount
    )
}
