package com.example.service

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.data.model.ConnectedPeer
import com.example.util.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets

object UdpDiscoveryHelper {

    private const val TAG = "UdpDiscoveryHelper"
    const val UDP_DISCOVERY_PORT = 8889
    private const val MAGIC_DISCOVERY_REQUEST = "MEDIA_SYNC_DISCOVER_REQUEST_V1"
    private const val MAGIC_DISCOVERY_RESPONSE_PREFIX = "MEDIA_SYNC_DISCOVER_RESPONSE_V1:"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var listenerJob: Job? = null
    private var listenerSocket: DatagramSocket? = null

    /**
     * Starts listening for UDP broadcast beacons from other nearby MediaSync devices.
     */
    fun startListener(context: Context, httpPort: Int = 8080) {
        if (listenerJob?.isActive == true) return

        listenerJob = scope.launch {
            try {
                val socket = DatagramSocket(UDP_DISCOVERY_PORT)
                socket.broadcast = true
                listenerSocket = socket
                Log.i(TAG, "UDP Discovery Listener started on port $UDP_DISCOVERY_PORT")

                val buffer = ByteArray(2048)
                while (isActive) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val message = String(packet.data, 0, packet.length, StandardCharsets.UTF_8).trim()
                        val senderIp = packet.address.hostAddress ?: ""
                        val localIp = NetworkUtils.getLocalIpAddress(context)

                        if (senderIp == localIp || senderIp == "127.0.0.1") {
                            continue
                        }

                        if (message.startsWith(MAGIC_DISCOVERY_REQUEST)) {
                            // Extract sender info from request if available
                            val parts = message.split(";")
                            val senderDevice = if (parts.size > 1) parts[1] else "MediaSync Phone ($senderIp)"
                            val senderPort = if (parts.size > 2) parts[2].toIntOrNull() ?: 8080 else 8080

                            // Auto-register the sender device
                            PeerManager.addOrUpdatePeer(
                                deviceName = senderDevice,
                                ipAddress = senderIp,
                                port = senderPort,
                                isPaired = PeerConnectionManager.isPeerPaired(context, senderIp)
                            )

                            // Reply back with our device name and HTTP port
                            val myDeviceName = "Android (${Build.MODEL})"
                            val replyText = "$MAGIC_DISCOVERY_RESPONSE_PREFIX$myDeviceName;$httpPort;$localIp"
                            val replyBytes = replyText.toByteArray(StandardCharsets.UTF_8)
                            val replyPacket = DatagramPacket(
                                replyBytes,
                                replyBytes.size,
                                packet.address,
                                packet.port
                            )
                            socket.send(replyPacket)
                            Log.i(TAG, "Replied to UDP discovery from $senderIp ($senderDevice)")
                        }
                    } catch (e: Exception) {
                        if (!isActive) break
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "UDP Discovery Listener error: ${e.message}")
            } finally {
                try { listenerSocket?.close() } catch (_: Exception) {}
                listenerSocket = null
            }
        }
    }

    fun stopListener() {
        try {
            listenerJob?.cancel()
            listenerJob = null
            listenerSocket?.close()
            listenerSocket = null
            Log.i(TAG, "UDP Discovery Listener stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping UDP listener", e)
        }
    }

    /**
     * Broadcasts a discovery packet across all network interfaces to discover peers in <50ms.
     */
    suspend fun broadcastDiscovery(
        context: Context,
        onPeerFound: ((ConnectedPeer) -> Unit)? = null
    ) {
        try {
            val localIp = NetworkUtils.getLocalIpAddress(context)
            val myDeviceName = "Android (${Build.MODEL})"
            val message = "$MAGIC_DISCOVERY_REQUEST;$myDeviceName;8080;$localIp"
            val messageBytes = message.toByteArray(StandardCharsets.UTF_8)

            val socket = DatagramSocket()
            socket.broadcast = true
            socket.soTimeout = 800 // 800ms quick listening window

            val broadcastTargets = getBroadcastAddresses()
            for (addr in broadcastTargets) {
                try {
                    val packet = DatagramPacket(messageBytes, messageBytes.size, addr, UDP_DISCOVERY_PORT)
                    socket.send(packet)
                } catch (_: Exception) {}
            }

            val endTime = System.currentTimeMillis() + 800
            val buffer = ByteArray(2048)

            while (System.currentTimeMillis() < endTime) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val reply = String(packet.data, 0, packet.length, StandardCharsets.UTF_8).trim()
                    val peerIp = packet.address.hostAddress ?: continue

                    if (peerIp == localIp || peerIp == "127.0.0.1") continue

                    if (reply.startsWith(MAGIC_DISCOVERY_RESPONSE_PREFIX)) {
                        val payload = reply.removePrefix(MAGIC_DISCOVERY_RESPONSE_PREFIX)
                        val parts = payload.split(";")
                        val deviceName = if (parts.isNotEmpty()) parts[0] else "MediaSync Phone ($peerIp)"
                        val port = if (parts.size > 1) parts[1].toIntOrNull() ?: 8080 else 8080
                        val isPaired = PeerConnectionManager.isPeerPaired(context, peerIp)

                        val peer = PeerManager.addOrUpdatePeer(
                            deviceName = deviceName,
                            ipAddress = peerIp,
                            port = port,
                            isPaired = isPaired
                        )
                        onPeerFound?.invoke(peer)
                        Log.i(TAG, "Discovered UDP Peer: $deviceName ($peerIp:$port)")
                    }
                } catch (_: SocketTimeoutException) {
                    break
                } catch (_: Exception) {
                    break
                }
            }

            socket.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error during UDP discovery broadcast: ${e.message}")
        }
    }

    private fun getBroadcastAddresses(): List<InetAddress> {
        val list = mutableListOf<InetAddress>()
        try {
            list.add(InetAddress.getByName("255.255.255.255"))
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return list
            while (interfaces.hasMoreElements()) {
                val netIf = interfaces.nextElement()
                if (netIf.isLoopback || !netIf.isUp) continue
                for (interfaceAddress in netIf.interfaceAddresses) {
                    val broadcast = interfaceAddress.broadcast
                    if (broadcast != null && !list.contains(broadcast)) {
                        list.add(broadcast)
                    }
                }
            }
        } catch (_: Exception) {}
        return list
    }
}
