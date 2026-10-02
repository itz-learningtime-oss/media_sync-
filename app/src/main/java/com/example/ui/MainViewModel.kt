package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.MediaSyncApp
import com.example.data.model.AutoSyncMode
import com.example.data.model.ConnectedPeer
import com.example.data.model.DetectedMedia
import com.example.data.model.ServerConfig
import com.example.data.model.TransferLog
import com.example.data.model.TransferProtocol
import com.example.network.TransferResult
import com.example.service.AppUpdateManager
import com.example.service.AppVersionInfo
import com.example.service.EmbeddedReceiverServer
import com.example.service.EmbeddedServerManager
import com.example.service.MediaObserverService
import com.example.service.MultiPeerTransferEngine
import com.example.service.NotificationHelper
import com.example.service.PackageInstallerHelper
import com.example.service.PeerManager
import com.example.service.ReceivedFileItem
import com.example.service.ReceivedFileRegistry
import com.example.service.SharedFileItem
import com.example.service.SharedFileRegistry
import com.example.service.TransferSecurityManager
import com.example.util.ApkSharingHelper
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
    val serverIpAddress: StateFlow<String> = EmbeddedServerManager.serverIp

    val serverConfig: StateFlow<ServerConfig> = repository.serverConfig

    val detectedMediaList: StateFlow<List<DetectedMedia>> = repository.allDetectedMedia
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val transferLogs: StateFlow<List<TransferLog>> = repository.allTransfers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val sharedFiles: StateFlow<List<SharedFileItem>> = SharedFileRegistry.sharedFiles
    val receivedFiles: StateFlow<List<ReceivedFileItem>> = ReceivedFileRegistry.receivedFiles

    val pinCode: StateFlow<String> = TransferSecurityManager.pinCode
    val authToken: StateFlow<String> = TransferSecurityManager.authToken
    val isPinRequired: StateFlow<Boolean> = TransferSecurityManager.isPinRequired

    fun regenerateSecurityPin(): String = TransferSecurityManager.regeneratePin()
    fun setSecurityPinRequired(required: Boolean) = TransferSecurityManager.setPinRequired(required)

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

    private val _showQrPairingDialog = MutableStateFlow(false)
    val showQrPairingDialog: StateFlow<Boolean> = _showQrPairingDialog.asStateFlow()

    // Connected local peers on the common Wi-Fi / Hotspot connection point
    val peers: StateFlow<List<ConnectedPeer>> = PeerManager.peers
    val isDiscoveringPeers: StateFlow<Boolean> = PeerManager.isDiscovering
    val discoveryProgress: StateFlow<Float> = PeerManager.discoveryProgress

    private val _showMultiPeerShareDialog = MutableStateFlow(false)
    val showMultiPeerShareDialog: StateFlow<Boolean> = _showMultiPeerShareDialog.asStateFlow()

    private val _isBroadcasting = MutableStateFlow(false)
    val isBroadcasting: StateFlow<Boolean> = _isBroadcasting.asStateFlow()

    val currentAppVersion: StateFlow<AppVersionInfo> = MutableStateFlow(AppUpdateManager.getVersionInfo(application))

    private val _availableUpdate = MutableStateFlow<AppVersionInfo?>(null)
    val availableUpdate: StateFlow<AppVersionInfo?> = _availableUpdate.asStateFlow()

    private val _isDownloadingUpdate = MutableStateFlow(false)
    val isDownloadingUpdate: StateFlow<Boolean> = _isDownloadingUpdate.asStateFlow()

    private val _updateDownloadProgress = MutableStateFlow(0f)
    val updateDownloadProgress: StateFlow<Float> = _updateDownloadProgress.asStateFlow()

    private val _updatePeerBaseUrl = MutableStateFlow<String?>(null)

    fun checkForPeerAppUpdate(peerHost: String, port: Int = 8080, authToken: String? = null, showUpToDateToast: Boolean = false) {
        if (peerHost.isBlank() || peerHost == "127.0.0.1" || peerHost == "localhost") return
        viewModelScope.launch {
            val localInfo = AppUpdateManager.getVersionInfo(getApplication())
            val portsToCheck = if (port == 8080) listOf(8080) else listOf(port, 8080)
            var found = false
            for (p in portsToCheck) {
                val baseUrl = "http://$peerHost:$p"
                val remoteInfo = AppUpdateManager.checkRemoteVersion(baseUrl, authToken)
                if (remoteInfo != null) {
                    found = true
                    if (remoteInfo.versionCode > localInfo.versionCode) {
                        _updatePeerBaseUrl.value = baseUrl
                        _availableUpdate.value = remoteInfo
                        Log.i("MainViewModel", "Update available from $baseUrl: v${remoteInfo.versionName} (#${remoteInfo.versionCode}) vs local #${localInfo.versionCode}")
                    } else if (showUpToDateToast) {
                        Toast.makeText(getApplication(), "MediaSync is up to date (v${localInfo.versionName})", Toast.LENGTH_SHORT).show()
                    }
                    break
                }
            }
            if (!found && showUpToDateToast) {
                Toast.makeText(getApplication(), "No peer update server found at $peerHost", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun dismissUpdateDialog() {
        _availableUpdate.value = null
    }

    fun downloadAndInstallUpdate(context: Context, authToken: String? = null) {
        val update = _availableUpdate.value ?: return
        val baseUrl = _updatePeerBaseUrl.value ?: return
        val downloadUrl = "$baseUrl/download/latest-apk"

        viewModelScope.launch {
            _isDownloadingUpdate.value = true
            _updateDownloadProgress.value = 0f
            val downloadedFile = AppUpdateManager.downloadLatestApk(
                context = context,
                downloadUrl = downloadUrl,
                authToken = authToken,
                onProgress = { progress -> _updateDownloadProgress.value = progress }
            )
            _isDownloadingUpdate.value = false

            if (downloadedFile != null && downloadedFile.exists()) {
                _availableUpdate.value = null
                PackageInstallerHelper.installApk(context, downloadedFile)
            } else {
                Toast.makeText(context, "Failed to download update APK", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun openMultiPeerShareDialog() {
        _showMultiPeerShareDialog.value = true
    }

    fun closeMultiPeerShareDialog() {
        _showMultiPeerShareDialog.value = false
    }

    fun discoverPeers(context: Context) {
        Toast.makeText(context, "Scanning local network for nearby phones...", Toast.LENGTH_SHORT).show()
        PeerManager.discoverLocalPeers(context) { peers ->
            val onlineCount = peers.count { it.isOnline }
            if (onlineCount > 0) {
                Toast.makeText(context, "✓ Found $onlineCount online device${if (onlineCount > 1) "s" else ""}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "No other phones detected. Ensure both are on the same Wi-Fi or Hotspot.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun togglePeerSelection(id: String) {
        PeerManager.togglePeerSelection(id)
    }

    fun selectAllPeers(select: Boolean) {
        PeerManager.selectAll(select)
    }

    fun addManualPeer(ip: String, port: Int = 8080) {
        PeerManager.addOrUpdatePeer(
            deviceName = "Peer ($ip)",
            ipAddress = ip,
            port = port
        )
    }

    fun removePeer(id: String) {
        PeerManager.removePeer(id)
    }

    fun broadcastFiles(
        context: Context,
        targetPeers: List<ConnectedPeer>? = null,
        customUris: List<Uri>? = null
    ) {
        val uris = customUris ?: run {
            val all = SharedFileRegistry.sharedFiles.value
            val nonApk = all.filter { !it.isApk }
            // If user has other files shared, don't bundle APK unless it's the only file
            val filesToUse = if (nonApk.isNotEmpty()) nonApk else all
            filesToUse.map { it.uri }
        }
        val targets = targetPeers ?: PeerManager.peers.value.filter { it.isSelected }

        if (uris.isEmpty()) {
            Toast.makeText(context, "No files selected yet to broadcast", Toast.LENGTH_SHORT).show()
            return
        }
        if (targets.isEmpty()) {
            Toast.makeText(context, "Please select at least one connected phone", Toast.LENGTH_SHORT).show()
            return
        }

        viewModelScope.launch {
            _isBroadcasting.value = true
            val summary = MultiPeerTransferEngine.broadcastFiles(
                context = context,
                fileUris = uris,
                targetPeers = targets,
                onLogCreated = { log ->
                    viewModelScope.launch {
                        repository.recordTransferLog(log)
                    }
                }
            )
            _isBroadcasting.value = false

            if (summary.successfulPeers > 0) {
                Toast.makeText(
                    context,
                    "✓ Broadcasted ${summary.totalFiles} files to ${summary.successfulPeers} mobile phones simultaneously!",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(context, "Broadcast failed. Check peer connection.", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun openQrDialog() {
        _showQrPairingDialog.value = true
    }

    fun closeQrDialog() {
        _showQrPairingDialog.value = false
    }

    fun openCompanionScriptDialog() {
        _showCompanionScriptDialog.value = true
    }

    fun closeCompanionScriptDialog() {
        _showCompanionScriptDialog.value = false
    }

    init {
        SharedFileRegistry.ensureApkRegistered(application)
        EmbeddedServerManager.startServer(application)
        PeerManager.loadSavedPeers(application)
        PeerManager.discoverLocalPeers(application)
    }

    fun refreshNetworkIp(context: Context) {
        EmbeddedServerManager.refreshIp(context)
    }

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
            if (isSuccess) {
                checkForPeerAppUpdate(serverConfig.value.pcHostIp, serverConfig.value.httpPort)
            }
        }
    }

    fun clearConnectionStatus() {
        _connectionStatus.value = null
    }

    suspend fun testPing(hostIp: String, port: Int): Pair<Boolean, String> {
        return repository.testPing(hostIp, port)
    }

    fun selectMediaDetail(media: DetectedMedia?) {
        _selectedMediaForDetail.value = media
    }

    fun setShowCompanionScript(show: Boolean) {
        _showCompanionScriptDialog.value = show
    }

    fun setShowQrPairing(show: Boolean) {
        _showQrPairingDialog.value = show
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

    /**
     * Registers picked documents/files, adding them to both the Web Portal
     * and the live media transfer feed.
     */
    fun registerPickedDocuments(uris: List<Uri>, context: Context) {
        viewModelScope.launch {
            var count = 0
            for (uri in uris) {
                val item = SharedFileRegistry.registerFromUri(context, uri)
                if (item != null) {
                    count++
                    val detectedItem = DetectedMedia(
                        mediaStoreId = System.currentTimeMillis() + count,
                        contentUriString = item.uri.toString(),
                        displayName = item.name,
                        filePath = null,
                        sizeBytes = item.sizeBytes,
                        mimeType = item.mimeType,
                        dateAddedSeconds = System.currentTimeMillis() / 1000,
                        isScreenshot = false,
                        bucketName = if (item.isApk) "APKs" else "Documents"
                    )
                    repository.recordDetectedMedia(detectedItem)
                }
            }
            if (count > 0) {
                Toast.makeText(context, "Added $count file(s) for sharing & download!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Shares the app APK via Android's native share sheet.
     */
    fun shareApkViaSystem(context: Context) {
        try {
            val intent = ApkSharingHelper.createShareApkIntent(context)
            val chooser = Intent.createChooser(intent, "Share MediaSync App (APK)")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(context, "Cannot share APK: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Sends the APK file directly to PC or another Phone via HTTP or FTP.
     */
    fun transferApk(protocol: TransferProtocol) {
        val apkFile = ApkSharingHelper.getShareableApkFile(getApplication())
        val apkMedia = DetectedMedia(
            mediaStoreId = System.currentTimeMillis(),
            contentUriString = Uri.fromFile(apkFile).toString(),
            displayName = "MediaSync.apk",
            filePath = apkFile.absolutePath,
            sizeBytes = apkFile.length(),
            mimeType = "application/vnd.android.package-archive",
            dateAddedSeconds = System.currentTimeMillis() / 1000,
            isScreenshot = false,
            bucketName = "Apps"
        )
        transferMedia(apkMedia, protocol)
    }

    fun saveReceivedFileToGallery(context: Context, item: ReceivedFileItem) {
        ReceivedFileRegistry.saveToGallery(context, item)
    }

    fun dismissReceivedFile(item: ReceivedFileItem) {
        ReceivedFileRegistry.removeReceivedFile(item.id)
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
