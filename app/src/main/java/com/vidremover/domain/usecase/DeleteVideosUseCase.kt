package com.vidremover.domain.usecase

import com.vidremover.domain.model.Video
import com.vidremover.data.repository.VideoRepository
import javax.inject.Inject

class DeleteVideosUseCase @Inject constructor(
    private val repository: VideoRepository
) {
    suspend operator fun invoke(videos: List<Video>): DeleteResult {
        var totalFreed = 0L
        var deletedCount = 0
        val failed = mutableListOf<Video>()

        videos.forEach { video ->
            try {
                val freedBefore = repository.getStorageFreedBytes()
                val success = repository.deleteVideo(video)
                if (success) {
                    deletedCount++
                } else {
                    failed.add(video)
                }
            } catch (e: Exception) {
                failed.add(video)
            }
        }

        return DeleteResult(
            deletedCount = deletedCount,
            failedCount = failed.size,
            freedBytes = repository.getStorageFreedBytes()
        )
    }
}

data class DeleteResult(
    val deletedCount: Int,
    val failedCount: Int,
    val freedBytes: Long
)