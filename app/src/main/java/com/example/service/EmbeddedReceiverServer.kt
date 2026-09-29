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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
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
 * - GET /ping: returns { "status": "online", "device": "Android Phone", "ip": "<LOCAL_IP>" }
 * - POST /receive: accepts multipart/form-data or binary uploads from PC and saves to Downloads/MediaSync/
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

    private fun handleClientSocket(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 45000
            val inputStream = s.getInputStream()
            val outputStream = s.getOutputStream()

            val headerLines = mutableListOf<String>()
            val lineBuffer = ByteArrayOutputStream()
            var prevByte = -1

            // 1. Read HTTP request line and headers (byte-by-byte until CRLF CRLF)
            while (true) {
                val b = inputStream.read()
                if (b == -1) break
                if (b == '\n'.code && prevByte == '\r'.code) {
                    val line = lineBuffer.toString(StandardCharsets.UTF_8.name()).trim()
                    lineBuffer.reset()
                    if (line.isEmpty()) {
                        // End of headers reached
                        break
                    }
                    headerLines.add(line)
                } else if (b != '\r'.code) {
                    lineBuffer.write(b)
                }
                prevByte = b
            }

            if (headerLines.isEmpty()) {
                Log.w(TAG, "Received empty HTTP request headers from client")
                return
            }

            val requestLine = headerLines[0]
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase(Locale.US)
            val uriPath = parts[1].split("?")[0]

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

            Log.i(TAG, "HTTP Request: $method $uriPath (Content-Length: ${headers["content-length"] ?: "none"}, Content-Type: ${headers["content-type"] ?: "none"})")

            when {
                method == "GET" && (uriPath == "/ping" || uriPath == "/health" || uriPath == "/") -> {
                    handlePing(outputStream)
                }
                method == "POST" && (uriPath == "/receive" || uriPath == "/upload") -> {
                    handleReceiveUpload(inputStream, outputStream, headers)
                }
                method == "OPTIONS" -> {
                    handleCorsOptions(outputStream)
                }
                else -> {
                    sendJsonResponse(
                        outputStream,
                        404,
                        JSONObject().put("error", "Not Found").put("path", uriPath).toString()
                    )
                }
            }
        }
    }

    private fun handlePing(output: OutputStream) {
        val json = JSONObject().apply {
            put("status", "online")
            put("device", "Android Phone (${Build.MODEL})")
            put("ip", getLocalIpAddress())
            put("port", port)
            put("version", "1.0")
        }
        sendJsonResponse(output, 200, json.toString())
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
            val customFilename = headers["x-file-name"]
                ?: headers["content-disposition"]?.let { extractFilename(it) }
                ?: "received_${System.currentTimeMillis()}.bin"
            saveDirectStreamFile(input, output, customFilename, contentLength)
        }
    }

    /**
     * Reads multipart body from input stream without relying on inputStream.available().
     * Uses standard ByteArray(8192) buffer loop to read all bytes up to contentLength.
     */
    private fun saveMultipartFile(
        input: InputStream,
        output: OutputStream,
        boundary: String,
        contentLength: Long
    ) {
        try {
            Log.i(TAG, "Processing incoming multipart file upload. Boundary: '$boundary', Content-Length: $contentLength")

            // Read the full multipart payload using standard 8192 buffer loop
            val bodyBytes = readEntireBody(input, contentLength)
            Log.i(TAG, "Total raw multipart body bytes read from TCP stream: ${bodyBytes.size} bytes")

            if (bodyBytes.isEmpty()) {
                Log.e(TAG, "Multipart upload body is empty (0 bytes received from network)")
                sendJsonResponse(output, 400, JSONObject().put("error", "Empty upload payload (0 bytes received)").toString())
                return
            }

            val boundaryBytes = ("--$boundary").toByteArray(StandardCharsets.ISO_8859_1)
            val headerSeparator = "\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1)

            // Find first boundary
            val firstBoundaryIdx = findSequence(bodyBytes, boundaryBytes, 0)
            if (firstBoundaryIdx == -1) {
                Log.e(TAG, "Initial boundary not found in multipart body")
                sendJsonResponse(output, 400, JSONObject().put("error", "Invalid multipart body format").toString())
                return
            }

            val partStart = firstBoundaryIdx + boundaryBytes.size
            val headerEndIdx = findSequence(bodyBytes, headerSeparator, partStart)
            if (headerEndIdx == -1) {
                Log.e(TAG, "Part header separator not found in multipart body")
                sendJsonResponse(output, 400, JSONObject().put("error", "Invalid multipart part headers").toString())
                return
            }

            val partHeaderBytes = bodyBytes.copyOfRange(partStart, headerEndIdx)
            val partHeaderStr = String(partHeaderBytes, StandardCharsets.UTF_8)
            val filename = extractFilename(partHeaderStr) ?: "pc_transfer_${System.currentTimeMillis()}.jpg"

            val fileContentStart = headerEndIdx + headerSeparator.size

            // Find closing boundary
            val nextBoundaryIdx = findSequence(bodyBytes, boundaryBytes, fileContentStart)
            val fileContentEnd = if (nextBoundaryIdx != -1) {
                // Trim trailing \r\n before closing boundary
                var end = nextBoundaryIdx
                if (end >= 2 && bodyBytes[end - 2] == '\r'.code.toByte() && bodyBytes[end - 1] == '\n'.code.toByte()) {
                    end -= 2
                }
                end
            } else {
                bodyBytes.size
            }

            val payloadLength = (fileContentEnd - fileContentStart).coerceAtLeast(0)
            Log.i(TAG, "Extracted file '$filename' payload: $payloadLength bytes (from byte index $fileContentStart to $fileContentEnd)")

            val result = writeByteArrayToPublicMediaSync(
                data = bodyBytes,
                offset = fileContentStart,
                length = payloadLength,
                targetFileName = filename
            )

            if (result != null) {
                Log.i(TAG, "Saved multipart file '$filename' (${result.second} bytes) to disk successfully (URI: ${result.third})")
                triggerFileReceivedNotification(result.first, result.second, result.third)
                val json = JSONObject().apply {
                    put("status", "success")
                    put("message", "File saved to Downloads/MediaSync")
                    put("filename", result.first)
                    put("size_bytes", result.second)
                    put("path", result.third?.toString() ?: "")
                }
                sendJsonResponse(output, 200, json.toString())
            } else {
                Log.e(TAG, "Failed writing extracted payload to MediaSync storage")
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
        try {
            Log.i(TAG, "Processing incoming direct stream upload for '$filename'. Content-Length: $contentLength")
            val bodyBytes = readEntireBody(input, contentLength)
            Log.i(TAG, "Total direct stream bytes read from TCP socket: ${bodyBytes.size} bytes")

            val result = writeByteArrayToPublicMediaSync(
                data = bodyBytes,
                offset = 0,
                length = bodyBytes.size,
                targetFileName = filename
            )

            if (result != null) {
                Log.i(TAG, "Saved direct stream file '$filename' (${result.second} bytes) to disk (URI: ${result.third})")
                triggerFileReceivedNotification(result.first, result.second, result.third)
                val json = JSONObject().apply {
                    put("status", "success")
                    put("message", "File saved to Downloads/MediaSync")
                    put("filename", result.first)
                    put("size_bytes", result.second)
                    put("path", result.third?.toString() ?: "")
                }
                sendJsonResponse(output, 200, json.toString())
            } else {
                sendJsonResponse(output, 500, JSONObject().put("error", "Failed to save direct stream file").toString())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving direct file stream", e)
            sendJsonResponse(output, 500, JSONObject().put("error", e.message ?: "Direct stream failed").toString())
        }
    }

    /**
     * Reads all bytes from the InputStream using a standard ByteArray(8192) buffer loop.
     * NEVER uses inputStream.available()!
     */
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

    /**
     * Writes byte array slice directly to MediaStore.Downloads/MediaSync or FileOutputStream.
     * Flushes and closes the stream inside .use { } block.
     * Logs exact byte count written to disk.
     */
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

            Log.i(TAG, "writeByteArrayToPublicMediaSync: Successfully wrote $length bytes to disk for '$sanitizedName' (Target Uri: $savedUri)")
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

    private fun sendJsonResponse(output: OutputStream, statusCode: Int, jsonBody: String) {
        val statusText = when (statusCode) {
            200 -> "OK"
            400 -> "Bad Request"
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

    /**
     * Triggers the required high-priority System Notification (CHANNEL_TRANSFER_STATUS)
     * with PendingIntent action to open/view the downloaded file directly.
     */
    private fun triggerFileReceivedNotification(
        filename: String,
        sizeBytes: Long,
        fileUri: Uri?
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
            .setContentTitle("File Received from PC")
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
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving local IP", e)
        }
        return "127.0.0.1"
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
