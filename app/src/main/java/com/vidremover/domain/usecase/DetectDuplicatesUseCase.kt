package com.vidremover.domain.usecase

import android.util.Log
import com.vidremover.domain.model.DuplicateGroup
import com.vidremover.domain.model.Video
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enum representing the available duplicate detection modes.
 */
enum class DetectionMode {
    /** Fast exact duplicate detection using MD5 hashing */
    MD5_ONLY,

    /** Similar content detection using perceptual hashing */
    PHASH_ONLY,

    /** Hybrid detection: MD5 first, then pHash on remaining videos */
    BOTH
}

/**
 * Data class representing the progress of duplicate detection.
 *
 * @property current The current video being processed (0-indexed)
 * @property total The total number of videos to process
 * @property currentFile The name of the current file being processed
 * @property isComplete Whether the detection is complete
 */
data class DetectionProgress(
    val current: Int,
    val total: Int,
    val currentFile: String,
    val isComplete: Boolean = false
)

/**
 * Use case for detecting duplicate videos using MD5 and/or perceptual hashing.
 *
 * Supports three detection modes:
 * - MD5_ONLY: Fast exact duplicate detection using file content hash
 * - PHASH_ONLY: Similar content detection using perceptual hashing
 * - BOTH: Combines both methods for comprehensive detection
 *
 * @property computeMD5Hash Use case for computing MD5 hashes
 * @property computePHash Use case for computing perceptual hashes
 */
