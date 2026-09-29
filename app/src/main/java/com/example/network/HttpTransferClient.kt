package com.example.network

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.InputStream
import java.util.concurrent.TimeUnit

class HttpTransferClient(private val context: Context) {

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
        try {
            val contentResolver = context.contentResolver
            val resolvedMime = mimeType ?: contentResolver.getType(fileUri) ?: "application/octet-stream"

            // Open stream to read file size and content
            val inputStream: InputStream = contentResolver.openInputStream(fileUri)
                ?: return@withContext TransferResult.Error("Cannot open stream for URI: $fileUri")

            val customRequestBody = object : RequestBody() {
                override fun contentType() = resolvedMime.toMediaTypeOrNull()

                override fun writeTo(sink: BufferedSink) {
                    contentResolver.openInputStream(fileUri)?.use { stream ->
                        sink.writeAll(stream.source())
                    }
                }
            }

            val sanitizedEndpoint = if (endpoint.startsWith("/")) endpoint else "/$endpoint"
            val url = "http://$hostIp:$port$sanitizedEndpoint"

            val multipartBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", fileName, customRequestBody)
                .build()

            val request = Request.Builder()
                .url(url)
                .post(multipartBody)
                .addHeader("User-Agent", "MediaSyncBridge-Android/1.0")
                .build()

            val response = okHttpClient.newCall(request).execute()
            val duration = System.currentTimeMillis() - startTime

            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: "OK"
                TransferResult.Success(
                    bytesSent = customRequestBody.contentLength().takeIf { it > 0 } ?: 0L,
                    durationMs = duration,
                    message = "HTTP ${response.code}: $responseBody"
                )
            } else {
                TransferResult.Error("Server responded with HTTP ${response.code}: ${response.message}")
            }
        } catch (e: Exception) {
            TransferResult.Error("HTTP Upload failed: ${e.localizedMessage ?: e.javaClass.simpleName}", e)
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
            // Also test base url
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
