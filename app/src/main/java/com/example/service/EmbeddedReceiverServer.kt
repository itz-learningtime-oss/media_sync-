package com.example.service

import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.MainActivity
import com.example.R
import com.example.data.model.ConnectedPeer
import com.example.util.ApkSharingHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Lightweight embedded HTTP Server running on port 8080.
 * Endpoints:
 * - GET /: Interactive Web Portal for iOS, PC, Mac, and any web browser.
 * - GET /ping or /health: Device discovery JSON API.
 * - GET /download/apk: Streams the app's own APK file for direct installation.
 * - GET /api/shared: JSON list of shared documents from the Android device.
 * - GET /download/file?id=...: Downloads a shared document from Android.
 * - POST /receive or /upload: Receives file uploads from PC, iPhone Safari, or other phones.
 */
class EmbeddedReceiverServer(
    private val context: Context,
    private val port: Int = 8080,
    private val scope: CoroutineScope
) {
    private var serverSocket: ServerSocket? = null
    private var isRunning = false

    companion object {
        private const val TAG = "EmbeddedReceiverServer"
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        scope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(port).apply {
                    reuseAddress = true
                }
                Log.i(TAG, "Embedded HTTP Server started on port $port (IP: ${getLocalIpAddress()})")

                while (isRunning && serverSocket?.isClosed == false) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        scope.launch(Dispatchers.IO) {
                            handleClientSocket(clientSocket)
                        }
                    } catch (e: Exception) {
                        if (!isRunning) break
                        Log.e(TAG, "Error accepting client connection", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind server socket on port $port", e)
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
            serverSocket = null
            Log.i(TAG, "Embedded HTTP Server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing server socket", e)
        }
    }

    private suspend fun handleClientSocket(socket: Socket) {
        var outputStream: OutputStream? = null
        var inputStream: InputStream? = null
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 120000
            try {
                socket.setSoLinger(false, 0)
            } catch (_: Exception) {}

            val rawIn = socket.getInputStream()
            val inStream = BufferedInputStream(rawIn, 32768)
            inputStream = inStream
            val outStream = socket.getOutputStream()
            outputStream = outStream

            val headerLines = mutableListOf<String>()
            val lineBuffer = ByteArrayOutputStream()
            var prevByte = -1

            // 1. Read HTTP request line and headers
            while (true) {
                val b = inStream.read()
                if (b == -1) break
                if (b == '\n'.code && prevByte == '\r'.code) {
                    val line = lineBuffer.toString(StandardCharsets.UTF_8.name()).trim()
                    lineBuffer.reset()
                    if (line.isEmpty()) {
                        break
                    }
                    headerLines.add(line)
                } else if (b != '\r'.code) {
                    lineBuffer.write(b)
                }
                prevByte = b
            }

            if (headerLines.isEmpty()) {
                return
            }

            val requestLine = headerLines[0]
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase(Locale.US)
            val fullUri = parts[1]
            val uriPath = fullUri.split("?")[0]
            val queryString = if (fullUri.contains("?")) fullUri.substringAfter("?") else ""
            val queryParams = parseQueryParams(queryString)

            val headers = mutableMapOf<String, String>()
            for (i in 1 until headerLines.size) {
                val line = headerLines[i]
                val colonIdx = line.indexOf(':')
                if (colonIdx > 0) {
                    val k = line.substring(0, colonIdx).trim().lowercase(Locale.US)
                    val v = line.substring(colonIdx + 1).trim()
                    headers[k] = v
                }
            }

            val acceptHeader = headers["accept"] ?: ""
            Log.i(TAG, "HTTP Request: $method $uriPath (Accept: $acceptHeader)")

            val tokenFromQuery = queryParams["token"] ?: queryParams["pin"] ?: queryParams["key"]
            val tokenFromHeader = headers["x-auth-token"] ?: headers["authorization"]?.removePrefix("Bearer ")?.trim()
            val clientToken = tokenFromQuery ?: tokenFromHeader
            val senderIp = headers["x-sender-ip"] ?: socket.inetAddress?.hostAddress
            val isSessionValid = PeerConnectionManager.isValidSessionToken(clientToken)
            val isPairedSender = senderIp != null && PeerConnectionManager.isPeerPaired(context, senderIp)
            val isAuthenticated = TransferSecurityManager.validateTokenOrPin(clientToken) || isSessionValid || isPairedSender

            when {
                method == "POST" && (uriPath == "/api/peer-transfer-request" || uriPath == "/api/transfer-request") -> {
                    handlePeerTransferRequest(inStream, outStream, headers, socket.inetAddress?.hostAddress ?: "")
                }
                method == "GET" && uriPath == "/api/auth" -> {
                    val candidate = queryParams["pin"] ?: queryParams["token"] ?: clientToken
                    if (TransferSecurityManager.validateTokenOrPin(candidate)) {
                        sendJsonResponse(
                            outStream,
                            200,
                            JSONObject()
                                .put("authenticated", true)
                                .put("token", TransferSecurityManager.authToken.value)
                                .toString()
                        )
                    } else {
                        sendJsonResponse(
                            outStream,
                            401,
                            JSONObject()
                                .put("authenticated", false)
                                .put("error", "Invalid Security PIN")
                                .toString()
                        )
                    }
                }
                method == "GET" && (uriPath == "/" || uriPath == "/web") -> {
                    if (acceptHeader.contains("application/json") && uriPath == "/") {
                        handlePing(outStream, queryParams)
                    } else {
                        handleWebPortal(outStream, isAuthenticated)
                    }
                }
                method == "GET" && (uriPath == "/ping" || uriPath == "/health" || uriPath == "/api/status") -> {
                    handlePing(outStream, queryParams)
                }
                method == "GET" && (uriPath == "/app-version" || uriPath == "/api/version") -> {
                    handleAppVersion(outStream)
                }
                method == "GET" && (uriPath == "/download/latest-apk" || uriPath == "/download/apk" || uriPath == "/download/app.apk" || uriPath == "/download/MediaSync.apk") -> {
                    handleApkDownload(outStream)
                }
                method == "GET" && uriPath == "/api/shared" -> {
                    if (TransferSecurityManager.isPinRequired.value && !isAuthenticated) {
                        sendJsonResponse(
                            outStream,
                            401,
                            JSONObject().put("error", "Unauthorized. Enter PIN on portal or scan QR.").toString()
                        )
                    } else {
                        handleApiSharedFiles(outStream)
                    }
                }
                method == "GET" && uriPath == "/download/file" -> {
                    if (TransferSecurityManager.isPinRequired.value && !isAuthenticated) {
                        sendJsonResponse(
                            outStream,
                            401,
                            JSONObject().put("error", "Unauthorized. Enter PIN on portal or scan QR.").toString()
                        )
                    } else {
                        val fileId = queryParams["id"]
                        handleFileDownload(outStream, fileId)
                    }
                }
                method == "POST" && (uriPath == "/receive" || uriPath == "/upload") -> {
                    if (TransferSecurityManager.isPinRequired.value && !isAuthenticated) {
                        Log.w(TAG, "Unauthorized upload attempt from $senderIp")
                        val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L
                        try {
                            val drain = ByteArray(4096)
                            var remaining = contentLength
                            while (remaining > 0) {
                                val r = inStream.read(drain, 0, minOf(drain.size.toLong(), remaining).toInt())
                                if (r <= 0) break
                                remaining -= r
                            }
                        } catch (_: Exception) {}
                        sendJsonResponse(
                            outStream,
                            401,
                            JSONObject().put("error", "Unauthorized. Please request peer connection or enter PIN.").toString()
                        )
                    } else {
                        handleReceiveUpload(inStream, outStream, headers)
                    }
                }
                method == "OPTIONS" -> {
                    handleCorsOptions(outStream)
                }
                else -> {
                    sendJsonResponse(
                        outStream,
                        404,
                        JSONObject().put("error", "Not Found").put("path", uriPath).toString()
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client socket: ${e.message}")
        } finally {
            try {
                outputStream?.flush()
            } catch (_: Exception) {}
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    private suspend fun handlePeerTransferRequest(
        input: InputStream,
        output: OutputStream,
        headers: Map<String, String>,
        remoteIp: String
    ) {
        try {
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val bodyBytes = ByteArray(contentLength)
            var bytesRead = 0
            while (bytesRead < contentLength) {
                val r = input.read(bodyBytes, bytesRead, contentLength - bytesRead)
                if (r == -1) break
                bytesRead += r
            }
            val bodyStr = String(bodyBytes, StandardCharsets.UTF_8)
            val json = if (bodyStr.isNotBlank()) JSONObject(bodyStr) else JSONObject()

            val senderDevice = json.optString("senderDevice", "Nearby Phone")
            val senderIp = json.optString("senderIp", remoteIp).ifBlank { remoteIp }
            val senderPort = json.optInt("senderPort", 8080)
            val fileCount = json.optInt("fileCount", 1)
            val totalSizeBytes = json.optLong("totalSizeBytes", 0L)

            val fileNamesArray = json.optJSONArray("fileNames")
            val fileNames = mutableListOf<String>()
            if (fileNamesArray != null) {
                for (i in 0 until fileNamesArray.length()) {
                    fileNames.add(fileNamesArray.getString(i))
                }
            }

            val isAlreadyPaired = PeerConnectionManager.isPeerPaired(context, senderIp)

            val request = IncomingTransferRequest(
                senderDevice = senderDevice,
                senderIp = senderIp,
                senderPort = senderPort,
                fileCount = fileCount,
                totalSizeBytes = totalSizeBytes,
                fileNames = fileNames,
                isSavedPeer = isAlreadyPaired
            )

            // Auto-register peer in peer manager
            PeerManager.addOrUpdatePeer(
                deviceName = senderDevice,
                ipAddress = senderIp,
                port = senderPort,
                isPaired = isAlreadyPaired
            )

            // Await user decision via notification action or in-app dialog (up to 60 seconds)
            val (accepted, sessionToken) = PeerConnectionManager.awaitUserDecision(request, context, 60000L)
            if (accepted) {
                // Save connection on recipient mobile for future convenience
                PeerConnectionManager.savePairedPeer(
                    context,
                    ConnectedPeer(
                        deviceName = senderDevice,
                        ipAddress = senderIp,
                        httpPort = senderPort,
                        isPaired = true,
                        isOnline = true
                    )
                )
            }
            val responseJson = JSONObject().apply {
                put("accepted", accepted)
                put("receiverDevice", "Android (${Build.MODEL})")
                put("isPaired", true)
                if (accepted && sessionToken != null) {
                    put("sessionToken", sessionToken)
                } else if (!accepted) {
                    put("reason", "Declined by recipient")
                }
            }
            sendJsonResponse(output, 200, responseJson.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error handling peer transfer request", e)
            sendJsonResponse(output, 500, JSONObject().put("error", e.message ?: "Server error").toString())
        }
    }

    private fun handlePing(output: OutputStream, queryParams: Map<String, String> = emptyMap()) {
        val senderIp = queryParams["sender_ip"]
        val senderDevice = queryParams["sender_device"]?.let {
            try { java.net.URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { it }
        }
        if (!senderIp.isNullOrBlank() && senderIp != "127.0.0.1" && senderIp != getLocalIpAddress()) {
            try {
                PeerManager.addOrUpdatePeer(
                    deviceName = senderDevice ?: "Mobile Peer ($senderIp)",
                    ipAddress = senderIp,
                    port = 8080
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not auto-register sender peer: ${e.message}")
            }
        }

        val json = JSONObject().apply {
            put("status", "online")
            put("device", "Android Phone (${Build.MODEL})")
            put("ip", getLocalIpAddress())
            put("port", port)
            put("version", "1.1")
            put("versionCode", 2)
            put("pin_required", TransferSecurityManager.isPinRequired.value)
        }
        sendJsonResponse(output, 200, json.toString())
    }

    private fun handleWebPortal(output: OutputStream, isPreAuthenticated: Boolean = false) {
        try {
            SharedFileRegistry.ensureApkRegistered(context)
            val files = SharedFileRegistry.sharedFiles.value
            val html = WebPortalHtmlGenerator.generateHtml(
                deviceModel = Build.MODEL,
                serverIp = getLocalIpAddress(),
                port = port,
                sharedFiles = files,
                isSecurityEnabled = TransferSecurityManager.isPinRequired.value,
                authToken = TransferSecurityManager.authToken.value,
                isPreAuthenticated = isPreAuthenticated
            )
            val htmlBytes = html.toByteArray(StandardCharsets.UTF_8)
            val response = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/html; charset=UTF-8\r\n" +
                    "Content-Length: ${htmlBytes.size}\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n\r\n"
            output.write(response.toByteArray(StandardCharsets.UTF_8))
            output.write(htmlBytes)
            output.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error generating web portal HTML", e)
            sendJsonResponse(output, 500, JSONObject().put("error", e.message).toString())
        }
    }

    private fun handleAppVersion(output: OutputStream) {
        try {
            val json = AppUpdateManager.getVersionJson(context)
            sendJsonResponse(output, 200, json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error generating app-version response", e)
            sendJsonResponse(output, 500, JSONObject().put("error", e.message).toString())
        }
    }

    private fun handleApkDownload(output: OutputStream) {
        try {
            val liveApkFile = AppUpdateManager.getLiveApkFile(context)
            val apkFile = if (liveApkFile.exists() && liveApkFile.length() > 0L) {
                liveApkFile
            } else {
                ApkSharingHelper.getShareableApkFile(context)
            }

            if (!apkFile.exists() || apkFile.length() == 0L) {
                sendJsonResponse(output, 404, JSONObject().put("error", "APK file not available").toString())
                return
            }

            val version = AppUpdateManager.getVersionInfo(context)
            val apkLength = apkFile.length()
            val filename = "MediaSync-v${version.versionName}.apk"
            val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/vnd.android.package-archive\r\n" +
                    "Content-Disposition: attachment; filename=\"$filename\"\r\n" +
                    "Content-Length: $apkLength\r\n" +
                    "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                    "Pragma: no-cache\r\n" +
                    "Expires: 0\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n\r\n"
            output.write(header.toByteArray(StandardCharsets.UTF_8))

            apkFile.inputStream().use { input ->
                val buffer = ByteArray(16384)
                var bytesRead: Int
                var totalSent = 0L
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalSent += bytesRead
                }
                output.flush()
                Log.i(TAG, "Successfully streamed live APK $filename ($totalSent bytes) to peer client")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error streaming APK download", e)
        }
    }

    private fun handleApiSharedFiles(output: OutputStream) {
        try {
            SharedFileRegistry.ensureApkRegistered(context)
            val files = SharedFileRegistry.sharedFiles.value
            val jsonArray = org.json.JSONArray()
            for (f in files) {
                val obj = JSONObject().apply {
                    put("id", f.id)
                    put("name", f.name)
                    put("size", f.formattedSize)
                    put("size_bytes", f.sizeBytes)
                    put("mime", f.mimeType)
                    put("is_apk", f.isApk)
                }
                jsonArray.put(obj)
            }
            val root = JSONObject().apply {
                put("status", "ok")
                put("device", "Android Phone (${Build.MODEL})")
                put("ip", getLocalIpAddress())
                put("files", jsonArray)
            }
            sendJsonResponse(output, 200, root.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error serializing shared files", e)
            sendJsonResponse(output, 500, JSONObject().put("error", e.message).toString())
        }
    }

    private fun handleFileDownload(output: OutputStream, fileId: String?) {
        if (fileId == null) {
            sendJsonResponse(output, 400, JSONObject().put("error", "Missing id parameter").toString())
            return
        }
        if (fileId == "mediasync_app_apk") {
            handleApkDownload(output)
            return
        }

        val item = SharedFileRegistry.getFileById(fileId)
        if (item == null) {
            sendJsonResponse(output, 404, JSONObject().put("error", "File not found").toString())
            return
        }

        try {
            val input = context.contentResolver.openInputStream(item.uri)
                ?: run {
                    sendJsonResponse(output, 404, JSONObject().put("error", "Cannot open stream for file").toString())
                    return
                }

            input.use { stream ->
                val safeName = item.name.replace("\"", "\\\"")
                val mime = item.mimeType.ifBlank { "application/octet-stream" }
                val header = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: $mime\r\n" +
                        "Content-Disposition: attachment; filename=\"$safeName\"\r\n" +
                        (if (item.sizeBytes > 0) "Content-Length: ${item.sizeBytes}\r\n" else "") +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Connection: close\r\n\r\n"
                output.write(header.toByteArray(StandardCharsets.UTF_8))

                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalSent = 0L
                while (stream.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalSent += bytesRead
                }
                output.flush()
                Log.i(TAG, "Successfully streamed shared file '${item.name}' ($totalSent bytes) to client browser")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error streaming file download for id: $fileId", e)
        }
    }

    private fun handleCorsOptions(output: OutputStream) {
        val response = "HTTP/1.1 200 OK\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: Content-Type, Content-Length, X-File-Name\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n\r\n"
        output.write(response.toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    private fun handleReceiveUpload(
        input: InputStream,
        output: OutputStream,
        headers: Map<String, String>
    ) {
        val contentType = headers["content-type"] ?: ""
        val contentLength = headers["content-length"]?.toLongOrNull() ?: -1L

        if (contentType.contains("multipart/form-data", ignoreCase = true)) {
            val boundaryMatch = Regex("boundary=([^;\\s]+)", RegexOption.IGNORE_CASE).find(contentType)
            val boundary = boundaryMatch?.groupValues?.get(1)?.trim()?.removeSurrounding("\"")
            if (boundary == null) {
                Log.e(TAG, "Multipart request missing boundary parameter in Content-Type: $contentType")
                sendJsonResponse(output, 400, JSONObject().put("error", "Missing multipart boundary").toString())
                return
            }
            saveMultipartFile(input, output, boundary, contentLength)
        } else {
            // Direct binary upload
            val rawName = headers["x-file-name"]
                ?: headers["content-disposition"]?.let { extractFilename(it) }
                ?: "received_${System.currentTimeMillis()}.bin"
            val decodedName = try { java.net.URLDecoder.decode(rawName, "UTF-8") } catch (_: Exception) { rawName }
            val cleanName = decodedName.replace(Regex("^temp_\\d+_"), "").replace(Regex("^temp_"), "")
            saveDirectStreamFile(input, output, cleanName, contentLength)
        }
    }

    private fun saveMultipartFile(
        input: InputStream,
        output: OutputStream,
        boundary: String,
        contentLength: Long
    ) {
        try {
            Log.i(TAG, "Processing incoming multipart file upload. Boundary: '$boundary', Content-Length: $contentLength")
            val bodyBytes = readEntireBody(input, contentLength)
            Log.i(TAG, "Total raw multipart body bytes read from TCP stream: ${bodyBytes.size} bytes")

            if (bodyBytes.isEmpty()) {
                sendJsonResponse(output, 400, JSONObject().put("error", "Empty upload payload (0 bytes received)").toString())
                return
            }

            val boundaryBytes = ("--$boundary").toByteArray(StandardCharsets.ISO_8859_1)
            val headerSeparator = "\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1)

            val firstBoundaryIdx = findSequence(bodyBytes, boundaryBytes, 0)
            if (firstBoundaryIdx == -1) {
                sendJsonResponse(output, 400, JSONObject().put("error", "Invalid multipart body format").toString())
                return
            }

            val partStart = firstBoundaryIdx + boundaryBytes.size
            val headerEndIdx = findSequence(bodyBytes, headerSeparator, partStart)
            if (headerEndIdx == -1) {
                sendJsonResponse(output, 400, JSONObject().put("error", "Invalid multipart part headers").toString())
                return
            }

            val partHeaderBytes = bodyBytes.copyOfRange(partStart, headerEndIdx)
            val partHeaderStr = String(partHeaderBytes, StandardCharsets.UTF_8)
            val rawFilename = extractFilename(partHeaderStr) ?: "transfer_${System.currentTimeMillis()}.jpg"
            val filename = rawFilename.replace(Regex("^temp_\\d+_"), "").replace(Regex("^temp_"), "")

            val fileContentStart = headerEndIdx + headerSeparator.size

            val nextBoundaryIdx = findSequence(bodyBytes, boundaryBytes, fileContentStart)
            val fileContentEnd = if (nextBoundaryIdx != -1) {
                var end = nextBoundaryIdx
                if (end >= 2 && bodyBytes[end - 2] == '\r'.code.toByte() && bodyBytes[end - 1] == '\n'.code.toByte()) {
                    end -= 2
                }
                end
            } else {
                bodyBytes.size
            }

            val payloadLength = (fileContentEnd - fileContentStart).coerceAtLeast(0)
            Log.i(TAG, "Extracted file '$filename' payload: $payloadLength bytes")

            val result = writeByteArrayToPublicMediaSync(
                data = bodyBytes,
                offset = fileContentStart,
                length = payloadLength,
                targetFileName = filename
            )

            if (result != null) {
                // Register in received files registry for in-app viewing & gallery saving
                val receivedItem = ReceivedFileItem(
                    fileName = result.first,
                    sizeBytes = result.second,
                    uri = result.third,
                    mimeType = guessMimeType(result.first)
                )
                ReceivedFileRegistry.addReceivedFile(receivedItem)

                triggerFileReceivedNotification(result.first, result.second, result.third, receivedItem.isImage || receivedItem.isVideo)
                val json = JSONObject().apply {
                    put("status", "success")
                    put("message", "File saved to Downloads/MediaSync")
                    put("filename", result.first)
                    put("size_bytes", result.second)
                    put("path", result.third?.toString() ?: "")
                }
                sendJsonResponse(output, 200, json.toString())
            } else {
                sendJsonResponse(output, 500, JSONObject().put("error", "Failed to save file payload to disk").toString())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing multipart file upload", e)
            sendJsonResponse(output, 500, JSONObject().put("error", e.message ?: "Upload processing failed").toString())
        }
    }

    private fun saveDirectStreamFile(
        input: InputStream,
        output: OutputStream,
        filename: String,
        contentLength: Long
    ) {
        val sanitizedName = filename.replace("..", "").replace("/", "_").replace("\\", "_")
        try {
            Log.i(TAG, "Processing direct zero-copy stream upload for '$sanitizedName' (Length: $contentLength)")
            var totalSaved = 0L
            var savedUri: Uri? = null

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, sanitizedName)
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/MediaSync")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }

                val resolver = context.contentResolver
                val itemUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    ?: throw IOException("Failed to create MediaStore entry for $sanitizedName")
                savedUri = itemUri

                try {
                    resolver.openOutputStream(itemUri)?.use { fos ->
                        val bis = java.io.BufferedInputStream(input, 131072)
                        val bos = java.io.BufferedOutputStream(fos, 131072)
                        val buffer = ByteArray(131072) // 128KB high-speed buffer
                        var bytesRemaining = if (contentLength > 0) contentLength else Long.MAX_VALUE
                        while (bytesRemaining > 0) {
                            val toRead = if (contentLength > 0) minOf(buffer.size.toLong(), bytesRemaining).toInt() else buffer.size
                            val read = bis.read(buffer, 0, toRead)
                            if (read == -1) break
                            bos.write(buffer, 0, read)
                            totalSaved += read
                            if (contentLength > 0) {
                                bytesRemaining -= read
                            }
                        }
                        bos.flush()
                    }

                    contentValues.clear()
                    contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(itemUri, contentValues, null, null)
                } catch (e: Exception) {
                    try { resolver.delete(itemUri, null, null) } catch (_: Exception) {}
                    throw e
                }
            } else {
                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val targetDir = File(downloadDir, "MediaSync").apply { if (!exists()) mkdirs() }
                val destFile = File(targetDir, sanitizedName)
                FileOutputStream(destFile).use { fos ->
                    val bis = java.io.BufferedInputStream(input, 131072)
                    val bos = java.io.BufferedOutputStream(fos, 131072)
                    val buffer = ByteArray(131072)
                    var bytesRemaining = if (contentLength > 0) contentLength else Long.MAX_VALUE
                    while (bytesRemaining > 0) {
                        val toRead = if (contentLength > 0) minOf(buffer.size.toLong(), bytesRemaining).toInt() else buffer.size
                        val read = bis.read(buffer, 0, toRead)
                        if (read == -1) break
                        bos.write(buffer, 0, read)
                        totalSaved += read
                        if (contentLength > 0) {
                            bytesRemaining -= read
                        }
                    }
                    bos.flush()
                }
                savedUri = Uri.fromFile(destFile)
            }

            Log.i(TAG, "Successfully saved $totalSaved bytes for '$sanitizedName' directly to storage (Uri: $savedUri)")

            val receivedItem = ReceivedFileItem(
                fileName = sanitizedName,
                sizeBytes = totalSaved,
                uri = savedUri,
                mimeType = guessMimeType(sanitizedName)
            )
            ReceivedFileRegistry.addReceivedFile(receivedItem)
            triggerFileReceivedNotification(sanitizedName, totalSaved, savedUri, receivedItem.isImage || receivedItem.isVideo)

            val json = JSONObject().apply {
                put("status", "success")
                put("message", "File saved to Downloads/MediaSync")
                put("filename", sanitizedName)
                put("size_bytes", totalSaved)
                put("path", savedUri?.toString() ?: "")
            }
            sendJsonResponse(output, 200, json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error saving direct file stream", e)
            sendJsonResponse(output, 500, JSONObject().put("error", e.message ?: "Direct stream failed").toString())
        }
    }

    private fun writeTempFileToPublicMediaSync(
        tempFile: File,
        targetFileName: String
    ): Triple<String, Long, Uri?>? {
        val sanitizedName = targetFileName.replace("..", "").replace("/", "_").replace("\\", "_")
        var savedUri: Uri? = null
        val fileLength = tempFile.length()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, sanitizedName)
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/MediaSync")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }

                val resolver = context.contentResolver
                val itemUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    ?: return null

                savedUri = itemUri

                resolver.openOutputStream(itemUri)?.use { outStream ->
                    FileInputStream(tempFile).use { inStream ->
                        val buffer = ByteArray(32768)
                        var read: Int
                        while (inStream.read(buffer).also { read = it } != -1) {
                            outStream.write(buffer, 0, read)
                        }
                        outStream.flush()
                    }
                }

                contentValues.clear()
                contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(itemUri, contentValues, null, null)
            } else {
                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val targetDir = File(downloadDir, "MediaSync")
                if (!targetDir.exists()) {
                    targetDir.mkdirs()
                }
                val destFile = File(targetDir, sanitizedName)
                FileInputStream(tempFile).use { inStream ->
                    FileOutputStream(destFile).use { outStream ->
                        val buffer = ByteArray(32768)
                        var read: Int
                        while (inStream.read(buffer).also { read = it } != -1) {
                            outStream.write(buffer, 0, read)
                        }
                        outStream.flush()
                    }
                }
                savedUri = Uri.fromFile(destFile)
            }

            Log.i(TAG, "Successfully wrote $fileLength bytes for '$sanitizedName' to Downloads/MediaSync (Uri: $savedUri)")
            return Triple(sanitizedName, fileLength, savedUri)
        } catch (e: Exception) {
            Log.e(TAG, "Failed writing $fileLength bytes for '$sanitizedName' to Downloads/MediaSync", e)
            return null
        }
    }

    private fun readEntireBody(input: InputStream, contentLength: Long): ByteArray {
        val baos = ByteArrayOutputStream(if (contentLength in 1..104857600L) contentLength.toInt() else 32768)
        val buffer = ByteArray(8192)
        var totalBytesRead = 0L

        if (contentLength > 0) {
            var remaining = contentLength
            while (remaining > 0) {
                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                val bytesRead = input.read(buffer, 0, toRead)
                if (bytesRead == -1) break
                baos.write(buffer, 0, bytesRead)
                totalBytesRead += bytesRead
                remaining -= bytesRead
            }
        } else {
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                baos.write(buffer, 0, bytesRead)
                totalBytesRead += bytesRead
            }
        }

        Log.i(TAG, "readEntireBody finished: total $totalBytesRead bytes read from network socket")
        return baos.toByteArray()
    }

    private fun writeByteArrayToPublicMediaSync(
        data: ByteArray,
        offset: Int,
        length: Int,
        targetFileName: String
    ): Triple<String, Long, Uri?>? {
        val sanitizedName = targetFileName.replace("..", "").replace("/", "_").replace("\\", "_")
        var savedUri: Uri? = null

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, sanitizedName)
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/MediaSync")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }

                val resolver = context.contentResolver
                val itemUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    ?: return null

                savedUri = itemUri

                resolver.openOutputStream(itemUri)?.use { out ->
                    out.write(data, offset, length)
                    out.flush()
                }

                contentValues.clear()
                contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(itemUri, contentValues, null, null)
            } else {
                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val targetDir = File(downloadDir, "MediaSync")
                if (!targetDir.exists()) {
                    targetDir.mkdirs()
                }
                val destFile = File(targetDir, sanitizedName)
                FileOutputStream(destFile).use { out ->
                    out.write(data, offset, length)
                    out.flush()
                }
                savedUri = Uri.fromFile(destFile)
            }

            Log.i(TAG, "writeByteArrayToPublicMediaSync: Successfully wrote $length bytes for '$sanitizedName' to disk (Uri: $savedUri)")
            return Triple(sanitizedName, length.toLong(), savedUri)
        } catch (e: Exception) {
            Log.e(TAG, "Failed writing $length bytes for '$sanitizedName' to Downloads/MediaSync", e)
            return null
        }
    }

    private fun findSequence(source: ByteArray, target: ByteArray, startIndex: Int): Int {
        if (target.isEmpty() || source.size < target.size || startIndex >= source.size) return -1
        val maxSearch = source.size - target.size
        for (i in startIndex..maxSearch) {
            var match = true
            for (j in target.indices) {
                if (source[i + j] != target[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return -1
    }

    private fun extractFilename(header: String): String? {
        val regex = Regex("filename=\"?([^\";\\r\\n]+)\"?", RegexOption.IGNORE_CASE)
        val match = regex.find(header)
        return match?.groupValues?.get(1)?.trim()?.removeSurrounding("\"")
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val result = mutableMapOf<String, String>()
        for (pair in query.split("&")) {
            val idx = pair.indexOf("=")
            if (idx > 0) {
                val key = pair.substring(0, idx).trim()
                val value = pair.substring(idx + 1).trim()
                result[key] = value
            }
        }
        return result
    }

    private fun guessMimeType(name: String): String {
        return when {
            name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
            name.endsWith(".png", true) -> "image/png"
            name.endsWith(".webp", true) -> "image/webp"
            name.endsWith(".gif", true) -> "image/gif"
            name.endsWith(".mp4", true) -> "video/mp4"
            name.endsWith(".mkv", true) -> "video/x-matroska"
            name.endsWith(".pdf", true) -> "application/pdf"
            name.endsWith(".apk", true) -> "application/vnd.android.package-archive"
            name.endsWith(".zip", true) -> "application/zip"
            name.endsWith(".txt", true) -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    private fun sendJsonResponse(output: OutputStream, statusCode: Int, jsonBody: String) {
        val statusText = when (statusCode) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            500 -> "Internal Server Error"
            else -> "OK"
        }
        val bodyBytes = jsonBody.toByteArray(StandardCharsets.UTF_8)
        val response = "HTTP/1.1 $statusCode $statusText\r\n" +
                "Content-Type: application/json; charset=UTF-8\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Content-Length: ${bodyBytes.size}\r\n" +
                "Connection: close\r\n\r\n"
        output.write(response.toByteArray(StandardCharsets.UTF_8))
        output.write(bodyBytes)
        output.flush()
    }

    private fun triggerFileReceivedNotification(
        filename: String,
        sizeBytes: Long,
        fileUri: Uri?,
        isMedia: Boolean
    ) {
        val formattedSize = formatFileSize(sizeBytes)
        val notifId = (System.currentTimeMillis() % 10000).toInt() + 5000

        val viewIntent = if (fileUri != null) {
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(fileUri, context.contentResolver.getType(fileUri) ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            viewIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, NotificationHelper.CHANNEL_TRANSFER_STATUS)
            .setContentTitle("File Received: $filename")
            .setContentText("$filename ($formattedSize) saved to MediaSync")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_menu_view,
                "Open File",
                pendingIntent
            )

        try {
            NotificationManagerCompat.from(context).notify(notifId, builder.build())
        } catch (ignored: SecurityException) {
            Log.e(TAG, "Notification permission not granted for transfer alert")
        }
    }

    fun getLocalIpAddress(): String {
        return com.example.util.NetworkUtils.getLocalIpAddress(context)
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var size = bytes.toDouble()
        var unitIndex = 0
        while (size >= 1024 && unitIndex < units.size - 1) {
            size /= 1024
            unitIndex++
        }
        return "%.1f %s".format(size, units[unitIndex])
    }
}
