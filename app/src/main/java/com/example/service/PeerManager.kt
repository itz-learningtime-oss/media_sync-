package com.example.service

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.data.model.ConnectedPeer
import com.example.data.model.PeerTransferStatus
import com.example.util.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Manages connected local network peers (other Android phones, PC instances, iOS browsers)
 * connected on the same Wi-Fi router or Mobile Hotspot with high-speed multi-subnet discovery.
 */
object PeerManager {

    private const val TAG = "PeerManager"

    private val peerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var discoveryJob: Job? = null
    private var heartbeatJob: Job? = null

    private val httpClient = OkHttpClient.Builder()
        .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.NANOSECONDS))
        .retryOnConnectionFailure(true)
        .connectTimeout(800, TimeUnit.MILLISECONDS)
        .readTimeout(1000, TimeUnit.MILLISECONDS)
        .build()

    private val _peers = MutableStateFlow<List<ConnectedPeer>>(emptyList())
    val peers: StateFlow<List<ConnectedPeer>> = _peers.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private val _discoveryProgress = MutableStateFlow(0f)
    val discoveryProgress: StateFlow<Float> = _discoveryProgress.asStateFlow()

    fun addOrUpdatePeer(
        deviceName: String,
        ipAddress: String,
        port: Int = 8080,
        authToken: String? = null,
        isHotspotGateway: Boolean = false,
        isPaired: Boolean = false,
        isOnline: Boolean = true
    ): ConnectedPeer {
        val current = _peers.value.toMutableList()
        val existingIndex = current.indexOfFirst { it.ipAddress == ipAddress }

        val peer = if (existingIndex != -1) {
            val existing = current[existingIndex]
            existing.copy(
                deviceName = if (deviceName.isNotBlank()) deviceName else existing.deviceName,
                httpPort = port,
                authToken = authToken ?: existing.authToken,
                isOnline = isOnline,
                isPaired = isPaired || existing.isPaired,
                lastSeenMillis = System.currentTimeMillis(),
                isHotspotGateway = isHotspotGateway || existing.isHotspotGateway
            ).also {
                current[existingIndex] = it
            }
        } else {
            ConnectedPeer(
                deviceName = deviceName.ifBlank { "Mobile Peer ($ipAddress)" },
                ipAddress = ipAddress,
                httpPort = port,
                authToken = authToken,
                isOnline = isOnline,
                isSelected = true,
                isPaired = isPaired,
                lastSeenMillis = System.currentTimeMillis(),
                isHotspotGateway = isHotspotGateway
            ).also {
                current.add(0, it)
            }
        }

        _peers.value = current
        Log.i(TAG, "Registered peer: ${peer.deviceName} (${peer.ipAddress}:${peer.httpPort}), isPaired=${peer.isPaired}, isOnline=${peer.isOnline}")
        return peer
    }

    fun loadSavedPeers(context: Context) {
        val saved = PeerConnectionManager.loadSavedPeers(context)
        val current = _peers.value.toMutableList()
        for (sp in saved) {
            val idx = current.indexOfFirst { it.ipAddress == sp.ipAddress }
            if (idx != -1) {
                current[idx] = current[idx].copy(
                    isPaired = true,
                    authToken = sp.authToken ?: current[idx].authToken
                )
            } else {
                current.add(0, sp.copy(isOnline = true, isSelected = true, isPaired = true))
            }
        }
        _peers.value = current
        Log.i(TAG, "Loaded ${saved.size} saved paired peers into PeerManager (total: ${current.size})")
    }

    fun removePeer(id: String) {
        _peers.value = _peers.value.filter { it.id != id }
    }

    fun clearAll() {
        _peers.value = emptyList()
    }

    fun togglePeerSelection(id: String) {
        _peers.value = _peers.value.map {
            if (it.id == id) it.copy(isSelected = !it.isSelected) else it
        }
    }

    fun selectAll(select: Boolean) {
        _peers.value = _peers.value.map { it.copy(isSelected = select) }
    }

    fun updatePeerTransferStatus(
        peerId: String,
        status: PeerTransferStatus,
        progress: Float = 0f,
        statusMessage: String? = null
    ) {
        _peers.value = _peers.value.map {
            if (it.id == peerId) {
                it.copy(
                    transferStatus = status,
                    transferProgress = progress,
                    transferStatusMessage = statusMessage
                )
            } else it
        }
    }

    fun resetAllTransferStatuses() {
        _peers.value = _peers.value.map {
            it.copy(
                transferStatus = PeerTransferStatus.IDLE,
                transferProgress = 0f,
                transferStatusMessage = null
            )
        }
    }

    /**
     * Starts a lightning-fast parallel discovery across all local network interfaces and UDP beacons.
     * Cancels any previously running scan so clicking 'Scan Network' ALWAYS triggers an instant fresh scan.
     */
    fun discoverLocalPeers(context: Context, onComplete: ((List<ConnectedPeer>) -> Unit)? = null) {
        discoveryJob?.cancel()

        discoveryJob = peerScope.launch {
            _isDiscovering.value = true
            _discoveryProgress.value = 0.05f

            // 1. Immediately load and display saved paired connections
            loadSavedPeers(context)

            val localIp = NetworkUtils.getLocalIpAddress(context)
            val myDeviceName = "Android (${Build.MODEL})"

            // 2. Launch ultra-fast UDP broadcast discovery in parallel (<50ms)
            launch {
                try {
                    UdpDiscoveryHelper.broadcastDiscovery(context) { discoveredPeer ->
                        Log.i(TAG, "UDP broadcast found peer: ${discoveredPeer.deviceName} (${discoveredPeer.ipAddress})")
                    }
                } catch (_: Exception) {}
            }

            // 3. Fast-ping all saved paired peers with high priority
            val savedPeers = PeerConnectionManager.loadSavedPeers(context)
            if (savedPeers.isNotEmpty()) {
                savedPeers.map { sp ->
                    async {
                        val alive = pingAndIdentify(sp.ipAddress, sp.httpPort, localIp, myDeviceName)
                        if (alive != null) {
                            addOrUpdatePeer(
                                deviceName = alive.deviceName,
                                ipAddress = sp.ipAddress,
                                port = sp.httpPort,
                                authToken = sp.authToken,
                                isPaired = true,
                                isOnline = true
                            )
                        }
                    }
                }.awaitAll()
            }

            _discoveryProgress.value = 0.25f

            // 4. Collect candidate IPs from all active IPv4 interfaces
            val candidateIps = getAllSubnetCandidateIps(context, localIp)

            if (candidateIps.isEmpty()) {
                _isDiscovering.value = false
                _discoveryProgress.value = 1f
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(_peers.value)
                }
                return@launch
            }

            // 5. Scan candidates in parallel with fast TCP socket probes (300ms timeout)
            val total = candidateIps.size
            var scanned = 0
            val batchSize = 50 // 50 parallel workers for 1-second total sweep

            for (batch in candidateIps.chunked(batchSize)) {
                if (!isActive) break

                val results = batch.map { targetIp ->
                    async {
                        if (isTcpPortOpen(targetIp, 8080, 250)) {
                            pingAndIdentify(targetIp, 8080, localIp, myDeviceName)
                        } else if (isTcpPortOpen(targetIp, 8000, 250)) {
                            pingAndIdentify(targetIp, 8000, localIp, myDeviceName)
                        } else {
                            null
                        }
                    }
                }.awaitAll()

                results.filterNotNull().forEach { peer ->
                    val isPaired = PeerConnectionManager.isPeerPaired(context, peer.ipAddress)
                    addOrUpdatePeer(
                        deviceName = peer.deviceName,
                        ipAddress = peer.ipAddress,
                        port = peer.httpPort,
                        authToken = peer.authToken,
                        isHotspotGateway = peer.isHotspotGateway,
                        isPaired = isPaired,
                        isOnline = true
                    )
                }

                scanned += batch.size
                _discoveryProgress.value = (0.25f + 0.75f * (scanned.toFloat() / total)).coerceIn(0f, 1f)
            }

            _isDiscovering.value = false
            _discoveryProgress.value = 1f
            Log.i(TAG, "Completed subnet discovery. Total online peers: ${_peers.value.size}")

            startPeerHeartbeat(context)

            withContext(Dispatchers.Main) {
                onComplete?.invoke(_peers.value)
            }
        }
    }

    /**
     * Starts a lightweight background heartbeat to periodically verify online peers.
     */
    fun startPeerHeartbeat(context: Context) {
        if (heartbeatJob?.isActive == true) return

        heartbeatJob = peerScope.launch {
            while (isActive) {
                delay(6000) // 6 seconds heartbeat interval
                try {
                    val currentPeers = _peers.value
                    if (currentPeers.isNotEmpty()) {
                        val localIp = NetworkUtils.getLocalIpAddress(context)
                        val myName = "Android (${Build.MODEL})"

                        currentPeers.map { p ->
                            async {
                                val alive = isTcpPortOpen(p.ipAddress, p.httpPort, 350)
                                if (alive) {
                                    addOrUpdatePeer(
                                        deviceName = p.deviceName,
                                        ipAddress = p.ipAddress,
                                        port = p.httpPort,
                                        authToken = p.authToken,
                                        isHotspotGateway = p.isHotspotGateway,
                                        isPaired = p.isPaired,
                                        isOnline = true
                                    )
                                } else {
                                    // Mark as offline if unresponsive
                                    _peers.value = _peers.value.map {
                                        if (it.id == p.id) it.copy(isOnline = false) else it
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun isTcpPortOpen(ip: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun getAllSubnetCandidateIps(context: Context, localIp: String): List<String> {
        val candidates = mutableListOf<String>()
        val prefixes = mutableSetOf<String>()

        if (localIp != "127.0.0.1" && localIp.isNotBlank() && localIp.contains(".")) {
            prefixes.add(localIp.substringBeforeLast("."))
        }

        // Also check all active network interfaces
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return candidates
            while (interfaces.hasMoreElements()) {
                val netIf = interfaces.nextElement()
                if (netIf.isLoopback || !netIf.isUp) continue
                for (addr in netIf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        if (!host.startsWith("127.") && host != "0.0.0.0" && host.contains(".")) {
                            prefixes.add(host.substringBeforeLast("."))
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // Standard Hotspot prefix
        prefixes.add("192.168.43")
        prefixes.add("192.168.49")

        for (prefix in prefixes) {
            // Prioritize gateway .1 and .254
            val g1 = "$prefix.1"
            if (g1 != localIp && !candidates.contains(g1)) {
                candidates.add(0, g1)
            }

            for (i in 2..253) {
                val ip = "$prefix.$i"
                if (ip != localIp && !candidates.contains(ip)) {
                    candidates.add(ip)
                }
            }
        }

        return candidates
    }

    fun pingAndIdentify(targetIp: String, port: Int, myIp: String, myDeviceName: String): ConnectedPeer? {
        return try {
            val url = "http://$targetIp:$port/ping?sender_ip=$myIp&sender_device=${java.net.URLEncoder.encode(myDeviceName, "UTF-8")}"
            val request = Request.Builder()
                .url(url)
                .addHeader("Connection", "close")
                .addHeader("User-Agent", "MediaSync-Discovery/1.1")
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                val json = JSONObject(body)
                val device = json.optString("device", "MediaSync Peer ($targetIp)")
                val isHotspot = targetIp.endsWith(".1") || targetIp == "192.168.43.1"
                ConnectedPeer(
                    deviceName = device,
                    ipAddress = targetIp,
                    httpPort = port,
                    isOnline = true,
                    isSelected = true,
                    isHotspotGateway = isHotspot
                )
            } else null
        } catch (_: Exception) {
            null
        }
    }
}
