package com.example.service

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.example.util.ApkSharingHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream
import java.util.UUID

data class SharedFileItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val uri: Uri,
    val sizeBytes: Long,
    val mimeType: String,
    val isApk: Boolean = false,
    val addedAtMillis: Long = System.currentTimeMillis()
) {
    val formattedSize: String
        get() {
            if (sizeBytes <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB")
            var size = sizeBytes.toDouble()
            var unitIndex = 0
            while (size >= 1024 && unitIndex < units.size - 1) {
                size /= 1024
                unitIndex++
            }
            return "%.1f %s".format(size, units[unitIndex])
        }
}

object SharedFileRegistry {

    private const val TAG = "SharedFileRegistry"

    private val _sharedFiles = MutableStateFlow<List<SharedFileItem>>(emptyList())
    val sharedFiles: StateFlow<List<SharedFileItem>> = _sharedFiles.asStateFlow()

    fun addFile(item: SharedFileItem) {
        val current = _sharedFiles.value.toMutableList()
        // Avoid duplicate by name and size
        if (current.none { it.name == item.name && it.sizeBytes == item.sizeBytes }) {
            current.add(0, item)
            _sharedFiles.value = current
            Log.i(TAG, "Added shared file: ${item.name} (${item.formattedSize})")
        }
    }

    fun removeFile(id: String) {
        _sharedFiles.value = _sharedFiles.value.filter { it.id != id }
    }

    fun clearAll() {
        _sharedFiles.value = emptyList()
    }

    fun getFileById(id: String): SharedFileItem? {
        return _sharedFiles.value.find { it.id == id }
    }

    fun registerFromUri(context: Context, uri: Uri): SharedFileItem? {
        return try {
            val contentResolver = context.contentResolver
            var displayName = "file_${System.currentTimeMillis()}"
            var sizeBytes = 0L

            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) displayName = cursor.getString(nameIndex) ?: displayName
                    if (sizeIndex != -1) sizeBytes = cursor.getLong(sizeIndex)
                }
            }

            val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
            val isApk = displayName.endsWith(".apk", ignoreCase = true) || mimeType == "application/vnd.android.package-archive"

            val item = SharedFileItem(
                name = displayName,
                uri = uri,
                sizeBytes = sizeBytes,
                mimeType = mimeType,
                isApk = isApk
            )
            addFile(item)
            item
        } catch (e: Exception) {
            Log.e(TAG, "Failed registering shared file from Uri: $uri", e)
            null
        }
    }

    fun ensureApkRegistered(context: Context) {
        try {
            val liveApkFile = AppUpdateManager.getLiveApkFile(context)
            val apkFile = if (liveApkFile.exists() && liveApkFile.length() > 0L) {
                liveApkFile
            } else {
                ApkSharingHelper.getShareableApkFile(context)
            }
            val version = AppUpdateManager.getVersionInfo(context)
            val apkUri = Uri.fromFile(apkFile)
            val apkItem = SharedFileItem(
                id = "mediasync_app_apk",
                name = "MediaSync-v${version.versionName}.apk",
                uri = apkUri,
                sizeBytes = apkFile.length(),
                mimeType = "application/vnd.android.package-archive",
                isApk = true
            )
            val current = _sharedFiles.value.toMutableList()
            val existingIdx = current.indexOfFirst { it.id == "mediasync_app_apk" }
            if (existingIdx != -1) {
                current[existingIdx] = apkItem
            } else {
                current.add(apkItem)
            }
            _sharedFiles.value = current
        } catch (e: Exception) {
            Log.e(TAG, "Error registering app APK in shared registry", e)
        }
    }
}
