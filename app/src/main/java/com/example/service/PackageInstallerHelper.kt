package com.example.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * Handles checking installation permissions and triggering native Android APK package installation.
 */
object PackageInstallerHelper {

    private const val TAG = "PackageInstallerHelper"

    /**
     * Checks if the app has permission to install unknown apps (Android 8.0+).
     */
    fun canInstallApks(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * Opens the system Settings screen allowing the user to grant "Install Unknown Apps" permission.
     */
    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Error opening unknown app sources setting", e)
            }
        }
    }

    /**
     * Launches the native Android package installer intent targeting the given APK file.
     */
    fun installApk(context: Context, apkFile: File): Boolean {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            Log.e(TAG, "Cannot install: APK file does not exist or is empty (${apkFile.absolutePath})")
            return false
        }

        return try {
            val authority = "${context.packageName}.provider"
            val apkUri: Uri = try {
                FileProvider.getUriForFile(context, authority, apkFile)
            } catch (e: Exception) {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.i(TAG, "Successfully started package installer intent for $apkUri")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error launching package installer intent", e)
            false
        }
    }
}
