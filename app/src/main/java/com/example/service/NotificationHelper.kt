package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.MainActivity
import com.example.R
import com.example.data.model.DetectedMedia
import com.example.data.model.TransferProtocol

object NotificationHelper {

    const val CHANNEL_FOREGROUND_SERVICE = "channel_media_sync_service"
    const val CHANNEL_MEDIA_DETECTED = "channel_media_detected_alert"
    const val CHANNEL_TRANSFER_STATUS = "channel_media_transfer_status"

    const val NOTIFICATION_ID_FOREGROUND = 1001
    const val NOTIFICATION_ID_TRANSFER_BASE = 2000

    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // 1. Foreground Service Channel (Low importance so it's silent & non-intrusive)
            val serviceChannel = NotificationChannel(
                CHANNEL_FOREGROUND_SERVICE,
                context.getString(R.string.channel_service_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.channel_service_desc)
                setShowBadge(false)
            }

            // 2. Media Detection Channel (HIGH importance for Heads-up notification)
            val detectionChannel = NotificationChannel(
                CHANNEL_MEDIA_DETECTED,
                context.getString(R.string.channel_detect_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.channel_detect_desc)
                enableVibration(true)
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            // 3. Transfer Status Channel
            val transferChannel = NotificationChannel(
                CHANNEL_TRANSFER_STATUS,
                context.getString(R.string.channel_transfer_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.channel_transfer_desc)
                setShowBadge(false)
            }

            notificationManager.createNotificationChannels(
                listOf(serviceChannel, detectionChannel, transferChannel)
            )
        }
    }

    /**
     * Builds the persistent notification for the MediaObserver Foreground Service.
     */
    fun buildForegroundNotification(context: Context, detectedCount: Int = 0): Notification {
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(context, TransferActionReceiver::class.java).apply {
            action = TransferActionReceiver.ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            context,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val textContent = if (detectedCount > 0) {
            "Active • $detectedCount media events captured"
        } else {
            "Active • Listening for screenshots and camera photos"
        }

        return NotificationCompat.Builder(context, CHANNEL_FOREGROUND_SERVICE)
            .setContentTitle("Media Sync Bridge Active")
            .setContentText(textContent)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop Service",
                stopPendingIntent
            )
            .build()
    }

    /**
     * Triggers a heads-up System Notification when a new image or screenshot is detected,
     * featuring two quick action buttons: "Send via Wi-Fi (HTTP)" and "Send via FTP".
     */
    fun showMediaDetectedNotification(
        context: Context,
        media: DetectedMedia,
        thumbnailBitmap: Bitmap? = null
    ) {
        val notificationId = (media.mediaStoreId % 10000).toInt() + 3000

        // Intent when clicking the notification body (opens MainActivity)
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("EXTRA_MEDIA_ID", media.mediaStoreId)
            putExtra("EXTRA_MEDIA_URI", media.contentUriString)
            putExtra("EXTRA_MEDIA_NAME", media.displayName)
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action 1: Send via Wi-Fi (HTTP)
        val httpIntent = Intent(context, TransferActionReceiver::class.java).apply {
            action = TransferActionReceiver.ACTION_SEND_HTTP
            putExtra(TransferActionReceiver.EXTRA_MEDIA_URI, media.contentUriString)
            putExtra(TransferActionReceiver.EXTRA_FILE_NAME, media.displayName)
            putExtra(TransferActionReceiver.EXTRA_MIME_TYPE, media.mimeType)
            putExtra(TransferActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(TransferActionReceiver.EXTRA_FILE_PATH, media.filePath)
        }
        val httpPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId * 2,
            httpIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action 2: Send via FTP
        val ftpIntent = Intent(context, TransferActionReceiver::class.java).apply {
            action = TransferActionReceiver.ACTION_SEND_FTP
            putExtra(TransferActionReceiver.EXTRA_MEDIA_URI, media.contentUriString)
            putExtra(TransferActionReceiver.EXTRA_FILE_NAME, media.displayName)
            putExtra(TransferActionReceiver.EXTRA_MIME_TYPE, media.mimeType)
            putExtra(TransferActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(TransferActionReceiver.EXTRA_FILE_PATH, media.filePath)
        }
        val ftpPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId * 2 + 1,
            ftpIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Dismiss action
        val dismissIntent = Intent(context, TransferActionReceiver::class.java).apply {
            action = TransferActionReceiver.ACTION_DISMISS_NOTIFICATION
            putExtra(TransferActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val dismissPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId * 2 + 2,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (media.isScreenshot) {
            "📸 Screenshot Captured: ${media.displayName}"
        } else {
            "📸 New Image Detected: ${media.displayName}"
        }

        val bodyText = "Size: ${media.formattedSize} • Ready to sync to PC"

        val builder = NotificationCompat.Builder(context, CHANNEL_MEDIA_DETECTED)
            .setContentTitle(title)
            .setContentText(bodyText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(openAppPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_upload,
                context.getString(R.string.action_send_http),
                httpPendingIntent
            )
            .addAction(
                android.R.drawable.ic_menu_send,
                context.getString(R.string.action_send_ftp),
                ftpPendingIntent
            )
            .addAction(
                android.R.drawable.ic_delete,
                context.getString(R.string.action_dismiss),
                dismissPendingIntent
            )

        if (thumbnailBitmap != null) {
            builder.setLargeIcon(thumbnailBitmap)
            builder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(thumbnailBitmap)
                    .bigLargeIcon(null as Bitmap?)
                    .setSummaryText(bodyText)
            )
        }

        try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        } catch (e: SecurityException) {
            // Permission not granted yet
        }
    }

    /**
     * Shows a transfer progress or completion notification.
     */
    fun showTransferStatusNotification(
        context: Context,
        fileName: String,
        protocol: TransferProtocol,
        isSuccess: Boolean,
        message: String
    ) {
        val notifId = NOTIFICATION_ID_TRANSFER_BASE + (System.currentTimeMillis() % 100).toInt()
        val title = if (isSuccess) {
            "✅ ${protocol.name} Transfer Complete"
        } else {
            "❌ ${protocol.name} Transfer Failed"
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_TRANSFER_STATUS)
            .setContentTitle(title)
            .setContentText("$fileName: $message")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        try {
            NotificationManagerCompat.from(context).notify(notifId, builder.build())
        } catch (ignored: SecurityException) {}
    }

    fun dismissNotification(context: Context, notificationId: Int) {
        try {
            NotificationManagerCompat.from(context).cancel(notificationId)
        } catch (ignored: Exception) {}
    }

    /**
     * Helper to safely decode a downscaled thumbnail bitmap from a Content URI.
     */
    fun loadThumbnail(context: Context, uri: Uri): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
            val targetSize = 400
            val maxDim = maxOf(options.outWidth, options.outHeight)
            var sampleSize = 1
            if (maxDim > targetSize) {
                sampleSize = (maxDim / targetSize).coerceAtLeast(1)
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            }
        } catch (e: Exception) {
            null
        }
    }
}
