package com.example.service

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.data.model.ConnectedPeer
import com.example.data.model.PeerTransferStatus
import com.example.data.model.TransferLog
import com.example.data.model.TransferProtocol
import com.example.network.TransferResult
import com.example.util.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.util.concurrent.TimeUnit

data class MultiPeerTransferSummary(
    val totalFiles: Int,
    val totalPeers: Int,
    val successfulPeers: Int,
    val failedPeers: Int,
    val totalBytesTransferred: Long,
    val durationMs: Long
)

data class PreparedFile(
    val uri: Uri,
    val cleanName: String,
    val sizeBytes: Long,
    val mimeType: String
)

/**
 * Direct zero-copy RequestBody that streams straight from Android Scoped Storage / ContentResolver
 * to the network socket with 64KB high-speed buffers.
 */
class DirectUriRequestBody(
    private val context: Context,
    private val uri: Uri,
    private val length: Long,
    private val onProgress: ((bytesSent: Long, total: Long) -> Unit)? = null
) : RequestBody() {

    override fun contentType() = "application/octet-stream".toMediaTypeOrNull()

    override fun contentLength(): Long = length

    override fun writeTo(sink: BufferedSink) {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Cannot open InputStream for URI: $uri")

        java.io.BufferedInputStream(inputStream, 131072).use { stream ->
            val buffer = ByteArray(131072) // 128KB high-throughput streaming buffer
            var bytesRead: Int
            var totalWritten = 0L

            while (stream.read(buffer).also { bytesRead = it } != -1) {
                sink.write(buffer, 0, bytesRead)
                totalWritten += bytesRead
                onProgress?.invoke(totalWritten, length)
            }
            sink.flush()
        }
    }
}

object MultiPeerTransferEngine {

    private const val TAG = "MultiPeerTransferEngine"

