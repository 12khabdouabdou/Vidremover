package com.vidremover.domain.usecase

import com.vidremover.domain.model.DuplicateGroup
import com.vidremover.domain.model.Video
import com.vidremover.data.repository.VideoRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow

enum class DetectionMode {
    MD5_ONLY,
    PHASH_ONLY,
    BOTH
}

class DetectDuplicatesUseCase(
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

    private fun detectMD5Only(videos: List<Video>): Flow<List<DuplicateGroup>> = flow {
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

    private fun detectPHashOnly(videos: List<Video>, threshold: Float): Flow<List<DuplicateGroup>> = flow {
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

    private fun detectBoth(videos: List<Video>, threshold: Float): Flow<List<DuplicateGroup>> = flow {
        val md5Groups = mutableListOf<DuplicateGroup>()
        val phashGroups = mutableListOf<DuplicateGroup>()

        // First detect MD5 duplicates
        val hashMap = mutableMapOf<String, MutableList<Video>>()
        videos.forEach { video ->
            val hash = repository.computeMD5Hash(video)
            hashMap.getOrPut(hash) { mutableListOf() }.add(video)
        }
        md5Groups.addAll(hashMap.filter { it.value.size > 1 }
            .map { (hash, vids) -> DuplicateGroup(id = "md5_$hash", videos = vids, similarity = 1.0f) })

        // Then detect pHash duplicates
        val pHashMap = mutableMapOf<String, MutableList<Video>>()
        videos.forEach { video ->
            try {
                val hash = repository.computepHash(video)
                pHashMap.getOrPut(hash) { mutableListOf() }.add(video)
            } catch (e: Exception) {}
        }
        phashGroups.addAll(pHashMap.filter { it.value.size > 1 }
            .map { (hash, vids) -> DuplicateGroup(id = "phash_$hash", videos = vids, similarity = threshold) })

        // Combine all groups
        emit(md5Groups + phashGroups)
    }
}