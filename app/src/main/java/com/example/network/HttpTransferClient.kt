package com.example.network

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.util.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

class HttpTransferClient(private val context: Context) {

    companion object {
        private const val TAG = "HttpTransferClient"
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun uploadFile(
        hostIp: String,
        port: Int,
        endpoint: String,
        fileUri: Uri,
        fileName: String,
        mimeType: String? = null
    ): TransferResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        var tempFile: File? = null

        try {
            val contentResolver = context.contentResolver
            val resolvedMime = mimeType ?: contentResolver.getType(fileUri) ?: "application/octet-stream"

            // 1. Copy content:// URI to temporary cache file to avoid 0 KB Scoped Storage issue
            tempFile = FileUtils.getFileFromUri(context, fileUri, fileName)
            val fileLength = tempFile.length()

            Log.i(TAG, "Starting HTTP upload for '$fileName'. File size: $fileLength bytes. Target: http://$hostIp:$port$endpoint")

            if (fileLength == 0L) {
                Log.w(TAG, "Warning: Cached file size is 0 bytes for Uri: $fileUri")
            }

            // 2. Build OkHttp RequestBody from the concrete cached File
            val requestFile = tempFile.asRequestBody(resolvedMime.toMediaTypeOrNull())
            val multipartBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", fileName, requestFile)
                .build()

            val sanitizedEndpoint = if (endpoint.startsWith("/")) endpoint else "/$endpoint"
            val url = "http://$hostIp:$port$sanitizedEndpoint"

            val request = Request.Builder()
                .url(url)
                .post(multipartBody)
                .addHeader("User-Agent", "MediaSyncBridge-Android/1.0")
                .build()

            // 3. Execute HTTP POST upload
            val response = okHttpClient.newCall(request).execute()
            val duration = System.currentTimeMillis() - startTime

            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: "OK"
                Log.i(TAG, "HTTP Upload Successful: $fileLength bytes sent in ${duration}ms (Response: ${response.code} $responseBody)")
                TransferResult.Success(
                    bytesSent = fileLength,
                    durationMs = duration,
                    message = "HTTP ${response.code}: $responseBody"
                )
            } else {
                val errorBody = response.body?.string() ?: ""
                Log.e(TAG, "HTTP Upload Failed: Server returned ${response.code} ${response.message}. Body: $errorBody")
                TransferResult.Error("Server responded with HTTP ${response.code}: ${response.message} ($errorBody)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "HTTP Upload Exception for $fileName: ${e.localizedMessage}", e)
            TransferResult.Error("HTTP Upload failed: ${e.localizedMessage ?: e.javaClass.simpleName}", e)
        } finally {
            // 4. Clean up temporary cache file
            try {
                if (tempFile != null && tempFile.exists()) {
                    val deleted = tempFile.delete()
                    Log.d(TAG, "Cleaned up temp cache file ($deleted): ${tempFile.name}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete temp cache file", e)
            }
        }
    }

    suspend fun pingServer(hostIp: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("http://$hostIp:$port/health")
                .get()
                .build()
            val response = okHttpClient.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            // Fallback to base url test
            try {
                val request = Request.Builder()
                    .url("http://$hostIp:$port/")
                    .get()
                    .build()
                val response = okHttpClient.newCall(request).execute()
                response.isSuccessful
            } catch (ignored: Exception) {
                false
            }
        }
    }
}
