package com.vidremover.domain.usecase

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.content.Context
import android.net.Uri
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

            // To detect duration-cut videos, we sample both at relative intervals AND fixed absolute intervals
            val positions = mutableSetOf<Long>()
            
            if (durationMs > 0) {
                // Relative intervals (useful for intact videos or scaled videos)
                val step = durationMs / (FRAME_SAMPLE_COUNT + 1)
                for (i in 1..FRAME_SAMPLE_COUNT) {
                    positions.add(i * step)
                }
                
                // Absolute intervals (useful for trimmed/cut videos)
                // Sample at 1s, 2s, 3s, 5s if duration allows
                val absoluteSecs = listOf(1000L, 2000L, 3000L, 5000L)
                for (absMs in absoluteSecs) {
                    if (absMs < durationMs) {
                        positions.add(absMs)
                    }
                }
            } else {
                positions.add(0L)
            }

            // Extract and hash each frame
            for (positionMs in positions.sorted()) {
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
     * Computes perceptual hash for a single image file.
     * 
     * @param file The image file
     * @return 64-bit hash string in hexadecimal format, or null if decoding fails
     */
    fun computeImagePHash(context: Context, uriString: String): String? {
        return try {
            val uri = Uri.parse(uriString)
            val options = BitmapFactory.Options()
            options.inPreferredConfig = Bitmap.Config.ARGB_8888
            options.inJustDecodeBounds = true
            
            context.contentResolver.openInputStream(uri)?.use { 
                BitmapFactory.decodeStream(it, null, options) 
            }
            
            val reqWidth = FRAME_DOWNSCALE_WIDTH
            val reqHeight = FRAME_DOWNSCALE_HEIGHT
            var inSampleSize = 1
            if (options.outHeight > reqHeight || options.outWidth > reqWidth) {
                val halfHeight = options.outHeight / 2
                val halfWidth = options.outWidth / 2
                while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                    inSampleSize *= 2
                }
            }
            
            options.inJustDecodeBounds = false
            options.inSampleSize = inSampleSize
            
            val bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return null
            
            val hash = computeFramePHash(bitmap)
            bitmap.recycle()
            hash
        } catch (e: Exception) {
            Log.w(TAG, "Failed to compute image pHash for $uriString", e)
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
        // Instead of a cryptographic hash which destroys perceptual similarity,
        // we join the hashes with a delimiter so we can compare individual frames later.
        return frameHashes.joinToString("-")
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
        // Fallback for single frames or legacy MD5 hashes
        if (!hash1.contains("-") || !hash2.contains("-")) {
            return compareSingleFrame(hash1, hash2)
        }

        val frames1 = hash1.split("-").filter { it.isNotEmpty() }
        val frames2 = hash2.split("-").filter { it.isNotEmpty() }
        
        if (frames1.isEmpty() || frames2.isEmpty()) return 0.0f

        // To support cut/trimmed videos, we want to find if there are highly matching frames.
        // We find the max similarity for each frame in video 1 against any frame in video 2.
        var totalSimilarity1 = 0.0f
        for (f1 in frames1) {
            var bestFrameSim = 0.0f
            for (f2 in frames2) {
                val sim = compareSingleFrame(f1, f2)
                if (sim > bestFrameSim) {
                    bestFrameSim = sim
                }
            }
            totalSimilarity1 += bestFrameSim
        }
        
        // Similarly check from frames2 to frames1
        var totalSimilarity2 = 0.0f
        for (f2 in frames2) {
            var bestFrameSim = 0.0f
            for (f1 in frames1) {
                val sim = compareSingleFrame(f1, f2)
                if (sim > bestFrameSim) {
                    bestFrameSim = sim
                }
            }
            totalSimilarity2 += bestFrameSim
        }
        
        val avgSim1 = totalSimilarity1 / frames1.size
        val avgSim2 = totalSimilarity2 / frames2.size
        
        // Return the max of the averages, allowing trimmed videos to match fully
        // if one is a sub-clip of the other.
        return maxOf(avgSim1, avgSim2)
    }

    private fun compareSingleFrame(f1: String, f2: String): Float {
        if (f1.length != f2.length || f1.isEmpty()) return 0.0f
        
        if (f1.length == 16) {
            try {
                val val1 = java.lang.Long.parseUnsignedLong(f1, 16)
                val val2 = java.lang.Long.parseUnsignedLong(f2, 16)
                val xor = val1 xor val2
                val bitDifferences = java.lang.Long.bitCount(xor)
                return 1.0f - (bitDifferences.toFloat() / 64.0f)
            } catch (e: Exception) {
                // Fallback
            }
        }
        
        var bitDifferences = 0
        for (i in f1.indices) {
            try {
                val val1 = Character.digit(f1[i], 16)
                val val2 = Character.digit(f2[i], 16)
                if (val1 >= 0 && val2 >= 0) {
                    bitDifferences += Integer.bitCount(val1 xor val2)
                } else {
                    if (f1[i] != f2[i]) bitDifferences += 4
                }
            } catch (e: Exception) {
                if (f1[i] != f2[i]) bitDifferences += 4 
            }
        }
        val totalBits = f1.length * 4
        return 1.0f - (bitDifferences.toFloat() / totalBits)
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
