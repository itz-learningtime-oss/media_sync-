package com.example.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object FileUtils {

    private const val TAG = "FileUtils"

    /**
     * Copies content from a Scoped Storage content:// Uri into a temporary cache file in context.cacheDir.
     * Guarantees a real, accessible File with accurate byte length for OkHttp MultipartBody and FTP uploads.
     */
    fun getFileFromUri(context: Context, uri: Uri, fallbackName: String? = null): File {
        val resolvedName = getFileNameFromUri(context, uri) ?: fallbackName ?: "upload_${System.currentTimeMillis()}.bin"
        val sanitizedName = resolvedName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val cacheDir = File(context.cacheDir, "transfer_temp").apply {
            if (!exists()) mkdirs()
        }
        val tempFile = File(cacheDir, "temp_${System.currentTimeMillis()}_$sanitizedName")

        var totalBytesCopied = 0L
        try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                Log.e(TAG, "ContentResolver returned null InputStream for Uri: $uri")
                throw IllegalArgumentException("Cannot open stream for Uri: $uri")
            }

            inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesCopied += bytesRead
                    }
                    output.flush()
                }
            }

            Log.i(TAG, "Successfully copied $totalBytesCopied bytes from Uri ($uri) to cache file: ${tempFile.absolutePath} (Length on disk: ${tempFile.length()} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "Error copying Uri ($uri) to temp cache file", e)
            if (tempFile.exists()) {
                tempFile.delete()
            }
            throw e
        }

        return tempFile
    }

    private fun getFileNameFromUri(context: Context, uri: Uri): String? {
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        val name = cursor.getString(nameIndex)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not query DISPLAY_NAME for Uri: $uri", e)
            }
        }
        return uri.lastPathSegment
    }
}
