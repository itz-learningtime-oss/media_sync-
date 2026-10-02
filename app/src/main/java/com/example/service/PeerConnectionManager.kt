package com.example.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.data.model.ConnectedPeer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class IncomingTransferRequest(
    val id: String = UUID.randomUUID().toString(),
    val senderDevice: String,
    val senderIp: String,
    val senderPort: Int = 8080,
    val fileCount: Int = 1,
    val totalSizeBytes: Long = 0L,
    val fileNames: List<String> = emptyList(),
    val isSavedPeer: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
) {
    val formattedSize: String
        get() {
            val kb = totalSizeBytes / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> String.format("%.2f GB", gb)
                mb >= 1.0 -> String.format("%.1f MB", mb)
                kb >= 1.0 -> String.format("%.1f KB", kb)
                else -> "$totalSizeBytes B"
            }
        }
}

object PeerConnectionManager {

    private const val TAG = "PeerConnectionManager"
    private const val PREFS_NAME = "mediasync_paired_peers"
    private const val KEY_PAIRED_LIST = "paired_peers_json"

    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val pendingRequestsData = ConcurrentHashMap<String, IncomingTransferRequest>()
    private val activeSessionTokens = ConcurrentHashMap<String, Long>() // Token -> Expiration timestamp

    private val _incomingRequest = MutableStateFlow<IncomingTransferRequest?>(null)
    val incomingRequest: StateFlow<IncomingTransferRequest?> = _incomingRequest.asStateFlow()

    private val _savedPeers = MutableStateFlow<List<ConnectedPeer>>(emptyList())
    val savedPeers: StateFlow<List<ConnectedPeer>> = _savedPeers.asStateFlow()

