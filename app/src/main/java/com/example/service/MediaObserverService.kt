package com.example.service

import android.app.Service
import android.content.ContentUris
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.example.MediaSyncApp
import com.example.data.model.AutoSyncMode
import com.example.data.model.DetectedMedia
import com.example.data.model.TransferProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MediaObserverService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val handler = Handler(Looper.getMainLooper())
    private var mediaObserver: ContentObserver? = null

    // Embedded HTTP Server to receive incoming transfers from PC
    private var embeddedServer: EmbeddedReceiverServer? = null

    private var lastProcessedMediaId: Long = -1L
    private var lastProcessedTimestamp: Long = 0L
    private val debounceMutex = Mutex()

    companion object {
        private const val TAG = "MediaObserverService"

        const val ACTION_START = "com.aistudio.mediasync.service.ACTION_START"
        const val ACTION_STOP = "com.aistudio.mediasync.service.ACTION_STOP"
        const val ACTION_TRIGGER_SCAN = "com.aistudio.mediasync.service.ACTION_TRIGGER_SCAN"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _eventCount = MutableStateFlow(0)
        val eventCount: StateFlow<Int> = _eventCount.asStateFlow()

        private val _lastDetected = MutableStateFlow<DetectedMedia?>(null)
        val lastDetected: StateFlow<DetectedMedia?> = _lastDetected.asStateFlow()

        private val _serviceStartTime = MutableStateFlow(0L)
        val serviceStartTime: StateFlow<Long> = _serviceStartTime.asStateFlow()

        private val _serverIpAddress = MutableStateFlow("127.0.0.1")
        val serverIpAddress: StateFlow<String> = _serverIpAddress.asStateFlow()
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "MediaObserverService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        when (action) {
            ACTION_STOP -> {
                stopObserverService()
                return START_NOT_STICKY
            }
            ACTION_TRIGGER_SCAN -> {
                serviceScope.launch {
                    processLatestImage(forceNotify = true)
                }
                return START_STICKY
            }
            ACTION_START -> {
                startObserverService()
            }
        }

        return START_STICKY
    }

    private fun startObserverService() {
        if (_isRunning.value) {
            Log.d(TAG, "Service already running")
            return
        }

        val notification = NotificationHelper.buildForegroundNotification(this, _eventCount.value)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        NotificationHelper.NOTIFICATION_ID_FOREGROUND,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } else {
                    startForeground(
                        NotificationHelper.NOTIFICATION_ID_FOREGROUND,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                }
            } else {
                startForeground(NotificationHelper.NOTIFICATION_ID_FOREGROUND, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
            startForeground(NotificationHelper.NOTIFICATION_ID_FOREGROUND, notification)
        }

        // 1. Register MediaStore ContentObserver for detecting photos/screenshots
        registerMediaStoreObserver()

        // 2. Start Embedded HTTP Server on Port 8080 for receiving files from PC
        startEmbeddedHttpServer()

        _isRunning.value = true
        if (_serviceStartTime.value == 0L) {
            _serviceStartTime.value = System.currentTimeMillis()
        }
        Log.i(TAG, "MediaObserverService started foreground monitoring & embedded HTTP server")
    }

    private fun startEmbeddedHttpServer() {
        if (embeddedServer == null) {
            embeddedServer = EmbeddedReceiverServer(
                context = applicationContext,
                port = 8080,
                scope = serviceScope
            ).apply {
                start()
                _serverIpAddress.value = getLocalIpAddress()
            }
            Log.i(TAG, "Embedded Receiver Server active on port 8080 (IP: ${_serverIpAddress.value})")
        }
    }

    private fun stopEmbeddedHttpServer() {
        embeddedServer?.stop()
        embeddedServer = null
    }

    private fun registerMediaStoreObserver() {
        if (mediaObserver != null) return

        mediaObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                handleMediaChange(null)
            }

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                handleMediaChange(uri)
            }
        }

        try {
            contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                mediaObserver!!
            )
            Log.d(TAG, "Successfully registered ContentObserver on MediaStore.Images.Media.EXTERNAL_CONTENT_URI")
        } catch (e: Exception) {
            Log.e(TAG, "Error registering MediaStore observer", e)
        }
    }

    private fun handleMediaChange(targetUri: Uri?) {
        serviceScope.launch {
            // Small delay to allow file system write to settle and avoid 0-byte reads
            delay(350)
            processLatestImage(targetUri = targetUri, forceNotify = false)
        }
    }

    /**
     * Queries MediaStore for the newest image or screenshot and triggers heads-up notification.
     */
    suspend fun processLatestImage(targetUri: Uri? = null, forceNotify: Boolean = false) {
        debounceMutex.withLock {
            try {
                val projection = mutableListOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATA,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.MIME_TYPE,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.DATE_MODIFIED,
                    MediaStore.Images.Media.BUCKET_DISPLAY_NAME
                )

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    projection.add(MediaStore.Images.Media.RELATIVE_PATH)
                }

                val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC"

                val cursor: Cursor? = if (targetUri != null && targetUri != MediaStore.Images.Media.EXTERNAL_CONTENT_URI) {
                    contentResolver.query(targetUri, projection.toTypedArray(), null, null, null)
                } else {
                    contentResolver.query(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        projection.toTypedArray(),
                        null,
                        null,
                        sortOrder
                    )
                }

                cursor?.use { c ->
                    if (c.moveToFirst()) {
                        val idIndex = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                        val nameIndex = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                        val dataIndex = c.getColumnIndex(MediaStore.Images.Media.DATA)
                        val sizeIndex = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                        val mimeIndex = c.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
                        val dateAddedIndex = c.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                        val bucketIndex = c.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)

                        val mediaId = c.getLong(idIndex)
                        val displayName = c.getString(nameIndex) ?: "IMG_${System.currentTimeMillis()}.jpg"
                        val filePath = if (dataIndex >= 0) c.getString(dataIndex) else null
                        val sizeBytes = c.getLong(sizeIndex)
                        val mimeType = if (mimeIndex >= 0) c.getString(mimeIndex) else "image/jpeg"
                        val dateAdded = if (dateAddedIndex >= 0) c.getLong(dateAddedIndex) else System.currentTimeMillis() / 1000
                        val bucketName = if (bucketIndex >= 0) c.getString(bucketIndex) else ""

                        var relativePath = ""
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val relIndex = c.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                            if (relIndex >= 0) {
                                relativePath = c.getString(relIndex) ?: ""
                            }
                        }

                        // Deduplication: Skip if already processed in last 2.5 seconds with same ID
                        val now = System.currentTimeMillis()
                        if (!forceNotify && mediaId == lastProcessedMediaId && (now - lastProcessedTimestamp) < 2500) {
                            Log.d(TAG, "Duplicate event skipped for media ID: $mediaId")
                            return
                        }

                        lastProcessedMediaId = mediaId
                        lastProcessedTimestamp = now

                        val contentUri = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            mediaId
                        )

                        val isScreenshot = bucketName.contains("screenshot", ignoreCase = true) ||
                                displayName.contains("screenshot", ignoreCase = true) ||
                                displayName.contains("Screenshot", ignoreCase = false) ||
                                relativePath.contains("screenshot", ignoreCase = true)

                        val detectedMedia = DetectedMedia(
                            mediaStoreId = mediaId,
                            contentUriString = contentUri.toString(),
                            displayName = displayName,
                            filePath = filePath,
                            sizeBytes = sizeBytes,
                            mimeType = mimeType,
                            dateAddedSeconds = dateAdded,
                            isScreenshot = isScreenshot,
                            bucketName = bucketName
                        )

                        // Save to Room DB & StateFlow
                        val repository = MediaSyncApp.instance.repository
                        repository.recordDetectedMedia(detectedMedia)

                        _eventCount.value += 1
                        _lastDetected.value = detectedMedia

                        // Load thumbnail bitmap for heads-up notification preview
                        val thumbnail = NotificationHelper.loadThumbnail(this@MediaObserverService, contentUri)

                        // Trigger the Heads-up system notification with HTTP & FTP buttons
                        NotificationHelper.showMediaDetectedNotification(
                            context = this@MediaObserverService,
                            media = detectedMedia,
                            thumbnailBitmap = thumbnail
                        )

                        // Update ongoing foreground service status notification with count
                        val updatedForegroundNotification =
                            NotificationHelper.buildForegroundNotification(this@MediaObserverService, _eventCount.value)
                        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                        notificationManager.notify(
                            NotificationHelper.NOTIFICATION_ID_FOREGROUND,
                            updatedForegroundNotification
                        )

                        // Handle Auto-Sync if configured
                        when (repository.serverConfig.value.autoSyncMode) {
                            AutoSyncMode.AUTO_HTTP -> {
                                repository.transferFile(
                                    fileUri = contentUri,
                                    fileName = displayName,
                                    protocol = TransferProtocol.HTTP,
                                    mimeType = mimeType
                                )
                            }
                            AutoSyncMode.AUTO_FTP -> {
                                repository.transferFile(
                                    fileUri = contentUri,
                                    fileName = displayName,
                                    protocol = TransferProtocol.FTP,
                                    mimeType = mimeType
                                )
                            }
                            AutoSyncMode.NOTIFICATION_CHOICE -> {
                                // Default mode: User chooses via heads-up notification or in-app dialog
                            }
                        }

                        Log.i(TAG, "Processed new media: $displayName ($contentUri), isScreenshot: $isScreenshot")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing MediaStore query", e)
            }
        }
    }

    private fun stopObserverService() {
        try {
            if (mediaObserver != null) {
                contentResolver.unregisterContentObserver(mediaObserver!!)
                mediaObserver = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering observer", e)
        }

        stopEmbeddedHttpServer()

        _isRunning.value = false
        _serviceStartTime.value = 0L

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
        Log.i(TAG, "MediaObserverService stopped")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopObserverService()
        serviceScope.cancel()
        Log.d(TAG, "MediaObserverService onDestroy")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
