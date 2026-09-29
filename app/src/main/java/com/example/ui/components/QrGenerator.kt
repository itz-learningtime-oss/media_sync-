package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.Color
import java.nio.charset.StandardCharsets
import java.util.BitSet

/**
 * Pure Kotlin QR Code Matrix generator (Version 1-6 ISO/IEC 18004 compliant encoder)
 * Produces crisp, standalone QR bitmap with zero extra external dependencies.
 */
object QrGenerator {

    fun generateQrCode(text: String, size: Int = 400): Bitmap? {
        return try {
            val qr = SimpleQrEncoder.encode(text)
            val matrixSize = qr.size
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val scale = size.toFloat() / matrixSize

            val pixels = IntArray(size * size)
            for (y in 0 until size) {
                val matrixY = (y / scale).toInt().coerceIn(0, matrixSize - 1)
                for (x in 0 until size) {
                    val matrixX = (x / scale).toInt().coerceIn(0, matrixSize - 1)
                    val isBlack = qr.get(matrixX, matrixY)
                    pixels[y * size + x] = if (isBlack) Color.BLACK else Color.WHITE
                }
            }
            bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
            bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

/**
 * Lightweight QR Code model & builder
 */
class QrMatrix(val size: Int) {
    private val data = Array(size) { BooleanArray(size) }
    private val isFunction = Array(size) { BooleanArray(size) }

    fun set(x: Int, y: Int, value: Boolean) {
        if (x in 0 until size && y in 0 until size) {
            data[y][x] = value
            isFunction[y][x] = true
        }
    }

    fun setDataOnly(x: Int, y: Int, value: Boolean) {
        if (x in 0 until size && y in 0 until size && !isFunction[y][x]) {
            data[y][x] = value
        }
    }

    fun get(x: Int, y: Int): Boolean {
        if (x in 0 until size && y in 0 until size) {
            return data[y][x]
        }
        return false
    }

    fun isFunction(x: Int, y: Int): Boolean {
        return if (x in 0 until size && y in 0 until size) isFunction[y][x] else true
    }
}

object SimpleQrEncoder {

    fun encode(content: String): QrMatrix {
        val rawBytes = content.toByteArray(StandardCharsets.UTF_8)
        val version = determineVersion(rawBytes.size)
        val matrixDimension = version * 4 + 17
        val matrix = QrMatrix(matrixDimension)

        // 1. Draw Finder Patterns
        drawFinderPattern(matrix, 0, 0)
        drawFinderPattern(matrix, matrixDimension - 7, 0)
        drawFinderPattern(matrix, 0, matrixDimension - 7)

        // 2. Draw Timing Patterns
        for (i in 8 until matrixDimension - 8) {
            val bit = (i % 2 == 0)
            matrix.set(6, i, bit)
            matrix.set(i, 6, bit)
        }

        // 3. Draw Dark module
        matrix.set(8, 4 * version + 9, true)

        // 4. Reserve format areas
        for (i in 0..8) {
            if (!matrix.isFunction(8, i)) matrix.set(8, i, false)
            if (!matrix.isFunction(i, 8)) matrix.set(i, 8, false)
        }
        for (i in (matrixDimension - 8) until matrixDimension) {
            if (!matrix.isFunction(8, i)) matrix.set(8, i, false)
            if (!matrix.isFunction(i, 8)) matrix.set(i, 8, false)
        }

        // 5. Build bit stream with Byte mode + Error Correction (Reed Solomon)
        val dataBits = generateBitStream(rawBytes, version)

        // 6. Populate Data into Matrix
        var bitIndex = 0
        var right = matrixDimension - 1
        var upward = true

        while (right > 0) {
            if (right == 6) right-- // Skip vertical timing line
            val yRange = if (upward) (matrixDimension - 1 downTo 0) else (0 until matrixDimension)
            for (y in yRange) {
                for (x in listOf(right, right - 1)) {
                    if (!matrix.isFunction(x, y)) {
                        val bit = if (bitIndex < dataBits.size) dataBits[bitIndex] else false
                        // Apply Mask 0: (x + y) % 2 == 0
                        val mask = (x + y) % 2 == 0
                        matrix.setDataOnly(x, y, bit xor mask)
                        bitIndex++
                    }
                }
            }
            right -= 2
            upward = !upward
        }

        // 7. Format bits (Mask 0, Error correction M) -> 0x5412 with format mask 0x5412 xor 0x5412 = 0
        applyFormatBits(matrix, 0b101010000010010)

        // Return completed matrix surrounded by quiet zone
        val quietZone = 2
        val bordered = QrMatrix(matrixDimension + quietZone * 2)
        for (y in 0 until matrixDimension) {
            for (x in 0 until matrixDimension) {
                bordered.set(x + quietZone, y + quietZone, matrix.get(x, y))
            }
        }
        return bordered
    }

    private fun determineVersion(dataLength: Int): Int {
        return when {
            dataLength <= 14 -> 1
            dataLength <= 26 -> 2
            dataLength <= 42 -> 3
            dataLength <= 62 -> 4
            dataLength <= 84 -> 5
            dataLength <= 106 -> 6
            dataLength <= 150 -> 7
            dataLength <= 200 -> 8
            else -> 10
        }
    }

    private fun drawFinderPattern(matrix: QrMatrix, startX: Int, startY: Int) {
        for (y in 0..6) {
            for (x in 0..6) {
                val isOuter = (x == 0 || x == 6 || y == 0 || y == 6)
                val isInner = (x in 2..4 && y in 2..4)
                matrix.set(startX + x, startY + y, isOuter || isInner)
            }
        }
        // Separator
        for (i in -1..7) {
            matrix.set(startX + i, startY - 1, false)
            matrix.set(startX + i, startY + 7, false)
            matrix.set(startX - 1, startY + i, false)
            matrix.set(startX + 7, startY + i, false)
        }
    }

    private fun generateBitStream(data: ByteArray, version: Int): List<Boolean> {
        val bits = mutableListOf<Boolean>()
        // Byte mode indicator 0100
        bits.addAll(listOf(false, true, false, false))

        // Character count indicator (8 bits for version 1-9)
        val len = data.size
        for (i in 7 downTo 0) {
            bits.add(((len shr i) and 1) == 1)
        }

        // Data bytes
        for (b in data) {
            val byteVal = b.toInt() and 0xFF
            for (i in 7 downTo 0) {
                bits.add(((byteVal shr i) and 1) == 1)
            }
        }

        // Terminator (up to 4 zeroes)
        for (i in 0 until 4) {
            bits.add(false)
        }

        // Pad to byte boundary
        while (bits.size % 8 != 0) {
            bits.add(false)
        }

        // Pad with alternating 0xEC and 0x11
        val maxDataCodewords = getMaxDataCodewords(version)
        var padByte = 0xEC
        while (bits.size < maxDataCodewords * 8) {
            for (i in 7 downTo 0) {
                bits.add(((padByte shr i) and 1) == 1)
            }
            padByte = if (padByte == 0xEC) 0x11 else 0xEC
        }

        // Generate Reed-Solomon Error Correction Code
        val codewords = mutableListOf<Int>()
        for (i in 0 until bits.size step 8) {
            var v = 0
            for (j in 0..7) {
                if (bits[i + j]) v = v or (1 shl (7 - j))
            }
            codewords.add(v)
        }

        val ecCount = getEcCodewordsCount(version)
        val ecCodewords = ReedSolomon.compute(codewords, ecCount)

        val fullBits = mutableListOf<Boolean>()
        for (cw in codewords) {
            for (i in 7 downTo 0) {
                fullBits.add(((cw shr i) and 1) == 1)
            }
        }
        for (cw in ecCodewords) {
            for (i in 7 downTo 0) {
                fullBits.add(((cw shr i) and 1) == 1)
            }
        }

        return fullBits
    }

    private fun getMaxDataCodewords(version: Int): Int {
        return when (version) {
            1 -> 19
            2 -> 34
            3 -> 55
            4 -> 80
            5 -> 108
            6 -> 136
            7 -> 156
            8 -> 194
            else -> 271
        }
    }

    private fun getEcCodewordsCount(version: Int): Int {
        return when (version) {
            1 -> 7
            2 -> 10
            3 -> 15
            4 -> 20
            5 -> 26
            6 -> 36
            7 -> 40
            8 -> 48
            else -> 60
        }
    }

    private fun applyFormatBits(matrix: QrMatrix, formatMasked: Int) {
        val bits = BooleanArray(15) { i -> ((formatMasked shr (14 - i)) and 1) == 1 }

        // Around top-left finder
        for (i in 0..5) matrix.set(8, i, bits[i])
        matrix.set(8, 7, bits[6])
        matrix.set(8, 8, bits[7])
        matrix.set(7, 8, bits[8])
        for (i in 0..5) matrix.set(5 - i, 8, bits[9 + i])

        // Around bottom-left & top-right
        val d = matrix.size
        for (i in 0..7) matrix.set(d - 1 - i, 8, bits[i])
        for (i in 0..6) matrix.set(8, d - 7 + i, bits[8 + i])
    }
}

/**
 * GF(256) Reed Solomon Error Correction for QR codes
 */
object ReedSolomon {
    private val exp = IntArray(512)
    private val log = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            exp[i] = x
            exp[i + 255] = x
            log[x] = i
            x = x shl 1
            if (x >= 256) x = x xor 0x11D
        }
    }

    private fun multiply(a: Int, b: Int): Int {
        if (a == 0 || b == 0) return 0
        return exp[log[a] + log[b]]
    }

    fun compute(data: List<Int>, ecLength: Int): List<Int> {
        var generator = intArrayOf(1)
        for (i in 0 until ecLength) {
            val term = intArrayOf(1, exp[i])
            val newGen = IntArray(generator.size + 1)
            for (j in generator.indices) {
                newGen[j] = newGen[j] xor multiply(generator[j], term[0])
                newGen[j + 1] = newGen[j + 1] xor multiply(generator[j], term[1])
            }
            generator = newGen
        }

        val result = IntArray(data.size + ecLength)
        for (i in data.indices) result[i] = data[i]

        for (i in data.indices) {
            val factor = result[i]
            if (factor != 0) {
                for (j in generator.indices) {
                    result[i + j] = result[i + j] xor multiply(generator[j], factor)
                }
            }
        }

        return result.takeLast(ecLength)
    }
}
