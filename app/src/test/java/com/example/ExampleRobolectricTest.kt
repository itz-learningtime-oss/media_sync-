package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.DetectedMedia
import com.example.service.EmbeddedReceiverServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun readStringFromContext() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("MediaSync", appName)
    }

    @Test
    fun detectedMediaFormatting() {
        val media = DetectedMedia(
            mediaStoreId = 123L,
            contentUriString = "content://media/external/images/media/123",
            displayName = "Screenshot_test.png",
            filePath = "/storage/emulated/0/Pictures/Screenshots/Screenshot_test.png",
            sizeBytes = 1048576L * 2, // 2 MB
            mimeType = "image/png",
            dateAddedSeconds = 1700000000L,
            isScreenshot = true,
            bucketName = "Screenshots"
        )

        assertEquals("2.0 MB", media.formattedSize)
        assertTrue(media.isScreenshot)
    }

    @Test
    fun embeddedReceiverServerCreation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val server = EmbeddedReceiverServer(
            context = context,
            port = 8080,
            scope = CoroutineScope(Dispatchers.IO)
        )
        assertNotNull(server)
        val ip = server.getLocalIpAddress()
        assertNotNull(ip)
    }
}
