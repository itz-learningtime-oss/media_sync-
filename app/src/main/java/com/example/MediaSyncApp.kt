package com.example

import android.app.Application
import com.example.data.db.AppDatabase
import com.example.data.repository.MediaTransferRepository
import com.example.service.NotificationHelper

class MediaSyncApp : Application() {

    val database by lazy { AppDatabase.getDatabase(this) }
    val repository by lazy { MediaTransferRepository(this, database.detectedMediaDao(), database.transferDao()) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Initialize notification channels at application start
        NotificationHelper.createNotificationChannels(this)
    }

    companion object {
        lateinit var instance: MediaSyncApp
            private set
    }
}