@Singleton
class DetectDuplicatesUseCase @Inject constructor(
    private val computeMD5Hash: ComputeMD5HashUseCase,
    private val computePHash: ComputePHashUseCase
) {
    companion object {
        private const val TAG = "DetectDuplicatesUseCase"
    }

    /**
     * Detects duplicate videos from the provided list based on the specified mode.
     *
     * @param videos The list of videos to analyze for duplicates
     * @param mode The detection mode to use (MD5_ONLY, PHASH_ONLY, or BOTH)
     * @param pHashThreshold Similarity threshold for pHash comparison (0.0 - 1.0, default: 0.9)
     * @param onProgress Optional callback for progress updates (current, total, filename)
     * @return Flow emitting lists of duplicate groups as they are discovered
     */
    operator fun invoke(
        videos: List<Video>,
        mode: DetectionMode,
        pHashThreshold: Float = 0.9f,
        onProgress: ((current: Int, total: Int, filename: String) -> Unit)? = null
    ): Flow<List<DuplicateGroup>> = flow {
        val sortedVideos = videos.sortedByDescending { it.size }

        when (mode) {
            DetectionMode.MD5_ONLY -> emit(detectMD5Only(sortedVideos, onProgress))
            DetectionMode.PHASH_ONLY -> emit(detectPHashOnly(sortedVideos, pHashThreshold, onProgress))
            DetectionMode.BOTH -> emit(detectBoth(sortedVideos, pHashThreshold, onProgress))
        }
    }

    /**
     * Detects duplicates using MD5 hashing only.
     * Fast and accurate for detecting exact file duplicates.
     *
     * @param videos The list of videos to analyze
     * @param onProgress Optional progress callback
     * @return List of duplicate groups
     */
    private suspend fun detectMD5Only(
        videos: List<Video>,
        onProgress: ((current: Int, total: Int, filename: String) -> Unit)?
    ): List<DuplicateGroup> {
        val hashMap = mutableMapOf<String, MutableList<Video>>()
        val total = videos.size

        videos.forEachIndexed { index, video ->
            onProgress?.invoke(index, total, video.name)

            try {
                val hash = computeMD5Hash(video)
                hashMap.getOrPut(hash) { mutableListOf() }.add(video)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to compute MD5 for video: ${video.name}", e)
            }
        }

        return hashMap
            .filter { it.value.size > 1 }
            .map { (hash, videoList) ->
                DuplicateGroup(
                    id = "md5_$hash",
                    videos = videoList.sortedByDescending { it.size },
                    similarity = 1.0f
                )
            }
            .sortedByDescending { it.videos.size }
    }

    /**
     * Detects duplicates using perceptual hashing only.
     * Detects similar content even after re-encoding or compression.
     *
     * @param videos The list of videos to analyze
     * @param threshold Similarity threshold for considering videos duplicates
     * @param onProgress Optional progress callback
     * @return List of duplicate groups
     */
    private suspend fun detectPHashOnly(
        videos: List<Video>,
        threshold: Float,
        onProgress: ((current: Int, total: Int, filename: String) -> Unit)?
    ): List<DuplicateGroup> {
        val hashes = mutableListOf<Pair<Video, String>>()
        val total = videos.size

        videos.forEachIndexed { index, video ->
            onProgress?.invoke(index, total, video.name)

            try {
                val hash = computePHash(video, threshold)
                hashes.add(video to hash)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to compute pHash for video: ${video.name}", e)
            }
        }

        val visited = BooleanArray(hashes.size)
        val groups = mutableListOf<DuplicateGroup>()

        for (i in hashes.indices) {
            if (visited[i]) continue
            visited[i] = true

            val (video1, hash1) = hashes[i]
            val currentGroup = mutableListOf(video1)

            for (j in i + 1 until hashes.size) {
                if (visited[j]) continue

                val (video2, hash2) = hashes[j]
                if (computePHash.compareHashes(hash1, hash2) >= threshold) {
                    currentGroup.add(video2)
                    visited[j] = true
                }
            }

            if (currentGroup.size > 1) {
                groups.add(
                    DuplicateGroup(
                        id = "phash_${hash1.take(10)}_${System.currentTimeMillis()}",
                        videos = currentGroup.sortedByDescending { it.size },
                        similarity = threshold
                    )
                )
            }
        }

        return groups.sortedByDescending { it.videos.size }
    }

    /**
     * Detects duplicates using both MD5 and pHash methods.
     * First finds exact duplicates with MD5, then finds similar content with pHash.
     *
     * @param videos The list of videos to analyze
     * @param threshold Similarity threshold for pHash comparison
     * @param onProgress Optional progress callback
     * @return Combined list of duplicate groups from both methods
     */
    private suspend fun detectBoth(
        videos: List<Video>,
        threshold: Float,
        onProgress: ((current: Int, total: Int, filename: String) -> Unit)?
    ): List<DuplicateGroup> {
        // First pass: MD5 detection for exact duplicates
        onProgress?.invoke(0, videos.size * 2, "Computing MD5 hashes...")
        val md5Groups = detectMD5Only(videos) { current, total, filename ->
            onProgress?.invoke(current, total * 2, filename)
        }

        // Find videos that are already in MD5 duplicates
        val videosInMD5Groups = md5Groups.flatMap { it.videos }.map { it.id }.toSet()

        // Second pass: pHash on remaining videos
        val remainingVideos = videos.filter { it.id !in videosInMD5Groups }

        if (remainingVideos.isNotEmpty()) {
            onProgress?.invoke(videos.size, videos.size * 2, "Computing pHash...")
            val pHashGroups = detectPHashOnly(remainingVideos, threshold) { current, total, filename ->
                onProgress?.invoke(videos.size + current, videos.size * 2, filename)
            }

            return (md5Groups + pHashGroups).sortedByDescending { it.videos.size }
        }

        return md5Groups
    }

    /**
     * Groups videos by their hash values and filters for duplicates.
     *
     * @param videos List of videos with their computed hashes
     * @param prefix Prefix to add to group IDs (e.g., "md5_" or "phash_")
     * @param similarity The similarity score for this detection method
     * @return List of duplicate groups
     */
    private fun groupByHash(
        videos: List<Pair<Video, String>>,
        prefix: String,
        similarity: Float
    ): List<DuplicateGroup> {
        val hashMap = mutableMapOf<String, MutableList<Video>>()

        videos.forEach { (video, hash) ->
            hashMap.getOrPut(hash) { mutableListOf() }.add(video)
        }

        return hashMap
            .filter { it.value.size > 1 }
            .map { (hash, videoList) ->
                DuplicateGroup(
                    id = "$prefix$hash",
                    videos = videoList.sortedByDescending { it.size },
                    similarity = similarity
                )
            }
    }
}
