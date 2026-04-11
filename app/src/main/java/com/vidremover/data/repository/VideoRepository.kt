package com.vidremover.data.repository

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.vidremover.domain.model.Video
import com.vidremover.domain.model.VideoFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

class VideoRepository(private val context: Context) {

    private val contentResolver: ContentResolver = context.contentResolver

    suspend fun getAllVideos(): List<Video> = withContext(Dispatchers.IO) {
        val videos = mutableListOf<Video>()
        
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.BUCKET_ID
        )

        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        contentResolver.query(
            collection,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
            val bucketColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn) ?: "Unknown"
                val path = cursor.getString(pathColumn) ?: ""
                val size = cursor.getLong(sizeColumn)
                val duration = cursor.getLong(durationColumn)
                val dateAdded = cursor.getLong(dateColumn)
                val mimeType = cursor.getString(mimeColumn) ?: "video/*"
                val folderName = cursor.getString(bucketColumn) ?: "Unknown"

                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    id
                )

                videos.add(
                    Video(
                        id = id,
                        uri = contentUri.toString(),
                        name = name,
                        path = path,
                        size = size,
                        duration = duration,
                        dateAdded = dateAdded,
                        mimeType = mimeType,
                        folderName = folderName
                    )
                )
            }
        }

        videos
    }

    suspend fun getVideosFromFolders(folders: List<String>): List<Video> = withContext(Dispatchers.IO) {
        val allVideos = getAllVideos()
        if (folders.isEmpty()) return@withContext allVideos
        
        allVideos.filter { video ->
            folders.any { folder -> video.path.startsWith(folder) }
        }
    }

    suspend fun getFolders(): List<VideoFolder> = withContext(Dispatchers.IO) {
        val folderMap = mutableMapOf<String, MutableList<Video>>()

        val videos = getAllVideos()
        videos.forEach { video ->
            val folderPath = video.path.substringBeforeLast("/")
            if (!folderMap.containsKey(folderPath)) {
                folderMap[folderPath] = mutableListOf()
            }
            folderMap[folderPath]?.add(video)
        }

        folderMap.map { (path, videos) ->
            VideoFolder(
                name = videos.first().folderName,
                path = path,
                videoCount = videos.size
            )
        }.sortedByDescending { it.videoCount }
    }

    suspend fun deleteVideo(video: Video): Boolean = withContext(Dispatchers.IO) {
        try {
            val uri = Uri.parse(video.uri)
            contentResolver.delete(uri, null, null) > 0
        } catch (e: Exception) {
            false
        }
    }

    fun formatFileSize(size: Long): String {
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            size < 1024 * 1024 * 1024 -> "${size / (1024 * 1024)} MB"
            else -> "${size / (1024 * 1024 * 1024)} GB"
        }
    }

    fun formatDuration(durationMs: Long): String {
        val seconds = (durationMs / 1000) % 60
        val minutes = (durationMs / (1000 * 60)) % 60
        val hours = durationMs / (1000 * 60 * 60)
        
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }

    suspend fun computeMD5Hash(video: Video): String = withContext(Dispatchers.IO) {
        try {
            val file = java.io.File(video.path)
            if (!file.exists()) return@withContext video.id.toString()

            val digest = MessageDigest.getInstance("MD5")
            val fileSize = file.length()
            val sampleSize = minOf(1024 * 1024, fileSize).toInt()
            val sampleStep = maxOf(1, (fileSize / sampleSize).toInt())

            java.io.RandomAccessFile(file, "r").use { raf ->
                var bytesRead = 0L
                val buffer = ByteArray(8192)
                
                while (bytesRead < fileSize && bytesRead < sampleSize * 10) {
                    raf.seek(bytesRead)
                    val read = raf.read(buffer)
                    if (read > 0) {
                        digest.update(buffer, 0, minOf(read, sampleSize - (bytesRead.toInt())))
                    }
                    bytesRead += sampleStep
                }
            }

            digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        } catch (e: Exception) {
            video.id.toString()
        }
    }

    suspend fun computepHash(video: Video): String = withContext(Dispatchers.IO) {
        try {
            val file = java.io.File(video.path)
            if (!file.exists()) return@withContext video.id.toString()

            val digest = MessageDigest.getInstance("MD5")
            val fileSize = file.length()
            
            // Sample more bytes for perceptual hash
            val sampleSize = minOf(2 * 1024 * 1024, fileSize).toInt()
            val sampleStep = maxOf(1, (fileSize / sampleSize).toInt())

            java.io.RandomAccessFile(file, "r").use { raf ->
                var bytesRead = 0L
                val buffer = ByteArray(8192)
                val samples = mutableListOf<Byte>()
                
                while (bytesRead < fileSize && bytesRead < sampleSize * 10) {
                    raf.seek(bytesRead)
                    val read = raf.read(buffer)
                    if (read > 0) {
                        digest.update(buffer, 0, read)
                        samples.add(buffer[0])
                        samples.add(buffer[minOf(read - 1, 8191)])
                    }
                    bytesRead += sampleStep
                }
            }

            val hash = digest.digest()
            hash.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            video.id.toString()
        }
    }

    suspend fun getStorageFreedBytes(): Long = withContext(Dispatchers.IO) {
        // Calculate storage freed - this is a simplified version
        // In production, you'd track deleted video sizes
        0L
    }
}