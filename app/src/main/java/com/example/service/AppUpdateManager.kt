package com.example.service

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class AppVersionInfo(
    val versionCode: Long,
    val versionName: String,
    val packageName: String,
    val apkSizeBytes: Long
)

/**
 * Manages live APK extraction directly from the Android OS runtime environment
 * and handles version inspection and update package downloading.
 */
object AppUpdateManager {

    private const val TAG = "AppUpdateManager"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * Retrieves the running application's live APK binary directly from the OS runtime environment.
     * `context.applicationInfo.sourceDir` always points to the exact, current APK running on this device.
     */
    fun getLiveApkFile(context: Context): File {
        val path = context.applicationInfo.sourceDir
        val file = File(path)
        Log.i(TAG, "Extracted live APK path: $path (size: ${file.length()} bytes, exists: ${file.exists()})")
        return file
    }

    /**
     * Dynamically extracts versionCode, versionName, and package metadata directly from PackageManager.
     */
    fun getVersionInfo(context: Context): AppVersionInfo {
        return try {
            val pm = context.packageManager
            val packageInfo: PackageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, 0)
            }
            val versionCode: Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
            val versionName = packageInfo.versionName ?: "1.0"
            val apkSize = getLiveApkFile(context).length()
            AppVersionInfo(
                versionCode = versionCode,
                versionName = versionName,
                packageName = context.packageName,
                apkSizeBytes = apkSize
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error querying package version info", e)
            AppVersionInfo(
                versionCode = 1L,
                versionName = "1.0",
                packageName = context.packageName,
                apkSizeBytes = getLiveApkFile(context).length()
            )
        }
    }

    /**
     * Serializes version metadata into JSON for GET /app-version endpoint.
     */
    fun getVersionJson(context: Context): JSONObject {
        val info = getVersionInfo(context)
        return JSONObject().apply {
            put("app", "MediaSync")
            put("versionCode", info.versionCode)
            put("versionName", info.versionName)
            put("packageName", info.packageName)
            put("apkSizeBytes", info.apkSizeBytes)
            put("downloadUrl", "/download/latest-apk")
        }
    }

    /**
     * Queries a remote peer's /app-version endpoint to check if an update is available.
     */
    suspend fun checkRemoteVersion(serverUrl: String, authToken: String? = null): AppVersionInfo? = withContext(Dispatchers.IO) {
        try {
            val baseUrl = serverUrl.trimEnd('/')
            val targetUrl = if (authToken.isNullOrBlank()) {
                "$baseUrl/app-version"
            } else {
                "$baseUrl/app-version?token=$authToken"
            }
            val requestBuilder = Request.Builder().url(targetUrl)
            if (!authToken.isNullOrBlank()) {
                requestBuilder.addHeader("X-Auth-Token", authToken)
            }
            val response = httpClient.newCall(requestBuilder.build()).execute()
            if (!response.isSuccessful) return@withContext null
            val body = response.body?.string() ?: return@withContext null
            val json = JSONObject(body)
            AppVersionInfo(
                versionCode = json.optLong("versionCode", 0L),
                versionName = json.optString("versionName", "1.0"),
                packageName = json.optString("packageName", ""),
                apkSizeBytes = json.optLong("apkSizeBytes", 0L)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Unable to query remote app-version from $serverUrl", e)
            null
        }
    }

    /**
     * Downloads the latest APK stream directly into cacheDir/updates/latest.apk with progress tracking.
     */
    suspend fun downloadLatestApk(
        context: Context,
        downloadUrl: String,
        authToken: String? = null,
        onProgress: (Float) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        try {
            val requestBuilder = Request.Builder().url(downloadUrl)
            if (!authToken.isNullOrBlank()) {
                requestBuilder.addHeader("X-Auth-Token", authToken)
            }
            val response = httpClient.newCall(requestBuilder.build()).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Download failed with HTTP ${response.code}")
                return@withContext null
            }
            val body = response.body ?: return@withContext null
            val totalBytes = body.contentLength()

            val updatesDir = File(context.cacheDir, "updates").apply {
                if (!exists()) mkdirs()
            }
            val targetFile = File(updatesDir, "latest.apk")
            if (targetFile.exists()) {
                targetFile.delete()
            }

            var bytesDownloaded = 0L
            val buffer = ByteArray(16384)
            body.byteStream().use { input ->
                FileOutputStream(targetFile).use { output ->
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesDownloaded += read
                        if (totalBytes > 0) {
                            val progress = (bytesDownloaded.toFloat() / totalBytes).coerceIn(0f, 1f)
                            onProgress(progress)
                        }
                    }
                    output.flush()
                }
            }
            onProgress(1f)
            Log.i(TAG, "Successfully downloaded latest APK to ${targetFile.absolutePath} ($bytesDownloaded bytes)")
            targetFile
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading latest APK", e)
            null
        }
    }
}
