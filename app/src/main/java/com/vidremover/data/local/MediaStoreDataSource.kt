package com.vidremover.data.local

import android.app.Activity
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.result.ActivityResultLauncher
import com.vidremover.data.model.VideoDto
import com.vidremover.data.model.VideoFolderDto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaStoreDataSource @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val contentResolver: ContentResolver = context.contentResolver

    private var deleteRequestLauncher: ActivityResultLauncher<Intent>? = null
    private var pendingDeleteCallback: ((Boolean) -> Unit)? = null

    fun setDeleteRequestLauncher(launcher: ActivityResultLauncher<Intent>) {
        deleteRequestLauncher = launcher
    }

    fun onDeleteResult(resultCode: Int) {
        val success = resultCode == Activity.RESULT_OK
        pendingDeleteCallback?.invoke(success)
        pendingDeleteCallback = null
    }

    suspend fun queryVideos(): List<VideoDto> = withContext(Dispatchers.IO) {
        val videos = mutableListOf<VideoDto>()

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
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME
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
                    VideoDto(
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

    /**
     * Queries videos from specific folders.
     * @param folderPaths List of folder paths to filter
     * @return List of VideoDto from the specified folders
     */
    suspend fun queryVideosFromFolders(folderPaths: List<String>): List<VideoDto> = withContext(Dispatchers.IO) {
        if (folderPaths.isEmpty()) {
            return@withContext queryVideos()
        }

        val allVideos = queryVideos()
        allVideos.filter { video ->
            folderPaths.any { folder -> video.path.startsWith(folder) }
        }
    }

    /**
     * Queries all folders containing videos.
     * Groups videos by parent folder and counts videos per folder.
     * @return List of VideoFolderDto sorted by video count (descending)
     */
    suspend fun queryFolders(): List<VideoFolderDto> = withContext(Dispatchers.IO) {
        val folderMap = mutableMapOf<String, MutableList<VideoDto>>()

        val videos = queryVideos()
        videos.forEach { video ->
            val folderPath = video.path.substringBeforeLast("/", "")
            if (folderPath.isNotEmpty()) {
                folderMap.getOrPut(folderPath) { mutableListOf() }.add(video)
            }
        }

        folderMap.map { (path, videosInFolder) ->
            VideoFolderDto(
                name = videosInFolder.firstOrNull()?.folderName ?: "Unknown",
                path = path,
                videoCount = videosInFolder.size
            )
        }.sortedByDescending { it.videoCount }
    }

    /**
     * Deletes a video using its content URI.
     * @param uri The content URI of the video to delete
     * @return true if deletion was successful, false otherwise
     */
    suspend fun deleteVideo(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                contentResolver.delete(uri, null, null) > 0
            } else {
                contentResolver.delete(uri, null, null) > 0
            }
        } catch (e: Exception) {
            false
        }
    }

    fun createDeleteRequestIntent(uris: List<Uri>): android.app.PendingIntent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            MediaStore.createDeleteRequest(contentResolver, uris)
        } else {
            null
        }
    }

    fun hasReadMediaPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(android.Manifest.permission.READ_MEDIA_VIDEO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }
}
