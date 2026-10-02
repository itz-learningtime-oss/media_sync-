package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.DetectedMedia
import com.example.service.EmbeddedReceiverServer
import com.example.ui.components.QrGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun sharedFileRegistryAndWebPortalTest() {
        val item = com.example.service.SharedFileItem(
            id = "test_doc_1",
            name = "ProjectReport.pdf",
            uri = android.net.Uri.parse("content://dummy/report.pdf"),
            sizeBytes = 1048576L * 5,
            mimeType = "application/pdf"
        )
        com.example.service.SharedFileRegistry.addFile(item)
        val files = com.example.service.SharedFileRegistry.sharedFiles.value
        assertTrue(files.any { it.name == "ProjectReport.pdf" })

        val html = com.example.service.WebPortalHtmlGenerator.generateHtml(
            deviceModel = "TestPhone",
            serverIp = "192.168.1.50",
            port = 8080,
            sharedFiles = files
        )
        assertNotNull(html)
        assertTrue(html.contains("ProjectReport.pdf"))
        assertTrue(html.contains("MediaSync.apk"))
        assertTrue(html.contains("/download/file?id=test_doc_1"))

        com.example.service.SharedFileRegistry.removeFile("test_doc_1")
    }

    @Test
    fun receivedFileRegistryTest() {
        val received = com.example.service.ReceivedFileItem(
            fileName = "photo_pc.jpg",
            sizeBytes = 204800L,
            uri = android.net.Uri.parse("file:///sdcard/photo_pc.jpg"),
            mimeType = "image/jpeg"
        )
        com.example.service.ReceivedFileRegistry.addReceivedFile(received)
        val list = com.example.service.ReceivedFileRegistry.receivedFiles.value
        assertTrue(list.any { it.fileName == "photo_pc.jpg" })
        assertTrue(received.isImage)
        assertEquals("200.0 KB", received.formattedSize)

        com.example.service.ReceivedFileRegistry.removeReceivedFile(received.id)
    }

    @Test
    fun securityManagerTest() {
        val pin = com.example.service.TransferSecurityManager.pinCode.value
        assertNotNull(pin)
        assertEquals(6, pin.length)

        val token = com.example.service.TransferSecurityManager.authToken.value
        assertNotNull(token)
        assertTrue(token.isNotEmpty())

        assertTrue(com.example.service.TransferSecurityManager.validateTokenOrPin(pin))
        assertTrue(com.example.service.TransferSecurityManager.validateTokenOrPin(token))
        assertFalse(com.example.service.TransferSecurityManager.validateTokenOrPin("000000_wrong"))

        val newPin = com.example.service.TransferSecurityManager.regeneratePin()
        assertEquals(6, newPin.length)
        assertTrue(com.example.service.TransferSecurityManager.validateTokenOrPin(newPin))
    }

    @Test
    fun directShareQueryFileInfoTest() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dummyFile = java.io.File(context.cacheDir, "gallery_photo.jpg").apply {
            writeBytes("fake jpeg data content".toByteArray())
        }
        val uri = android.net.Uri.fromFile(dummyFile)
        val (name, size, mime) = com.example.util.FileUtils.queryFileInfo(context, uri)
        assertNotNull(name)
        assertTrue(name.contains("gallery_photo.jpg"))
        assertEquals(dummyFile.length(), size)
        assertEquals("image/jpeg", mime)
        dummyFile.delete()
    }

    @Test
    fun appUpdateManagerAndVersionTest() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val versionInfo = com.example.service.AppUpdateManager.getVersionInfo(context)
        assertNotNull(versionInfo)
        assertTrue(versionInfo.versionCode >= 1L)
        assertNotNull(versionInfo.versionName)

        val versionJson = com.example.service.AppUpdateManager.getVersionJson(context)
        assertEquals("MediaSync", versionJson.getString("app"))
        assertEquals("/download/latest-apk", versionJson.getString("downloadUrl"))

        val liveApk = com.example.service.AppUpdateManager.getLiveApkFile(context)
        assertNotNull(liveApk)
    }

    @Test
    fun apkSharingHelperTest() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val apkFile = com.example.util.ApkSharingHelper.getShareableApkFile(context)
        assertNotNull(apkFile)
        val shareIntent = com.example.util.ApkSharingHelper.createShareApkIntent(context)
        assertNotNull(shareIntent)
        assertEquals("application/vnd.android.package-archive", shareIntent.type)
    }

    @Test
    fun packageInstallerHelperPermissionCheckTest() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // In robolectric default environment, should not throw
        val canInstall = com.example.service.PackageInstallerHelper.canInstallApks(context)
        assertNotNull(canInstall)
    }

    @Test
    fun peerConnectionManagerPairingAndDecisionTest() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        com.example.service.PeerConnectionManager.init(context)

        val testPeer = com.example.data.model.ConnectedPeer(
            id = "test-peer-id",
            deviceName = "Galaxy S24 Ultra",
            ipAddress = "192.168.1.155",
            httpPort = 8080,
            isPaired = true,
            isOnline = true
        )

        com.example.service.PeerConnectionManager.savePairedPeer(context, testPeer)
        assertTrue(com.example.service.PeerConnectionManager.isPeerPaired(context, "192.168.1.155"))

        val incomingReq = com.example.service.IncomingTransferRequest(
            id = "req-999",
            senderDevice = "Pixel 8 Pro",
            senderIp = "192.168.1.160",
            senderPort = 8080,
            fileCount = 2,
            totalSizeBytes = 2048000L,
            fileNames = listOf("doc1.pdf", "image1.jpg"),
            isSavedPeer = false
        )

        // Accept and remember device
        com.example.service.PeerConnectionManager.acceptRequest(context, incomingReq.id, rememberDevice = true)
        // Also verify PeerManager integration
        val addedPeer = com.example.service.PeerManager.addOrUpdatePeer(
            deviceName = "Pixel 8 Pro",
            ipAddress = "192.168.1.160",
            port = 8080,
            isPaired = true
        )
        assertEquals("Pixel 8 Pro", addedPeer.deviceName)
        assertTrue(addedPeer.isPaired)
    }

    @Test
    fun qrGeneratorZxingStandardTest() {
        val testUrl = "http://10.198.104.245:8080/?token=sec_9876543210ab"
        val bitmap = QrGenerator.generateQrCode(testUrl, 400)
        assertNotNull(bitmap)
        assertEquals(400, bitmap?.width)
        assertEquals(400, bitmap?.height)
    }
}
