package com.vidremover.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.vidremover.R
import com.vidremover.domain.model.DuplicateGroup
import com.vidremover.domain.model.ScanProgress
import com.vidremover.domain.model.Video
import com.vidremover.data.repository.VideoRepository
import com.vidremover.domain.usecase.DetectDuplicatesUseCase
import com.vidremover.presentation.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ScanForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var notificationManager: NotificationManager
    
    private var repository: VideoRepository? = null
    private var detectDuplicatesUseCase: DetectDuplicatesUseCase? = null

    private val _scanProgress = MutableStateFlow(ScanProgress(0, 0, "", false))
    val scanProgress: StateFlow<ScanProgress> = _scanProgress.asStateFlow()

    private val _duplicateGroups = MutableStateFlow<List<DuplicateGroup>>(emptyList())
    val duplicateGroups: StateFlow<List<DuplicateGroup>> = _duplicateGroups.asStateFlow()

    private var scanJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val folderPaths = intent.getStringArrayListExtra(EXTRA_FOLDERS) ?: emptyList()
                val scanAll = intent.getBooleanExtra(EXTRA_SCAN_ALL, true)
                val mode = intent.getStringExtra(EXTRA_DETECTION_MODE) ?: "MD5_ONLY"
                startScan(folderPaths, scanAll, mode)
            }
            ACTION_CANCEL -> cancelScan()
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Video Scanning",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows scan progress"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startScan(folderPaths: List<String>, scanAll: Boolean, mode: String) {
        val notification = createNotification(0, 0, "Starting...")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.app.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        scanJob = serviceScope.launch {
            try {
                val videos = if (scanAll) {
                    repository?.getAllVideos() ?: emptyList()
                } else {
                    repository?.getVideosInFolders(folderPaths) ?: emptyList()
                }

                _scanProgress.value = ScanProgress(0, videos.size, "Starting scan...", false)

                val detectionMode = when (mode) {
                    "PHASH_ONLY" -> com.vidremover.domain.usecase.DetectionMode.PHASH_ONLY
                    "BOTH" -> com.vidremover.domain.usecase.DetectionMode.BOTH
                    else -> com.vidremover.domain.usecase.DetectionMode.MD5_ONLY
                }

                detectDuplicatesUseCase?.invoke(videos, detectionMode, 0.9f)?.collect { groups ->
                    _duplicateGroups.value = groups
                    
                    val progress = _scanProgress.value
                    _scanProgress.value = progress.copy(
                        current = progress.current + 1,
                        isComplete = progress.current >= progress.total
                    )
                    
                    updateNotification(progress.current, progress.total, progress.currentFile)
                }

                _scanProgress.value = _scanProgress.value.copy(isComplete = true)
                updateNotification(_scanProgress.value.total, _scanProgress.value.total, "Complete!")
                
            } catch (e: Exception) {
                _scanProgress.value = _scanProgress.value.copy(
                    currentFile = "Error: ${e.message}"
                )
            }
        }
    }

    private fun cancelScan() {
        scanJob?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotification(current: Int, total: Int, file: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val progress = if (total > 0) (current * 100) / total else 0
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Scanning Videos")
            .setContentText("$file ($current/$total)")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setProgress(100, progress, false)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(current: Int, total: Int, file: String) {
        val notification = createNotification(current, total, file)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    companion object {
        const val CHANNEL_ID = "scan_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.vidremover.ACTION_START"
        const val ACTION_CANCEL = "com.vidremover.ACTION_CANCEL"
        const val EXTRA_FOLDERS = "folders"
        const val EXTRA_SCAN_ALL = "scan_all"
        const val EXTRA_DETECTION_MODE = "detection_mode"

        fun createStartIntent(
            context: Context,
            folders: List<String>,
            scanAll: Boolean,
            mode: String
        ): Intent {
            return Intent(context, ScanForegroundService::class.java).apply {
                action = ACTION_START
                putStringArrayListExtra(EXTRA_FOLDERS, ArrayList(folders))
                putBooleanExtra(EXTRA_SCAN_ALL, scanAll)
                putStringExtra(EXTRA_DETECTION_MODE, mode)
            }
        }

        fun createCancelIntent(context: Context): Intent {
            return Intent(context, ScanForegroundService::class.java).apply {
                action = ACTION_CANCEL
            }
        }
    }
}