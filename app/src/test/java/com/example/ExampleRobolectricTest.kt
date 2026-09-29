package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.DetectedMedia
import com.example.service.EmbeddedReceiverServer
import com.example.ui.components.QrGenerator
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

    @Test
    fun qrCodeGeneratorTest() {
        val qrBitmap = QrGenerator.generateQrCode("{\"ip\":\"192.168.1.50\",\"port\":8080}", 300)
        assertNotNull(qrBitmap)
        assertEquals(300, qrBitmap?.width)
        assertEquals(300, qrBitmap?.height)
    }

    @Test
    fun qrCodeDecoderJsonParsingTest() {
        val jsonPayload = "{\"ip\":\"192.168.1.15\",\"port\":8000,\"ftp_port\":2121,\"name\":\"PC Connect\"}"
        val parsed = com.example.ui.components.QrCodeDecoder.parsePcConnectionInfo(jsonPayload)
        assertNotNull(parsed)
        assertEquals("192.168.1.15", parsed?.hostIp)
        assertEquals(8000, parsed?.httpPort)
        assertEquals(2121, parsed?.ftpPort)
        assertEquals("PC Connect", parsed?.serverName)
    }

    @Test
    fun qrCodeDecoderUrlParsingTest() {
        val urlPayload = "http://192.168.1.100:8000/upload"
        val parsed = com.example.ui.components.QrCodeDecoder.parsePcConnectionInfo(urlPayload)
        assertNotNull(parsed)
        assertEquals("192.168.1.100", parsed?.hostIp)
        assertEquals(8000, parsed?.httpPort)
        assertEquals("/upload", parsed?.httpEndpoint)
    }

    @Test
    fun qrCodeDecoderIpPortParsingTest() {
        val ipPort = "192.168.1.42:9000"
        val parsed = com.example.ui.components.QrCodeDecoder.parsePcConnectionInfo(ipPort)
        assertNotNull(parsed)
        assertEquals("192.168.1.42", parsed?.hostIp)
        assertEquals(9000, parsed?.httpPort)
    }

    @Test
    fun fileUtilsCacheCreationTest() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dummyFile = java.io.File(context.cacheDir, "sample_test.png").apply {
            writeBytes("hello media sync data".toByteArray())
        }
        val uri = android.net.Uri.fromFile(dummyFile)
        val cachedFile = com.example.util.FileUtils.getFileFromUri(context, uri, "sample_test.png")
        assertNotNull(cachedFile)
        assertTrue(cachedFile.exists())
        assertEquals(dummyFile.length(), cachedFile.length())
        cachedFile.delete()
        dummyFile.delete()
    }
}
