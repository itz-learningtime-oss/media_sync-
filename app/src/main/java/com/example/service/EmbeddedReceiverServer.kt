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
import androidx.core.content.FileProvider
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
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
            s.soTimeout = 30000
            val inputStream = s.getInputStream()
            val outputStream = s.getOutputStream()

            val headerLines = mutableListOf<String>()
            val lineBuffer = ByteArrayOutputStream()
            var prevByte = -1

            // Read HTTP request line and headers
            while (true) {
                val b = inputStream.read()
                if (b == -1) break
                if (b == '\n'.code && prevByte == '\r'.code) {
                    val line = lineBuffer.toString(StandardCharsets.UTF_8.name()).trim()
                    lineBuffer.reset()
                    if (line.isEmpty()) {
                        // End of headers
                        break
                    }
                    headerLines.add(line)
                } else if (b != '\r'.code) {
                    lineBuffer.write(b)
                }
                prevByte = b
            }

            if (headerLines.isEmpty()) return

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

            when {
                method == "GET" && (uriPath == "/ping" || uriPath == "/health" || uriPath == "/") -> {
                    handlePing(outputStream)
                }
                method == "POST" && uriPath == "/receive" -> {
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
                "Access-Control-Allow-Headers: Content-Type, Content-Length\r\n" +
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

        if (contentType.contains("multipart/form-data")) {
            val boundaryMatch = Regex("boundary=([^;]+)").find(contentType)
            val boundary = boundaryMatch?.groupValues?.get(1)?.trim()?.removeSurrounding("\"")
            if (boundary == null) {
                sendJsonResponse(output, 400, JSONObject().put("error", "Missing multipart boundary").toString())
                return
            }
            saveMultipartFile(input, output, boundary, contentLength)
        } else {
            // Direct binary body upload with optional custom filename header
            val customFilename = headers["x-file-name"]
                ?: headers["content-disposition"]?.let { extractFilename(it) }
                ?: "received_${System.currentTimeMillis()}.bin"
            saveDirectStreamFile(input, output, customFilename, contentLength, contentType)
        }
    }

    private fun saveMultipartFile(
        input: InputStream,
        output: OutputStream,
        boundary: String,
        contentLength: Long
    ) {
        try {
            val boundaryBytes = ("--$boundary").toByteArray(StandardCharsets.ISO_8859_1)
            val headerEndMarker = "\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1)

            // Read the part headers to find filename
            val partHeaderBuffer = ByteArrayOutputStream()
            var prevByte = -1
            var headerDone = false

            while (!headerDone) {
                val b = input.read()
                if (b == -1) break
                partHeaderBuffer.write(b)
                val bytes = partHeaderBuffer.toByteArray()
                if (bytes.size >= 4) {
                    val l = bytes.size
                    if (bytes[l - 4] == '\r'.code.toByte() &&
                        bytes[l - 3] == '\n'.code.toByte() &&
                        bytes[l - 2] == '\r'.code.toByte() &&
                        bytes[l - 1] == '\n'.code.toByte()
                    ) {
                        headerDone = true
                    }
                }
            }

            val partHeaderStr = partHeaderBuffer.toString(StandardCharsets.UTF_8.name())
            val filename = extractFilename(partHeaderStr) ?: "pc_transfer_${System.currentTimeMillis()}.jpg"

            // Save file payload to destination MediaSync folder
            val result = saveStreamToPublicMediaSync(
                input = input,
                targetFileName = filename,
                stopAtBoundary = boundaryBytes
            )

            if (result != null) {
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
                sendJsonResponse(output, 500, JSONObject().put("error", "Failed to save file payload").toString())
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
        contentLength: Long,
        mimeType: String
    ) {
        try {
            val result = saveStreamToPublicMediaSync(
                input = input,
                targetFileName = filename,
                exactLength = contentLength
            )

            if (result != null) {
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
     * Saves incoming stream to Environment.DIRECTORY_DOWNLOADS/MediaSync/ using MediaStore for Android 10+
     * and fallback to standard File I/O for older OS or external storage.
     */
    private fun saveStreamToPublicMediaSync(
        input: InputStream,
        targetFileName: String,
        stopAtBoundary: ByteArray? = null,
        exactLength: Long = -1L
    ): Triple<String, Long, Uri?>? {
        val sanitizedName = targetFileName.replace("..", "").replace("/", "_").replace("\\", "_")
        var totalBytesWritten = 0L
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
                    totalBytesWritten = streamData(input, out, stopAtBoundary, exactLength)
                }

                contentValues.clear()
                contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(itemUri, contentValues, null, null)
            } else {
                // Android 9 and below: direct filesystem write in Downloads/MediaSync
                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val targetDir = File(downloadDir, "MediaSync")
                if (!targetDir.exists()) {
                    targetDir.mkdirs()
                }
                val destFile = File(targetDir, sanitizedName)
                FileOutputStream(destFile).use { out ->
                    totalBytesWritten = streamData(input, out, stopAtBoundary, exactLength)
                }
                savedUri = Uri.fromFile(destFile)
            }

            return Triple(sanitizedName, totalBytesWritten, savedUri)
        } catch (e: Exception) {
            Log.e(TAG, "Failed saving stream to Downloads/MediaSync", e)
            return null
        }
    }

    private fun streamData(
        input: InputStream,
        output: OutputStream,
        stopAtBoundary: ByteArray?,
        exactLength: Long
    ): Long {
        val buf = ByteArray(16384)
        var total = 0L

        if (stopAtBoundary != null) {
            // Buffer stream and strip boundary delimiter at the end
            val baos = ByteArrayOutputStream()
            var bytesRead: Int
            while (input.read(buf).also { bytesRead = it } != -1) {
                baos.write(buf, 0, bytesRead)
                // If stream is very large, periodically flush up to boundary safety margin
            }
            val fullBytes = baos.toByteArray()
            val endIdx = findBoundaryIndex(fullBytes, stopAtBoundary)
            val writeLen = if (endIdx != -1) {
                // Also trim trailing \r\n before the boundary
                var trimmed = endIdx
                if (trimmed >= 2 && fullBytes[trimmed - 2] == '\r'.code.toByte() && fullBytes[trimmed - 1] == '\n'.code.toByte()) {
                    trimmed -= 2
                }
                trimmed
            } else {
                fullBytes.size
            }
            output.write(fullBytes, 0, writeLen)
            output.flush()
            return writeLen.toLong()
        } else if (exactLength > 0) {
            var remaining = exactLength
            while (remaining > 0) {
                val toRead = minOf(buf.size.toLong(), remaining).toInt()
                val read = input.read(buf, 0, toRead)
                if (read == -1) break
                output.write(buf, 0, read)
                total += read
                remaining -= read
            }
            output.flush()
            return total
        } else {
            var read: Int
            while (input.read(buf).also { read = it } != -1) {
                output.write(buf, 0, read)
                total += read
            }
            output.flush()
            return total
        }
    }

    private fun findBoundaryIndex(data: ByteArray, boundary: ByteArray): Int {
        if (boundary.isEmpty() || data.size < boundary.size) return -1
        for (i in 0..(data.size - boundary.size)) {
            var found = true
            for (j in boundary.indices) {
                if (data[i + j] != boundary[j]) {
                    found = false
                    break
                }
            }
            if (found) return i
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

        // Intent to view the downloaded file or open the Downloads / Main App
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