    private val okHttpClient = OkHttpClient.Builder()
        .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.NANOSECONDS))
        .retryOnConnectionFailure(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * Broadcasts a list of file URIs to all selected connected peers in parallel.
     * Streams directly from Scoped Storage without slow intermediate disk copies.
     */
    suspend fun broadcastFiles(
        context: Context,
        fileUris: List<Uri>,
        targetPeers: List<ConnectedPeer>,
        onPeerProgress: ((peerId: String, currentFile: String, fileIndex: Int, totalFiles: Int, progress: Float) -> Unit)? = null,
        onLogCreated: ((TransferLog) -> Unit)? = null
    ): MultiPeerTransferSummary = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        var totalBytes = 0L

        if (fileUris.isEmpty() || targetPeers.isEmpty()) {
            return@withContext MultiPeerTransferSummary(
                totalFiles = fileUris.size,
                totalPeers = targetPeers.size,
                successfulPeers = 0,
                failedPeers = 0,
                totalBytesTransferred = 0L,
                durationMs = 0L
            )
        }

        // Query file metadata directly without creating slow disk cache copies
        val preparedFiles = mutableListOf<PreparedFile>()
        for (uri in fileUris) {
            val (name, size, mime) = FileUtils.queryFileInfo(context, uri)
            val cleanName = name.replace(Regex("^temp_\\d+_"), "").replace(Regex("^temp_"), "")
            val resolvedSize = if (size > 0L) size else {
                try {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
                } catch (_: Exception) { 0L }
            }
            preparedFiles.add(PreparedFile(uri, cleanName, resolvedSize, mime))
        }

        // Dispatch parallel upload jobs to all recipient peers simultaneously
        val peerResults: List<Pair<ConnectedPeer, Boolean>> = coroutineScope {
            targetPeers.map { peer ->
                async {
                    var allPeerFilesSucceeded = true
                    var peerBytes = 0L

                    // 1. Request connection and transfer approval from peer
                    PeerManager.updatePeerTransferStatus(
                        peer.id,
                        PeerTransferStatus.PREPARING,
                        0f,
                        "Asking ${peer.deviceName} to accept connection..."
                    )

                    val (accepted, reason, sessionToken) = requestPeerApproval(
                        context = context,
                        peer = peer,
                        preparedFiles = preparedFiles
                    )

                    if (!accepted) {
                        Log.w(TAG, "Connection was not accepted by ${peer.deviceName}: $reason")
                        PeerManager.updatePeerTransferStatus(
                            peer.id,
                            PeerTransferStatus.FAILED,
                            0f,
                            reason ?: "Declined by recipient"
                        )
                        onLogCreated?.invoke(
                            TransferLog(
                                fileName = "Connection Request (${preparedFiles.size} files)",
                                fileUriString = "",
                                protocol = TransferProtocol.HTTP,
                                targetIp = peer.ipAddress,
                                targetPort = peer.httpPort,
                                status = com.example.data.model.TransferStatus.FAILED,
                                sizeBytes = 0L,
                                errorMessage = reason ?: "Declined by recipient"
                            )
                        )
                        return@async peer to false
                    }

                    // Save connection on sender's device as well for future convenience
                    PeerConnectionManager.savePairedPeer(
                        context,
                        peer.copy(isPaired = true, isOnline = true, authToken = sessionToken)
                    )

                    PeerManager.updatePeerTransferStatus(
                        peer.id,
                        PeerTransferStatus.SENDING,
                        0f,
                        "✓ Connected! Starting direct high-speed transfer..."
                    )

                    for ((index, prepFile) in preparedFiles.withIndex()) {
                        val fileName = prepFile.cleanName
                        val fileLength = prepFile.sizeBytes

                        val baseProgress = (index.toFloat() / preparedFiles.size).coerceIn(0f, 1f)
                        PeerManager.updatePeerTransferStatus(
                            peer.id,
                            PeerTransferStatus.SENDING,
                            baseProgress,
                            "Streaming ($index/${preparedFiles.size}): $fileName"
                        )
                        onPeerProgress?.invoke(peer.id, fileName, index + 1, preparedFiles.size, baseProgress)

                        val transferResult = uploadSingleFileToPeer(
                            context = context,
                            peer = peer,
                            prepFile = prepFile,
                            sessionToken = sessionToken,
                            onFileProgress = { fraction ->
                                val overall = (baseProgress + fraction / preparedFiles.size).coerceIn(0f, 1f)
                                PeerManager.updatePeerTransferStatus(
                                    peer.id,
                                    PeerTransferStatus.SENDING,
                                    overall,
                                    "Streaming (${(overall * 100).toInt()}%): $fileName"
                                )
                                onPeerProgress?.invoke(peer.id, fileName, index + 1, preparedFiles.size, overall)
                            }
                        )

                        when (transferResult) {
                            is TransferResult.Success -> {
                                peerBytes += fileLength
                                totalBytes += fileLength
                                onLogCreated?.invoke(
                                    TransferLog(
                                        fileName = fileName,
                                        fileUriString = prepFile.uri.toString(),
                                        protocol = TransferProtocol.HTTP,
                                        targetIp = peer.ipAddress,
                                        targetPort = peer.httpPort,
                                        status = com.example.data.model.TransferStatus.SUCCESS,
                                        sizeBytes = fileLength,
                                        durationMs = transferResult.durationMs,
                                        errorMessage = null
                                    )
                                )
                            }
                            is TransferResult.Error -> {
                                allPeerFilesSucceeded = false
                                Log.e(TAG, "Failed sending $fileName to ${peer.deviceName}: ${transferResult.message}")
                                onLogCreated?.invoke(
                                    TransferLog(
                                        fileName = fileName,
                                        fileUriString = prepFile.uri.toString(),
                                        protocol = TransferProtocol.HTTP,
                                        targetIp = peer.ipAddress,
                                        targetPort = peer.httpPort,
                                        status = com.example.data.model.TransferStatus.FAILED,
                                        sizeBytes = 0L,
                                        errorMessage = transferResult.message
                                    )
                                )
                            }
                        }
                    }

                    if (allPeerFilesSucceeded) {
                        PeerManager.updatePeerTransferStatus(
                            peer.id,
                            PeerTransferStatus.COMPLETED,
                            1f,
                            "✓ Received by ${peer.deviceName}"
                        )
                    } else {
                        PeerManager.updatePeerTransferStatus(
                            peer.id,
                            PeerTransferStatus.FAILED,
                            0f,
                            "Transfer incomplete"
                        )
                    }

                    peer to allPeerFilesSucceeded
                }
            }.awaitAll()
        }

        val successCount = peerResults.count { it.second }
        val failedCount = peerResults.size - successCount
        val totalDuration = System.currentTimeMillis() - startTime

        Log.i(TAG, "Broadcast complete: $successCount/${peerResults.size} peers succeeded in ${totalDuration}ms ($totalBytes bytes)")

        MultiPeerTransferSummary(
            totalFiles = fileUris.size,
            totalPeers = targetPeers.size,
            successfulPeers = successCount,
            failedPeers = failedCount,
            totalBytesTransferred = totalBytes,
            durationMs = totalDuration
        )
    }

    private fun requestPeerApproval(
        context: Context,
        peer: ConnectedPeer,
        preparedFiles: List<PreparedFile>
    ): Triple<Boolean, String?, String?> {
        val localIp = com.example.util.NetworkUtils.getLocalIpAddress(context)
        val myDeviceName = "Android (${android.os.Build.MODEL})"
        val totalBytes = preparedFiles.sumOf { it.sizeBytes }
        val fileNamesArray = org.json.JSONArray()
        for (f in preparedFiles) {
            fileNamesArray.put(f.cleanName)
        }

        val jsonBody = org.json.JSONObject().apply {
            put("senderDevice", myDeviceName)
            put("senderIp", localIp)
            put("senderPort", 8080)
            put("fileCount", preparedFiles.size)
            put("totalSizeBytes", totalBytes)
            put("fileNames", fileNamesArray)
        }

        val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
        val targetUrl = "http://${peer.ipAddress}:${peer.httpPort}/api/peer-transfer-request"

        val client = okHttpClient.newBuilder()
            .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.NANOSECONDS))
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(65, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        var lastError: Exception? = null
        for (attempt in 1..2) {
            try {
                val requestBody = RequestBody.create(mediaType, jsonBody.toString())
                val request = Request.Builder()
                    .url(targetUrl)
                    .post(requestBody)
                    .addHeader("Connection", "close")
                    .addHeader("User-Agent", "MediaSync-P2P/1.1")
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val respStr = response.body?.string() ?: ""
                    val respJson = org.json.JSONObject(respStr)
                    val accepted = respJson.optBoolean("accepted", false)
                    val token = respJson.optString("sessionToken", null)
                    val reason = respJson.optString("reason", "Declined by recipient")
                    return Triple(accepted, reason, token)
                } else {
                    return Triple(false, "Server returned HTTP ${response.code}", null)
                }
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Attempt $attempt requesting peer approval from ${peer.deviceName} failed: ${e.message}")
                if (attempt == 1) {
                    Thread.sleep(250)
                }
            }
        }
        return Triple(false, lastError?.localizedMessage ?: "Connection error", null)
    }

    private fun uploadSingleFileToPeer(
        context: Context,
        peer: ConnectedPeer,
        prepFile: PreparedFile,
        sessionToken: String? = null,
        onFileProgress: ((Float) -> Unit)? = null
    ): TransferResult {
        val startTime = System.currentTimeMillis()
        val fileName = prepFile.cleanName
        val fileLength = prepFile.sizeBytes
        val targetUrl = "http://${peer.ipAddress}:${peer.httpPort}/receive"
        val effectiveToken = sessionToken ?: peer.authToken

        val client = okHttpClient.newBuilder()
            .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.NANOSECONDS))
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(300, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        var lastError: Exception? = null
        for (attempt in 1..2) {
            try {
                val directBody = DirectUriRequestBody(
                    context = context,
                    uri = prepFile.uri,
                    length = fileLength,
                    onProgress = { sent, total ->
                        if (total > 0) {
                            val fraction = (sent.toFloat() / total).coerceIn(0f, 1f)
                            onFileProgress?.invoke(fraction)
                        }
                    }
                )

                val requestBuilder = Request.Builder()
                    .url(targetUrl)
                    .post(directBody)
                    .addHeader("Connection", "close")
                    .addHeader("User-Agent", "MediaSync-P2P/1.1")
                    .addHeader("Content-Type", "application/octet-stream")
                    .addHeader("Content-Length", fileLength.toString())
                    .addHeader("X-File-Name", java.net.URLEncoder.encode(fileName, "UTF-8"))

                if (!effectiveToken.isNullOrBlank()) {
                    requestBuilder.addHeader("X-Auth-Token", effectiveToken)
                }
                requestBuilder.addHeader("X-Sender-Ip", com.example.util.NetworkUtils.getLocalIpAddress(context))
                requestBuilder.addHeader("X-Sender-Device", "Android (${android.os.Build.MODEL})")

                val response = client.newCall(requestBuilder.build()).execute()
                val duration = System.currentTimeMillis() - startTime

                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: "OK"
                    Log.i(TAG, "Successfully sent $fileName to ${peer.deviceName} in ${duration}ms")
                    return TransferResult.Success(fileLength, duration, bodyStr)
                } else {
                    val err = response.body?.string() ?: "HTTP ${response.code}"
                    return TransferResult.Error("HTTP ${response.code}: $err")
                }
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Attempt $attempt upload $fileName to ${peer.deviceName} failed: ${e.message}")
                if (attempt == 1) {
                    Thread.sleep(250)
                }
            }
        }
        return TransferResult.Error("Upload error: ${lastError?.localizedMessage ?: lastError?.javaClass?.simpleName}", lastError)
    }
}
