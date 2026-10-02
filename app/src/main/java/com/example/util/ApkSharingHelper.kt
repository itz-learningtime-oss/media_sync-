package com.example.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.example.service.AppUpdateManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object ApkSharingHelper {

    private const val TAG = "ApkSharingHelper"

    /**
     * Retrieves the running application's live APK binary directly from the OS runtime environment.
     */
    fun getSourceApkFile(context: Context): File {
        return AppUpdateManager.getLiveApkFile(context)
    }

    fun getApkSize(context: Context): Long {
        return try {
            getSourceApkFile(context).length()
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Copies the live installed application APK into cache as MediaSync.apk
     * so it can be streamed via HTTP or shared via Intent.
     * Always re-extracts the fresh live binary on demand to guarantee that
     * sharing always delivers the latest version with all current updates and fixes.
     */
    fun getShareableApkFile(context: Context): File {
        val cacheDir = File(context.cacheDir, "exported_apk").apply {
            if (!exists()) mkdirs()
        }
        val targetFile = File(cacheDir, "MediaSync.apk")
        val sourceFile = getSourceApkFile(context)

        try {
            // Delete previous cached copy to guarantee fresh binary copy
            if (targetFile.exists()) {
                targetFile.delete()
            }

            FileInputStream(sourceFile).use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            Log.i(TAG, "Copied fresh live app APK (${targetFile.length()} bytes) to ${targetFile.absolutePath}")
            return targetFile
        } catch (e: Exception) {
            Log.e(TAG, "Error copying live APK to cache, fallback to direct source", e)
            return if (targetFile.exists() && targetFile.length() > 0) targetFile else sourceFile
        }
    }

    /**
     * Creates an Intent to share the latest APK using Android's system share sheet
     * to WhatsApp, Bluetooth, Nearby Share / Quick Share, Telegram, Email, etc.
     */
    fun createShareApkIntent(context: Context): Intent {
        val apkFile = getShareableApkFile(context)
        val authority = "${context.packageName}.provider"
        val apkUri: Uri = try {
            FileProvider.getUriForFile(context, authority, apkFile)
        } catch (e: Exception) {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        }

        val version = AppUpdateManager.getVersionInfo(context)

        return Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, apkUri)
            putExtra(Intent.EXTRA_SUBJECT, "Install MediaSync App (v${version.versionName})")
            putExtra(Intent.EXTRA_TEXT, "Install latest MediaSync app (v${version.versionName}, build #${version.versionCode}) for high-speed offline and local network file transfer between Android, PC, and iPhone.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
