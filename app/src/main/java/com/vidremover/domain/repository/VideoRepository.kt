package com.vidremover.domain.repository

import com.vidremover.domain.model.Video
import com.vidremover.domain.model.VideoFolder

interface VideoRepository {
    suspend fun getAllVideos(): List<Video>
    suspend fun getVideosFromFolders(folders: List<String>): List<Video>
    suspend fun getFolders(): List<VideoFolder>
    suspend fun deleteVideo(video: Video): Boolean
    suspend fun computeMD5Hash(video: Video): String
    suspend fun computePHash(video: Video): String
    fun formatFileSize(size: Long): String
    fun formatDuration(durationMs: Long): String
    suspend fun getStorageFreedBytes(): Long
}
