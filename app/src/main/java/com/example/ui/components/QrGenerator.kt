package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.EnumMap

/**
 * Standard QR Code generator using ZXing QRCodeWriter.
 * Produces 100% ISO/IEC 18004 compliant QR codes with standard alignment patterns,
 * timing patterns, and BCH format strings that scan immediately and reliably on
 * iPhone cameras, Android cameras, Samsung Camera, Google Lens, and all scanners.
 */
object QrGenerator {

    private const val TAG = "QrGenerator"

    /**
     * Generates an ISO/IEC 18004 standard QR Code bitmap.
     * Guarantees:
     * - Minimum 4-module quiet zone (MARGIN = 4) mandated by camera barcode standards
     * - Medium error correction level (M - 15% redundancy) for robust error recovery
     * - High-contrast pure black modules (#000000) on pure white background (#FFFFFF)
     * - Explicit UTF-8 character encoding
     */
    fun generateQrCode(text: String, size: Int = 512): Bitmap? {
        if (text.isBlank()) return null
        return try {
            val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
                put(EncodeHintType.CHARACTER_SET, "UTF-8")
                put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M)
                put(EncodeHintType.MARGIN, 4) // Mandated by ISO/IEC 18004 quiet zone rules
            }

            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, size, size, hints)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val pixels = IntArray(width * height)

            for (y in 0 until height) {
                val offset = y * width
                for (x in 0 until width) {
                    pixels[offset + x] = if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate standard QR code bitmap", e)
            null
        }
    }
}
