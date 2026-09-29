package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.example.MediaSyncApp
import com.example.data.model.TransferProtocol
import com.example.network.TransferResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TransferActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_SEND_HTTP = "com.aistudio.mediasync.ACTION_SEND_HTTP"
        const val ACTION_SEND_FTP = "com.aistudio.mediasync.ACTION_SEND_FTP"
        const val ACTION_DISMISS_NOTIFICATION = "com.aistudio.mediasync.ACTION_DISMISS_NOTIFICATION"
        const val ACTION_STOP_SERVICE = "com.aistudio.mediasync.ACTION_STOP_SERVICE"

        const val EXTRA_MEDIA_URI = "extra_media_uri"
        const val EXTRA_FILE_NAME = "extra_file_name"
        const val EXTRA_MIME_TYPE = "extra_mime_type"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
        const val EXTRA_FILE_PATH = "extra_file_path"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val notifId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)

        when (action) {
            ACTION_STOP_SERVICE -> {
                val serviceIntent = Intent(context, MediaObserverService::class.java).apply {
                    this.action = MediaObserverService.ACTION_STOP
                }
                context.startService(serviceIntent)
                Toast.makeText(context, "Stopping Media Observer Service", Toast.LENGTH_SHORT).show()
            }

            ACTION_DISMISS_NOTIFICATION -> {
                if (notifId != -1) {
                    NotificationHelper.dismissNotification(context, notifId)
                }
            }

            ACTION_SEND_HTTP -> {
                if (notifId != -1) {
                    NotificationHelper.dismissNotification(context, notifId)
                }
                handleTransfer(context, intent, TransferProtocol.HTTP)
            }

            ACTION_SEND_FTP -> {
                if (notifId != -1) {
                    NotificationHelper.dismissNotification(context, notifId)
                }
                handleTransfer(context, intent, TransferProtocol.FTP)
            }
        }
    }

    private fun handleTransfer(context: Context, intent: Intent, protocol: TransferProtocol) {
        val uriString = intent.getStringExtra(EXTRA_MEDIA_URI) ?: return
        val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: "media_${System.currentTimeMillis()}.jpg"
        val mimeType = intent.getStringExtra(EXTRA_MIME_TYPE)
        val fileUri = Uri.parse(uriString)

        Toast.makeText(
            context,
            "🚀 Sending $fileName via ${protocol.name}...",
            Toast.LENGTH_SHORT
        ).show()

        val repository = MediaSyncApp.instance.repository

        CoroutineScope(Dispatchers.IO).launch {
            val result = repository.transferFile(
                fileUri = fileUri,
                fileName = fileName,
                protocol = protocol,
                mimeType = mimeType
            )

            when (result) {
                is TransferResult.Success -> {
                    NotificationHelper.showTransferStatusNotification(
                        context = context,
                        fileName = fileName,
                        protocol = protocol,
                        isSuccess = true,
                        message = "Transferred ${result.bytesSent} bytes in ${result.durationMs}ms"
                    )
                }
                is TransferResult.Error -> {
                    NotificationHelper.showTransferStatusNotification(
                        context = context,
                        fileName = fileName,
                        protocol = protocol,
                        isSuccess = false,
                        message = result.message
                    )
                }
            }
        }
    }
}
