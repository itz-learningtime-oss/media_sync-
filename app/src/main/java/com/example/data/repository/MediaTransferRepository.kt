package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.example.data.db.DetectedMediaDao
import com.example.data.db.TransferDao
import com.example.data.model.AutoSyncMode
import com.example.data.model.DetectedMedia
import com.example.data.model.ServerConfig
import com.example.data.model.TransferLog
import com.example.data.model.TransferProtocol
import com.example.data.model.TransferStatus
import com.example.network.FtpTransferClient
import com.example.network.HttpTransferClient
import com.example.network.TransferResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class MediaTransferRepository(
    private val context: Context,
    private val detectedMediaDao: DetectedMediaDao,
    private val transferDao: TransferDao
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("media_sync_config", Context.MODE_PRIVATE)

    private val httpClient = HttpTransferClient(context)
    private val ftpClient = FtpTransferClient(context)

    private val _serverConfig = MutableStateFlow(loadConfig())
    val serverConfig: StateFlow<ServerConfig> = _serverConfig.asStateFlow()

    val allDetectedMedia: Flow<List<DetectedMedia>> = detectedMediaDao.getAllDetectedMedia()
    val allTransfers: Flow<List<TransferLog>> = transferDao.getAllTransfers()
    val detectedCount: Flow<Int> = detectedMediaDao.getDetectedCount()
    val httpSuccessCount: Flow<Int> = transferDao.getHttpSuccessCount()
    val ftpSuccessCount: Flow<Int> = transferDao.getFtpSuccessCount()

    private fun loadConfig(): ServerConfig {
        val host = prefs.getString("pc_host", "192.168.1.100") ?: "192.168.1.100"
        val httpPort = prefs.getInt("http_port", 8000)
        val httpEndpoint = prefs.getString("http_endpoint", "/upload") ?: "/upload"
        val ftpPort = prefs.getInt("ftp_port", 2121)
        val ftpUser = prefs.getString("ftp_user", "user") ?: "user"
        val ftpPass = prefs.getString("ftp_pass", "password") ?: "password"
        val modeStr = prefs.getString("auto_sync_mode", AutoSyncMode.NOTIFICATION_CHOICE.name)
        val autoMode = try {
            AutoSyncMode.valueOf(modeStr ?: AutoSyncMode.NOTIFICATION_CHOICE.name)
        } catch (e: Exception) {
            AutoSyncMode.NOTIFICATION_CHOICE
        }

        return ServerConfig(
            pcHostIp = host,
            httpPort = httpPort,
            httpEndpoint = httpEndpoint,
            ftpPort = ftpPort,
            ftpUsername = ftpUser,
            ftpPassword = ftpPass,
            autoSyncMode = autoMode
        )
    }

    fun updateConfig(config: ServerConfig) {
        prefs.edit()
            .putString("pc_host", config.pcHostIp)
            .putInt("http_port", config.httpPort)
            .putString("http_endpoint", config.httpEndpoint)
            .putInt("ftp_port", config.ftpPort)
            .putString("ftp_user", config.ftpUsername)
            .putString("ftp_pass", config.ftpPassword)
            .putString("auto_sync_mode", config.autoSyncMode.name)
            .apply()
        _serverConfig.value = config
    }

    suspend fun recordDetectedMedia(media: DetectedMedia): Long = withContext(Dispatchers.IO) {
        val existing = detectedMediaDao.getByMediaStoreId(media.mediaStoreId)
        if (existing == null) {
            detectedMediaDao.insert(media)
        } else {
            existing.id
        }
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        transferDao.clearHistory()
    }

    suspend fun clearDetectedMedia() = withContext(Dispatchers.IO) {
        detectedMediaDao.clearAll()
    }

    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        val config = _serverConfig.value
        httpClient.pingServer(config.pcHostIp, config.httpPort)
    }

    suspend fun transferFile(
        fileUri: Uri,
        fileName: String,
        protocol: TransferProtocol,
        mimeType: String? = null
    ): TransferResult = withContext(Dispatchers.IO) {
        val config = _serverConfig.value
        val port = if (protocol == TransferProtocol.HTTP) config.httpPort else config.ftpPort

        val logId = transferDao.insert(
            TransferLog(
                fileName = fileName,
                fileUriString = fileUri.toString(),
                protocol = protocol,
                targetIp = config.pcHostIp,
                targetPort = port,
                status = TransferStatus.IN_PROGRESS
            )
        )

        val result = if (protocol == TransferProtocol.HTTP) {
            httpClient.uploadFile(
                hostIp = config.pcHostIp,
                port = config.httpPort,
                endpoint = config.httpEndpoint,
                fileUri = fileUri,
                fileName = fileName,
                mimeType = mimeType
            )
        } else {
            ftpClient.uploadFile(
                hostIp = config.pcHostIp,
                port = config.ftpPort,
                user = config.ftpUsername,
                pass = config.ftpPassword,
                fileUri = fileUri,
                fileName = fileName
            )
        }

        when (result) {
            is TransferResult.Success -> {
                transferDao.update(
                    TransferLog(
                        id = logId,
                        fileName = fileName,
                        fileUriString = fileUri.toString(),
                        protocol = protocol,
                        targetIp = config.pcHostIp,
                        targetPort = port,
                        status = TransferStatus.SUCCESS,
                        sizeBytes = result.bytesSent,
                        durationMs = result.durationMs,
                        errorMessage = null
                    )
                )
            }
            is TransferResult.Error -> {
                transferDao.update(
                    TransferLog(
                        id = logId,
                        fileName = fileName,
                        fileUriString = fileUri.toString(),
                        protocol = protocol,
                        targetIp = config.pcHostIp,
                        targetPort = port,
                        status = TransferStatus.FAILED,
                        errorMessage = result.message
                    )
                )
            }
        }

        result
    }
}
