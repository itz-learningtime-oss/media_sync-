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
        val cleanName = resolvedName.replace(Regex("^temp_\\d+_"), "").replace(Regex("^temp_"), "")
        val sanitizedName = cleanName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val sessionDir = File(context.cacheDir, "transfer_temp_${System.currentTimeMillis()}").apply {
            if (!exists()) mkdirs()
        }
        val tempFile = File(sessionDir, sanitizedName)

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

    /**
     * Queries display name, byte size, and MIME type for any content Uri (e.g. from Gallery / File Manager share).
     */
    fun queryFileInfo(context: Context, uri: Uri): Triple<String, Long, String> {
        var name = "Shared_File_${System.currentTimeMillis()}"
        var size = 0L
        var mime = context.contentResolver.getType(uri) ?: "application/octet-stream"

        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIdx != -1) {
                            cursor.getString(nameIdx)?.let { if (it.isNotBlank()) name = it }
                        }
                        if (sizeIdx != -1) {
                            size = cursor.getLong(sizeIdx)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not query metadata for content uri: $uri", e)
            }
        } else if (uri.scheme == "file") {
            uri.lastPathSegment?.let { name = it }
            val f = File(uri.path ?: "")
            if (f.exists()) {
                size = f.length()
            }
        }

        name = name.replace(Regex("^temp_\\d+_"), "").replace(Regex("^temp_"), "")

        if (mime == "application/octet-stream") {
            val extension = name.substringAfterLast('.', "").lowercase()
            mime = when (extension) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "mp4" -> "video/mp4"
                "mkv" -> "video/x-matroska"
                "mov" -> "video/quicktime"
                "mp3" -> "audio/mpeg"
                "wav" -> "audio/wav"
                "pdf" -> "application/pdf"
                "apk" -> "application/vnd.android.package-archive"
                "zip" -> "application/zip"
                "txt" -> "text/plain"
                else -> "application/octet-stream"
            }
        }

        return Triple(name, size, mime)
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
