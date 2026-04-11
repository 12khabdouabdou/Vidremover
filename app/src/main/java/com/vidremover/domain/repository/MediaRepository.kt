package com.vidremover.domain.repository

import com.vidremover.domain.model.Image
import com.vidremover.domain.model.ImageFolder

interface MediaRepository : VideoRepository {
    suspend fun getAllImages(): List<Image>
    suspend fun getImagesFromFolders(folders: List<String>): List<Image>
    suspend fun getImageFolders(): List<ImageFolder>
    suspend fun deleteImage(image: Image): Boolean
}
