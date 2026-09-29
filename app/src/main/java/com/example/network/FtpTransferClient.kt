package com.example.network

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.regex.Pattern

class FtpTransferClient(private val context: Context) {

    suspend fun uploadFile(
        hostIp: String,
        port: Int,
        user: String,
        pass: String,
        fileUri: Uri,
        fileName: String
    ): TransferResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        var controlSocket: Socket? = null
        var dataSocket: Socket? = null
        var reader: BufferedReader? = null
        var writer: PrintWriter? = null
        var inputStream: InputStream? = null

        try {
            // 1. Open control connection
            controlSocket = Socket()
            controlSocket.connect(InetSocketAddress(hostIp, port), 10000)
            controlSocket.soTimeout = 15000

            reader = BufferedReader(InputStreamReader(controlSocket.getInputStream()))
            writer = PrintWriter(controlSocket.getOutputStream(), true)

            // Read welcome
            val welcome = readResponse(reader)
            if (!welcome.startsWith("220")) {
                return@withContext TransferResult.Error("FTP Server error: $welcome")
            }

            // USER
            writer.println("USER $user")
            val userResp = readResponse(reader)
            if (userResp.startsWith("331")) {
                // PASS
                writer.println("PASS $pass")
                val passResp = readResponse(reader)
                if (!passResp.startsWith("230")) {
                    return@withContext TransferResult.Error("FTP Auth failed: $passResp")
                }
            } else if (!userResp.startsWith("230")) {
                return@withContext TransferResult.Error("FTP User rejected: $userResp")
            }

            // TYPE I (Binary mode)
            writer.println("TYPE I")
            readResponse(reader)

            // PASV (Passive mode)
            writer.println("PASV")
            val pasvResp = readResponse(reader)
            if (!pasvResp.startsWith("227")) {
                return@withContext TransferResult.Error("PASV mode failed: $pasvResp")
            }

            // Parse PASV host & port: 227 Entering Passive Mode (192,168,1,100,8,123)
            val matcher = Pattern.compile("\\((\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)\\)").matcher(pasvResp)
            if (!matcher.find()) {
                return@withContext TransferResult.Error("Failed to parse PASV response: $pasvResp")
            }

            val dataIp = "${matcher.group(1)}.${matcher.group(2)}.${matcher.group(3)}.${matcher.group(4)}"
            val dataPort = (matcher.group(5)!!.toInt() shl 8) + matcher.group(6)!!.toInt()

            // Open Data connection (prefer hostIp if dataIp is 0.0.0.0 or internal)
            val resolvedDataIp = if (dataIp == "0.0.0.0" || dataIp == "127.0.0.1") hostIp else dataIp
            dataSocket = Socket()
            dataSocket.connect(InetSocketAddress(resolvedDataIp, dataPort), 10000)

            // STOR filename
            val cleanName = fileName.replace(" ", "_").replace("/", "_")
            writer.println("STOR $cleanName")
            val storResp = readResponse(reader)
            if (!storResp.startsWith("150") && !storResp.startsWith("125")) {
                return@withContext TransferResult.Error("STOR failed: $storResp")
            }

            // Stream file data
            inputStream = context.contentResolver.openInputStream(fileUri)
                ?: return@withContext TransferResult.Error("Failed to open file: $fileUri")

            val dataOut: OutputStream = dataSocket.getOutputStream()
            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalBytesSent = 0L

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                dataOut.write(buffer, 0, bytesRead)
                totalBytesSent += bytesRead
            }
            dataOut.flush()
            dataSocket.close()
            dataSocket = null

            // Read transfer complete status
            val completeResp = readResponse(reader)

            // Send QUIT
            try {
                writer.println("QUIT")
            } catch (ignored: Exception) {}

            val duration = System.currentTimeMillis() - startTime
            if (completeResp.startsWith("226") || completeResp.startsWith("250")) {
                TransferResult.Success(
                    bytesSent = totalBytesSent,
                    durationMs = duration,
                    message = "FTP Transfer complete: $completeResp"
                )
            } else {
                TransferResult.Success(
                    bytesSent = totalBytesSent,
                    durationMs = duration,
                    message = "Uploaded $totalBytesSent bytes (Response: $completeResp)"
                )
            }
        } catch (e: Exception) {
            TransferResult.Error("FTP transfer error: ${e.localizedMessage ?: e.javaClass.simpleName}", e)
        } finally {
            try { inputStream?.close() } catch (ignored: Exception) {}
            try { dataSocket?.close() } catch (ignored: Exception) {}
            try { controlSocket?.close() } catch (ignored: Exception) {}
        }
    }

    private fun readResponse(reader: BufferedReader): String {
        var line = reader.readLine() ?: ""
        // Handle multi-line responses (e.g., 220- ...)
        while (line.length >= 4 && line[3] == '-') {
            line = reader.readLine() ?: break
        }
        return line
    }
}
