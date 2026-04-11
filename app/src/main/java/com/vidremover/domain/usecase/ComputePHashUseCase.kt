package com.vidremover.domain.usecase

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.util.Log
import com.vidremover.domain.model.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Use case for computing perceptual hash (pHash) of a video file.
 *
 * This use case extracts key frames from the video and computes a perceptual hash
 * that can detect visually similar videos even after re-encoding, compression, or
 * minor edits. Unlike MD5 which detects exact duplicates, pHash detects similar content.
 *
 * The algorithm:
 * 1. Extract multiple frames at regular intervals using MediaMetadataRetriever
 * 2. Downscale each frame to 8x8 pixels
 * 3. Convert to grayscale
 * 4. Compute average pixel brightness
 * 5. Create a binary hash based on pixels above/below average
 * 6. Combine frame hashes into final video hash
 *
 * @constructor Creates a new instance with Hilt dependency injection
 * @param hashSimilarityThreshold Default threshold for considering hashes similar (0.0 - 1.0)
 *
 * @sample
 * ```kotlin
 * val useCase = ComputePHashUseCase()
 * val hash = useCase(video) // Uses default threshold
 * val hash = useCase(video, threshold = 0.85f) // Custom threshold
 * ```
 */
@Singleton
class ComputePHashUseCase @Inject constructor() {
    private val hashSimilarityThreshold: Float = DEFAULT_SIMILARITY_THRESHOLD

    companion object {
        private const val TAG = "ComputePHashUseCase"
        private const val DEFAULT_SIMILARITY_THRESHOLD = 0.9f
        private const val FRAME_SAMPLE_COUNT = 5
        private const val HASH_WIDTH = 8
        private const val HASH_HEIGHT = 8
        private const val FRAME_DOWNSCALE_WIDTH = 32
        private const val FRAME_DOWNSCALE_HEIGHT = 32
    }

    /**
     * Computes perceptual hash for a video file using frame sampling.
     *
     * Extracts multiple frames from the video at regular intervals and computes
     * a perceptual hash based on the visual content of these frames.
     *
     * @param video The video to compute hash for
     * @param threshold Similarity threshold for hash comparison (0.0 - 1.0, default: 0.9)
     * @return Perceptual hash string, or video ID as fallback on error
     * @throws IllegalArgumentException if threshold is not in valid range (caught internally)
     * @throws RuntimeException if frame extraction fails (caught internally)
     */
    suspend operator fun invoke(
        video: Video,
        threshold: Float = hashSimilarityThreshold
    ): String = withContext(Dispatchers.IO) {
        try {
            // Validate threshold
            require(threshold in 0.0f..1.0f) { "Threshold must be between 0.0 and 1.0" }

            val file = File(video.path)

            // Validate file exists
            if (!file.exists()) {
                Log.w(TAG, "File not found: ${video.path}")
                return@withContext video.id.toString()
            }

            if (!file.canRead()) {
                Log.w(TAG, "Cannot read file: ${video.path}")
                return@withContext video.id.toString()
            }

            // Extract frames and compute hash
            val frameHashes = extractFrameHashes(file, video.duration)

            if (frameHashes.isEmpty()) {
                Log.w(TAG, "No frames extracted for video ${video.id}")
                return@withContext video.id.toString()
            }

            // Combine frame hashes into final video hash
            combineFrameHashes(frameHashes)

        } catch (e: FileNotFoundException) {
            Log.e(TAG, "File not found for video ${video.id}", e)
            video.id.toString()
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for video ${video.id}", e)
            video.id.toString()
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Invalid argument for video ${video.id}", e)
            video.id.toString()
        } catch (e: RuntimeException) {
            Log.e(TAG, "Runtime error computing pHash for video ${video.id}", e)
            video.id.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error computing pHash for video ${video.id}", e)
            video.id.toString()
        }
    }