    fun init(context: Context) {
        loadSavedPeers(context)
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun loadSavedPeers(context: Context): List<ConnectedPeer> {
        val jsonStr = getPrefs(context).getString(KEY_PAIRED_LIST, null) ?: return emptyList()
        val list = mutableListOf<ConnectedPeer>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    ConnectedPeer(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        deviceName = obj.optString("deviceName", "Paired Phone"),
                        ipAddress = obj.optString("ipAddress", ""),
                        httpPort = obj.optInt("httpPort", 8080),
                        authToken = obj.optString("authToken", null),
                        isOnline = true,
                        isSelected = true,
                        isPaired = true,
                        lastSeenMillis = obj.optLong("lastSeenMillis", System.currentTimeMillis())
                    )
                )
            }
            _savedPeers.value = list
            Log.i(TAG, "Loaded ${list.size} saved paired peers from storage")
        } catch (e: Exception) {
            Log.e(TAG, "Failed loading saved paired peers", e)
        }
        return list
    }

    fun savePairedPeer(context: Context, peer: ConnectedPeer) {
        val current = _savedPeers.value.toMutableList()
        val existingIndex = current.indexOfFirst { it.ipAddress == peer.ipAddress }
        val updatedPeer = peer.copy(isPaired = true, isOnline = true, lastSeenMillis = System.currentTimeMillis())

        if (existingIndex != -1) {
            current[existingIndex] = updatedPeer
        } else {
            current.add(0, updatedPeer)
        }
        _savedPeers.value = current
        persistSavedPeers(context, current)
        // Also update in PeerManager so active UI sees it
        PeerManager.addOrUpdatePeer(
            deviceName = updatedPeer.deviceName,
            ipAddress = updatedPeer.ipAddress,
            port = updatedPeer.httpPort,
            authToken = updatedPeer.authToken,
            isPaired = true
        )
        Log.i(TAG, "Successfully saved paired connection: ${peer.deviceName} (${peer.ipAddress})")
    }

    fun removePairedPeer(context: Context, ipAddress: String) {
        val updated = _savedPeers.value.filter { it.ipAddress != ipAddress }
        _savedPeers.value = updated
        persistSavedPeers(context, updated)
        Log.i(TAG, "Removed paired connection for IP: $ipAddress")
    }

    fun isPeerPaired(context: Context, ipAddress: String): Boolean {
        if (_savedPeers.value.isEmpty()) {
            loadSavedPeers(context)
        }
        return _savedPeers.value.any { it.ipAddress == ipAddress }
    }

    private fun persistSavedPeers(context: Context, list: List<ConnectedPeer>) {
        try {
            val array = JSONArray()
            for (p in list) {
                val obj = JSONObject().apply {
                    put("id", p.id)
                    put("deviceName", p.deviceName)
                    put("ipAddress", p.ipAddress)
                    put("httpPort", p.httpPort)
                    put("authToken", p.authToken ?: "")
                    put("lastSeenMillis", p.lastSeenMillis)
                }
                array.put(obj)
            }
            getPrefs(context).edit().putString(KEY_PAIRED_LIST, array.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed persisting paired peers list", e)
        }
    }

    /**
     * Called by EmbeddedReceiverServer when an incoming peer requests connection & file transfer.
     * Suspends until the receiver user accepts or declines, or times out.
     */
    suspend fun awaitUserDecision(
        request: IncomingTransferRequest,
        context: Context,
        timeoutMs: Long = 45000L
    ): Pair<Boolean, String?> {
        val deferred = CompletableDeferred<Boolean>()
        pendingRequests[request.id] = deferred
        pendingRequestsData[request.id] = request
        _incomingRequest.value = request

        // Post high-priority notification with Accept/Decline action buttons
        NotificationHelper.showPeerTransferRequestNotification(context, request)

        Log.i(TAG, "Awaiting user confirmation for request ${request.id} from ${request.senderDevice} (${request.senderIp})")

        val result = withTimeoutOrNull(timeoutMs) {
            deferred.await()
        } ?: false

        pendingRequests.remove(request.id)
        pendingRequestsData.remove(request.id)
        if (_incomingRequest.value?.id == request.id) {
            _incomingRequest.value = null
        }
        NotificationHelper.dismissPeerTransferRequestNotification(context)

        return if (result) {
            val sessionToken = UUID.randomUUID().toString().replace("-", "")
            activeSessionTokens[sessionToken] = System.currentTimeMillis() + 600000L // 10 minutes valid
            Log.i(TAG, "Transfer request ${request.id} was ACCEPTED. Generated session token: $sessionToken")
            Pair(true, sessionToken)
        } else {
            Log.i(TAG, "Transfer request ${request.id} was DECLINED or TIMED OUT.")
            Pair(false, null)
        }
    }

    fun acceptRequest(context: Context, requestId: String, rememberDevice: Boolean = true) {
        val request = pendingRequestsData[requestId] ?: _incomingRequest.value
        val deferred = pendingRequests[requestId]

        if (request != null && rememberDevice) {
            val peer = ConnectedPeer(
                deviceName = request.senderDevice,
                ipAddress = request.senderIp,
                httpPort = request.senderPort,
                isPaired = true,
                isOnline = true
            )
            savePairedPeer(context, peer)
        }

        deferred?.complete(true)
        pendingRequests.remove(requestId)
        pendingRequestsData.remove(requestId)
        if (_incomingRequest.value?.id == requestId) {
            _incomingRequest.value = null
        }
        NotificationHelper.dismissPeerTransferRequestNotification(context)
    }

    fun declineRequest(context: Context, requestId: String) {
        val deferred = pendingRequests[requestId]
        deferred?.complete(false)
        pendingRequests.remove(requestId)
        pendingRequestsData.remove(requestId)
        if (_incomingRequest.value?.id == requestId) {
            _incomingRequest.value = null
        }
        NotificationHelper.dismissPeerTransferRequestNotification(context)
    }

    fun registerSessionToken(token: String, durationMs: Long = 600000L) {
        activeSessionTokens[token] = System.currentTimeMillis() + durationMs
    }

    fun isValidSessionToken(token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        val exp = activeSessionTokens[token] ?: return false
        if (System.currentTimeMillis() > exp) {
            activeSessionTokens.remove(token)
            return false
        }
        return true
    }
}
