package com.vidremover.domain.usecase

import com.vidremover.domain.model.DuplicateGroup
import com.vidremover.domain.model.Video
import com.vidremover.data.repository.VideoRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

enum class DetectionMode {
    MD5_ONLY,
    PHASH_ONLY,
    BOTH
}

class DetectDuplicatesUseCase @Inject constructor(
    private val repository: VideoRepository
) {
    operator fun invoke(
        videos: List<Video>,
        mode: DetectionMode,
        pHashThreshold: Float = 0.9f
    ): Flow<List<DuplicateGroup>> {
        return when (mode) {
            DetectionMode.MD5_ONLY -> detectMD5Only(videos)
            DetectionMode.PHASH_ONLY -> detectPHashOnly(videos, pHashThreshold)
            DetectionMode.BOTH -> detectBoth(videos, pHashThreshold)
        }
    }

    private fun detectMD5Only(videos: List<Video>): Flow<List<DuplicateGroup>> {
        return kotlinx.coroutines.flow.flow {
            val hashMap = mutableMapOf<String, MutableList<Video>>()
            
            videos.forEach { video ->
                val hash = repository.computeMD5Hash(video)
                hashMap.getOrPut(hash) { mutableListOf() }.add(video)
            }
            
            val groups = hashMap.filter { it.value.size > 1 }
                .map { (hash, videos) -> 
                    DuplicateGroup(
                        id = hash,
                        videos = videos,
                        similarity = 1.0f
                    )
                }
            emit(groups)
        }
    }

    private fun detectPHashOnly(videos: List<Video>, threshold: Float): Flow<List<DuplicateGroup>> {
        return kotlinx.coroutines.flow.flow {
            val hashMap = mutableMapOf<String, MutableList<Video>>()
            
            videos.forEach { video ->
                try {
                    val hash = repository.computepHash(video)
                    hashMap.getOrPut(hash) { mutableListOf() }.add(video)
                } catch (e: Exception) {
                    // Skip videos that can't be hashed
                }
            }
            
            val groups = hashMap.filter { it.value.size > 1 }
                .map { (hash, videos) ->
                    DuplicateGroup(
                        id = hash,
                        videos = videos,
                        similarity = threshold
                    )
                }
            emit(groups)
        }
    }

    private fun detectBoth(videos: List<Video>, threshold: Float): Flow<List<DuplicateGroup>> {
        return combine(detectMD5Only(videos), detectPHashOnly(videos, threshold)) { md5Groups, pHashGroups ->
            val allGroups = (md5Groups + pHashGroups).groupBy { it.id }
            allGroups.values.map { groups ->
                val bestSimilarity = groups.maxOfOrNull { it.similarity } ?: 0f
                val allVideos = groups.flatMap { it.videos }.distinctBy { it.id }
                DuplicateGroup(
                    id = groups.first().id,
                    videos = allVideos,
                    similarity = bestSimilarity
                )
            }.filter { it.videos.size > 1 }
        }
    }
}