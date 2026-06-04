package com.vidremover.domain.usecase

import android.util.Log
import com.vidremover.domain.model.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Use case for computing MD5 hash of a video file.
 *
 * This use case implements a sampling strategy for large files to improve performance:
 * - Samples up to 1MB from files larger than 1MB
 * - Uses random access to read samples from different parts of the file
 * - Falls back to video ID as hash on errors
 *
 * The sampling approach provides a balance between hash accuracy and computation speed,
 * making it suitable for duplicate detection on large video collections.
 *
 * @constructor Creates a new instance with Hilt dependency injection
 *
 * @sample
 * ```kotlin
 * val useCase = ComputeMD5HashUseCase()
 * val hash = useCase(video)
 * ```
 */
@Singleton
class ComputeMD5HashUseCase @Inject constructor() {

    companion object {
        private const val TAG = "ComputeMD5HashUseCase"
        private const val DEFAULT_SAMPLE_SIZE = 1024 * 1024L // 1MB
        private const val BUFFER_SIZE = 8192
    }

    /**
     * Computes MD5 hash for a video file using sampling for performance.
     *
     * For files smaller than the sample size, computes hash of the entire file.
     * For larger files, samples data from multiple positions to create a representative hash.
     *
     * @param video The video to compute hash for
     * @return MD5 hash string in hexadecimal format, or video ID as fallback on error
     * @throws SecurityException if file access is denied (caught internally)
     * @throws IOException if file cannot be read (caught internally)
     */
    suspend operator fun invoke(video: Video): String = withContext(Dispatchers.IO) {
        try {
            val file = File(video.path)

            // Validate file exists and is readable
            if (!file.exists()) {
                Log.w(TAG, "File not found: ${video.path}")
                return@withContext video.id.toString()
            }

            if (!file.canRead()) {
                Log.w(TAG, "Cannot read file: ${video.path}")
                return@withContext video.id.toString()
            }

            val fileSize = file.length()
            val digest = MessageDigest.getInstance("MD5")

            // Determine sample strategy based on file size
            val sampleSize = minOf(DEFAULT_SAMPLE_SIZE, fileSize)

            if (fileSize <= sampleSize) {
                // Small file: hash entire content
                hashEntireFile(file, digest)
            } else {
                // Large file: use sampling strategy
                hashWithSampling(file, fileSize, sampleSize, digest)
            }

            // Convert digest to hex string
            digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }

        } catch (e: FileNotFoundException) {
            Log.e(TAG, "File not found for video ${video.id}", e)
            video.id.toString()
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for video ${video.id}", e)
            video.id.toString()
        } catch (e: IOException) {
            Log.e(TAG, "IO error computing hash for video ${video.id}", e)
            video.id.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error computing hash for video ${video.id}", e)
            video.id.toString()
        }
    }

    /**
     * Hashes the entire file content.
     *
     * @param file The file to hash
     * @param digest The MessageDigest instance to update
     */
    private fun hashEntireFile(file: File, digest: MessageDigest) {
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
    }

    /**
     * Hashes a file using sampling strategy for large files.
     *
     * Samples data from beginning, middle, and end of file to create
     * a representative hash without reading the entire file.
     *
     * @param file The file to hash
     * @param fileSize Total size of the file
     * @param sampleSize Target sample size to read
     * @param digest The MessageDigest instance to update
     */
    private fun hashWithSampling(
        file: File,
        fileSize: Long,
        sampleSize: Long,
        digest: MessageDigest
    ) {
        RandomAccessFile(file, "r").use { raf ->
            val buffer = ByteArray(BUFFER_SIZE)
            val numSamples = 10 // Number of sample positions
            val step = fileSize / numSamples

            // Sample from multiple positions in the file
            for (i in 0 until numSamples) {
                val position = i * step
                val remainingSampleSize = sampleSize / numSamples

                raf.seek(position)

                var bytesReadInSample = 0L
                while (bytesReadInSample < remainingSampleSize) {
                    val remainingToRead = minOf(
                        buffer.size.toLong(),
                        remainingSampleSize - bytesReadInSample
                    ).toInt()

                    val bytesRead = raf.read(buffer, 0, remainingToRead)
                    if (bytesRead <= 0) break

                    digest.update(buffer, 0, bytesRead)
                    bytesReadInSample += bytesRead
                }
            }
        }
    }
}
