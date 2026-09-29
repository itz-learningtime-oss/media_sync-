package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.MediaSyncApp
import com.example.data.model.AutoSyncMode
import com.example.data.model.DetectedMedia
import com.example.data.model.ServerConfig
import com.example.data.model.TransferLog
import com.example.data.model.TransferProtocol
import com.example.network.TransferResult
import com.example.service.MediaObserverService
import com.example.service.NotificationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as MediaSyncApp).repository

    val isServiceRunning: StateFlow<Boolean> = MediaObserverService.isRunning
    val eventCount: StateFlow<Int> = MediaObserverService.eventCount
    val lastDetected: StateFlow<DetectedMedia?> = MediaObserverService.lastDetected
    val serviceStartTime: StateFlow<Long> = MediaObserverService.serviceStartTime
    val serverIpAddress: StateFlow<String> = MediaObserverService.serverIpAddress

    val serverConfig: StateFlow<ServerConfig> = repository.serverConfig

    val detectedMediaList: StateFlow<List<DetectedMedia>> = repository.allDetectedMedia
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val transferLogs: StateFlow<List<TransferLog>> = repository.allTransfers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val detectedTotalCount: StateFlow<Int> = repository.detectedCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val httpSuccessCount: StateFlow<Int> = repository.httpSuccessCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val ftpSuccessCount: StateFlow<Int> = repository.ftpSuccessCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _isTestingConnection = MutableStateFlow(false)
    val isTestingConnection: StateFlow<Boolean> = _isTestingConnection.asStateFlow()

    private val _connectionStatus = MutableStateFlow<String?>(null)
    val connectionStatus: StateFlow<String?> = _connectionStatus.asStateFlow()

    private val _selectedMediaForDetail = MutableStateFlow<DetectedMedia?>(null)
    val selectedMediaForDetail: StateFlow<DetectedMedia?> = _selectedMediaForDetail.asStateFlow()

    private val _showCompanionScriptDialog = MutableStateFlow(false)
    val showCompanionScriptDialog: StateFlow<Boolean> = _showCompanionScriptDialog.asStateFlow()

    fun startService(context: Context) {
        val intent = Intent(context, MediaObserverService::class.java).apply {
            action = MediaObserverService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stopService(context: Context) {
        val intent = Intent(context, MediaObserverService::class.java).apply {
            action = MediaObserverService.ACTION_STOP
        }
        context.startService(intent)
    }

    fun triggerScan(context: Context) {
        val intent = Intent(context, MediaObserverService::class.java).apply {
            action = MediaObserverService.ACTION_TRIGGER_SCAN
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun updateConfig(config: ServerConfig) {
        repository.updateConfig(config)
    }

    fun testConnection() {
        viewModelScope.launch {
            _isTestingConnection.value = true
            _connectionStatus.value = null
            val isSuccess = repository.testConnection()
            _isTestingConnection.value = false
            _connectionStatus.value = if (isSuccess) {
                "✅ Successfully reached PC at ${serverConfig.value.pcHostIp}:${serverConfig.value.httpPort}"
            } else {
                "⚠️ Could not reach HTTP server at ${serverConfig.value.pcHostIp}:${serverConfig.value.httpPort}. Ensure Python server is running!"
            }
        }
    }

    fun clearConnectionStatus() {
        _connectionStatus.value = null
    }

    fun selectMediaDetail(media: DetectedMedia?) {
        _selectedMediaForDetail.value = media
    }

    fun setShowCompanionScript(show: Boolean) {
        _showCompanionScriptDialog.value = show
    }

    fun clearHistory() {
        viewModelScope.launch {
            repository.clearHistory()
        }
    }

    fun clearDetected() {
        viewModelScope.launch {
            repository.clearDetectedMedia()
        }
    }

    fun transferMedia(media: DetectedMedia, protocol: TransferProtocol) {
        viewModelScope.launch {
            val fileUri = Uri.parse(media.contentUriString)
            Toast.makeText(
                getApplication(),
                "Initiating ${protocol.name} transfer for ${media.displayName}...",
                Toast.LENGTH_SHORT
            ).show()

            val result = repository.transferFile(
                fileUri = fileUri,
                fileName = media.displayName,
                protocol = protocol,
                mimeType = media.mimeType
            )

            when (result) {
                is TransferResult.Success -> {
                    NotificationHelper.showTransferStatusNotification(
                        context = getApplication(),
                        fileName = media.displayName,
                        protocol = protocol,
                        isSuccess = true,
                        message = "Transferred ${result.bytesSent} bytes in ${result.durationMs}ms"
                    )
                    Toast.makeText(
                        getApplication(),
                        "✅ ${protocol.name} transfer completed!",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                is TransferResult.Error -> {
                    NotificationHelper.showTransferStatusNotification(
                        context = getApplication(),
                        fileName = media.displayName,
                        protocol = protocol,
                        isSuccess = false,
                        message = result.message
                    )
                    Toast.makeText(
                        getApplication(),
                        "❌ ${protocol.name} failed: ${result.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /**
     * Simulates a media event (e.g. Screenshot) to demonstrate and verify the
     * ContentObserver, system Heads-up notification with HTTP & FTP buttons, and database flow.
     */
    fun simulateScreenshotEvent() {
        viewModelScope.launch {
            val fakeId = System.currentTimeMillis()
            val fakeScreenshot = DetectedMedia(
                mediaStoreId = fakeId,
                contentUriString = "content://media/external/images/media/$fakeId",
                displayName = "Screenshot_${System.currentTimeMillis()}.png",
                filePath = "/storage/emulated/0/Pictures/Screenshots/Screenshot_${System.currentTimeMillis()}.png",
                sizeBytes = 2_450_000L,
                mimeType = "image/png",
                dateAddedSeconds = System.currentTimeMillis() / 1000,
                isScreenshot = true,
                bucketName = "Screenshots"
            )

            repository.recordDetectedMedia(fakeScreenshot)

            NotificationHelper.showMediaDetectedNotification(
                context = getApplication(),
                media = fakeScreenshot,
                thumbnailBitmap = null
            )

            Toast.makeText(
                getApplication(),
                "📸 Simulated screenshot detected! Check your notification shade.",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
