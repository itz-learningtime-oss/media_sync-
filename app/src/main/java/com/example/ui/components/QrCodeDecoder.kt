package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI

data class PcConnectionInfo(
    val hostIp: String,
    val httpPort: Int = 8000,
    val httpEndpoint: String = "/upload",
    val ftpPort: Int = 2121,
    val ftpUser: String = "anonymous",
    val ftpPass: String = "",
    val serverName: String = "PC Server",
    val authToken: String? = null,
    val rawContent: String = ""
)

object QrCodeDecoder {

    private val reader = MultiFormatReader().apply {
        val hints = mapOf<DecodeHintType, Any>(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.CHARACTER_SET to "UTF-8"
        )
        setHints(hints)
    }

    /**
     * Decode QR code from CameraX ImageProxy (YUV_420_888 format)
     */
    fun decodeImageProxy(imageProxy: ImageProxy): String? {
        return try {
            val planes = imageProxy.planes
            val yBuffer = planes[0].buffer
            val ySize = yBuffer.remaining()
            val yArray = ByteArray(ySize)
            yBuffer.get(yArray)

            val width = imageProxy.width
            val height = imageProxy.height

            val source = PlanarYUVLuminanceSource(
                yArray,
                width,
                height,
                0,
                0,
                width,
                height,
                false
            )
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
            val result = reader.decodeWithState(binaryBitmap)
            result.text
        } catch (e: Exception) {
            null
        } finally {
            reader.reset()
        }
    }

    /**
     * Decode QR code from a Bitmap (e.g. from photo picker or screenshot)
     */
    fun decodeBitmap(bitmap: Bitmap): String? {
        return try {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            val source = RGBLuminanceSource(width, height, pixels)
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
            val result = reader.decodeWithState(binaryBitmap)
            result.text
        } catch (e: Exception) {
            null
        } finally {
            reader.reset()
        }
    }

    /**
     * Intelligently parses raw QR text into PcConnectionInfo.
     * Supports:
     * 1. JSON payload: {"ip": "192.168.1.15", "port": 8000, "ftp_port": 2121, "name": "PC-Connect"}
     * 2. URL: http://192.168.1.15:8000/upload or http://192.168.1.15:8000
     * 3. IP:Port string: 192.168.1.15:8000
     * 4. Plain IP: 192.168.1.15
     * 5. URI scheme: pc-connect://192.168.1.15:8000 or mediasync://192.168.1.15:8000
     */
    fun parsePcConnectionInfo(rawText: String): PcConnectionInfo? {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return null

        // 1. Try JSON parsing
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                val json = JSONObject(trimmed)
                val ip = json.optString("ip").ifEmpty {
                    json.optString("host").ifEmpty {
                        json.optString("server_ip")
                    }
                }
                if (isValidIpOrHost(ip)) {
                    val httpPort = json.optInt("port", json.optInt("http_port", 8000))
                    val endpoint = json.optString("endpoint", json.optString("upload_endpoint", "/upload")).let {
                        if (it.startsWith("/")) it else "/$it"
                    }
                    val ftpPort = json.optInt("ftp_port", 2121)
                    val ftpUser = json.optString("ftp_user", json.optString("user", "anonymous"))
                    val ftpPass = json.optString("ftp_pass", json.optString("password", ""))
                    val name = json.optString("name", json.optString("pc_name", "PC Server"))
                    val token = json.optString("token").ifEmpty { json.optString("pin").ifEmpty { null } }
                    return PcConnectionInfo(
                        hostIp = ip,
                        httpPort = if (httpPort in 1..65535) httpPort else 8000,
                        httpEndpoint = endpoint,
                        ftpPort = if (ftpPort in 1..65535) ftpPort else 2121,
                        ftpUser = ftpUser,
                        ftpPass = ftpPass,
                        serverName = name,
                        authToken = token,
                        rawContent = trimmed
                    )
                }
            } catch (_: Exception) {
                // Fallthrough to URI parsing
            }
        }

        // 2. Try URI parsing (http://..., https://..., pc-connect://..., mediasync://...)
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("pc-connect://", ignoreCase = true) ||
            trimmed.startsWith("mediasync://", ignoreCase = true)
        ) {
            try {
                // Normalize custom scheme to http for java.net.URI parsing
                val normalizedUriString = trimmed
                    .replaceFirst("(?i)^pc-connect://".toRegex(), "http://")
                    .replaceFirst("(?i)^mediasync://".toRegex(), "http://")

                val uri = URI.create(normalizedUriString)
                val host = uri.host ?: ""
                val port = if (uri.port != -1) uri.port else 8000
                val path = if (!uri.path.isNullOrEmpty() && uri.path != "/") uri.path else "/upload"
                val query = uri.query ?: ""
                val token = when {
                    query.contains("token=") -> query.substringAfter("token=").substringBefore("&")
                    query.contains("pin=") -> query.substringAfter("pin=").substringBefore("&")
                    else -> null
                }

                if (isValidIpOrHost(host)) {
                    return PcConnectionInfo(
                        hostIp = host,
                        httpPort = port,
                        httpEndpoint = path,
                        serverName = "Device ($host)",
                        authToken = token,
                        rawContent = trimmed
                    )
                }
            } catch (_: Exception) {
                // Fallthrough
            }
        }

        // 3. Try IP:Port format (e.g. 192.168.1.15:8000)
        if (trimmed.contains(":")) {
            val parts = trimmed.split(":")
            if (parts.size == 2) {
                val ip = parts[0].trim()
                val portStr = parts[1].trim()
                val port = portStr.toIntOrNull()
                if (isValidIpOrHost(ip) && port != null && port in 1..65535) {
                    return PcConnectionInfo(
                        hostIp = ip,
                        httpPort = port,
                        serverName = "PC ($ip)",
                        rawContent = trimmed
                    )
                }
            }
        }

        // 4. Try Plain IP address (e.g. 192.168.1.15)
        if (isValidIpOrHost(trimmed)) {
            return PcConnectionInfo(
                hostIp = trimmed,
                httpPort = 8000,
                httpEndpoint = "/upload",
                serverName = "PC ($trimmed)",
                rawContent = trimmed
            )
        }

        // 5. Look for any IPv4 address pattern inside random string
        val ipRegex = Regex("""\b(?:[0-9]{1,3}\.){3}[0-9]{1,3}\b""")
        val match = ipRegex.find(trimmed)
        if (match != null) {
            val extractedIp = match.value
            if (isValidIpOrHost(extractedIp)) {
                // Check if port immediately follows
                val afterIp = trimmed.substring(match.range.last + 1)
                var port = 8000
                if (afterIp.startsWith(":")) {
                    val portDigits = afterIp.substring(1).takeWhile { it.isDigit() }
                    port = portDigits.toIntOrNull() ?: 8000
                }
                return PcConnectionInfo(
                    hostIp = extractedIp,
                    httpPort = port,
                    serverName = "PC ($extractedIp)",
                    rawContent = trimmed
                )
            }
        }

        return null
    }

    private fun isValidIpOrHost(host: String): Boolean {
        if (host.isBlank()) return false
        // IPv4 format check
        val parts = host.split(".")
        if (parts.size == 4) {
            return parts.all { part ->
                part.toIntOrNull()?.let { it in 0..255 } ?: false
            }
        }
        // Localhost / hostnames
        return host.equals("localhost", ignoreCase = true) || host.matches(Regex("""^[a-zA-Z0-9.-]+$"""))
    }
}