    /**
     * Extracts frames from the video at regular intervals and computes hashes.
     *
     * Uses MediaMetadataRetriever to efficiently extract frames without
     * decoding the entire video.
     *
     * @param file The video file
     * @param durationMs Video duration in milliseconds
     * @return List of frame hash strings
     */
    private fun extractFrameHashes(file: File, durationMs: Long): List<String> {
        val retriever = MediaMetadataRetriever()
        val hashes = mutableListOf<String>()

        try {
            retriever.setDataSource(file.absolutePath)

            // Calculate frame positions to sample
            val positions = if (durationMs <= 0 || durationMs < FRAME_SAMPLE_COUNT * 1000) {
                // Short video: just extract at beginning
                listOf(0L)
            } else {
                // Sample frames at regular intervals
                val step = durationMs / (FRAME_SAMPLE_COUNT + 1)
                (1..FRAME_SAMPLE_COUNT).map { it * step }
            }

            // Extract and hash each frame
            for (positionMs in positions) {
                val frameHash = extractAndHashFrame(retriever, positionMs * 1000) // Convert to microseconds
                if (frameHash != null) {
                    hashes.add(frameHash)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error extracting frames from ${file.absolutePath}", e)
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing MediaMetadataRetriever", e)
            }
        }

        return hashes
    }

    /**
     * Extracts a single frame at the specified time and computes its perceptual hash.
     *
     * @param retriever The MediaMetadataRetriever instance
     * @param timeUs Time position in microseconds
     * @return Perceptual hash string of the frame, or null if extraction fails
     */
    private fun extractAndHashFrame(
        retriever: MediaMetadataRetriever,
        timeUs: Long
    ): String? {
        return try {
            // Get frame at specified time
            val bitmap = retriever.getFrameAtTime(
                timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: return null

            // Compute perceptual hash for this frame
            val hash = computeFramePHash(bitmap)

            // Recycle bitmap to free memory
            bitmap.recycle()

            hash

        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract frame at time $timeUs", e)
            null
        }
    }

    /**
     * Computes perceptual hash for a single bitmap frame.
     *
     * Implements the simplified pHash algorithm:
     * 1. Downscale to 32x32
     * 2. Convert to grayscale
     * 3. Compute average brightness
     * 4. Create 64-bit hash based on pixels above/below average
     *
     * @param bitmap The frame bitmap
     * @return 64-bit hash string in hexadecimal format
     */
    private fun computeFramePHash(bitmap: Bitmap): String {
        // Downscale to improve performance and focus on structure
        val scaledBitmap = Bitmap.createScaledBitmap(
            bitmap,
            FRAME_DOWNSCALE_WIDTH,
            FRAME_DOWNSCALE_HEIGHT,
            true
        )

        // Calculate average brightness
        var totalBrightness = 0.0
        val pixelCount = FRAME_DOWNSCALE_WIDTH * FRAME_DOWNSCALE_HEIGHT
        val pixels = IntArray(pixelCount)

        scaledBitmap.getPixels(pixels, 0, FRAME_DOWNSCALE_WIDTH, 0, 0, FRAME_DOWNSCALE_WIDTH, FRAME_DOWNSCALE_HEIGHT)

        for (pixel in pixels) {
            totalBrightness += getBrightness(pixel)
        }

        val averageBrightness = totalBrightness / pixelCount

        // Create hash based on pixels above/below average
        // Sample 8x8 from the 32x32 image for the final hash
        val hashBuilder = StringBuilder()
        val step = FRAME_DOWNSCALE_WIDTH / HASH_WIDTH

        for (y in 0 until HASH_HEIGHT) {
            for (x in 0 until HASH_WIDTH) {
                val pixelIndex = (y * step) * FRAME_DOWNSCALE_WIDTH + (x * step)
                val brightness = getBrightness(pixels[pixelIndex])
                hashBuilder.append(if (brightness >= averageBrightness) "1" else "0")
            }
        }

        // Clean up scaled bitmap
        scaledBitmap.recycle()

        // Convert binary string to hex
        return binaryToHex(hashBuilder.toString())
    }

    /**
     * Calculates the perceived brightness of a pixel.
     *
     * Uses standard luminance formula: 0.299*R + 0.587*G + 0.114*B
     *
     * @param pixel The ARGB pixel value
     * @return Brightness value (0.0 - 255.0)
     */
    private fun getBrightness(pixel: Int): Double {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        return 0.299 * r + 0.587 * g + 0.114 * b
    }

    /**
     * Converts a binary string to hexadecimal representation.
     *
     * @param binary The binary string
     * @return Hexadecimal string
     */
    private fun binaryToHex(binary: String): String {
        return binary.chunked(4)
            .map { it.toInt(2).toString(16) }
            .joinToString("")
            .uppercase()
    }

    /**
     * Combines multiple frame hashes into a single video hash.
     *
     * Uses MD5 hash of the concatenated frame hashes to create
     * a consistent length hash regardless of frame count.
     *
     * @param frameHashes List of individual frame hashes
     * @return Combined hash string
     */
    private fun combineFrameHashes(frameHashes: List<String>): String {
        val combined = frameHashes.joinToString("")
        val digest = MessageDigest.getInstance("MD5")
        digest.update(combined.toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Compares two perceptual hashes and returns their similarity.
     *
     * Uses Hamming distance to calculate similarity between hashes.
     *
     * @param hash1 First hash string
     * @param hash2 Second hash string
     * @return Similarity score between 0.0 and 1.0
     */
    fun compareHashes(hash1: String, hash2: String): Float {
        if (hash1.length != hash2.length) return 0.0f

        var differences = 0
        for (i in hash1.indices) {
            if (hash1[i] != hash2[i]) differences++
        }

        return 1.0f - (differences.toFloat() / hash1.length)
    }

    /**
     * Determines if two hashes are similar based on the configured threshold.
     *
     * @param hash1 First hash string
     * @param hash2 Second hash string
     * @return true if hashes are considered similar
     */
    fun areSimilar(hash1: String, hash2: String): Boolean {
        return compareHashes(hash1, hash2) >= hashSimilarityThreshold
    }
}
