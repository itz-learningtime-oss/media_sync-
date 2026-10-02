package com.example.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.example.util.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object EmbeddedServerManager {

    private const val TAG = "EmbeddedServerManager"
    const val DEFAULT_PORT = 8080

    private val managerJob = SupervisorJob()
    private val managerScope = CoroutineScope(Dispatchers.IO + managerJob)

    private var serverInstance: EmbeddedReceiverServer? = null
    private var autoRefreshJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val _isServerRunning = MutableStateFlow(false)
    val isServerRunning: StateFlow<Boolean> = _isServerRunning.asStateFlow()

    private val _serverIp = MutableStateFlow("127.0.0.1")
    val serverIp: StateFlow<String> = _serverIp.asStateFlow()

    fun startServer(context: Context, port: Int = DEFAULT_PORT) {
        if (serverInstance != null && _isServerRunning.value) {
            refreshIp(context)
            return
        }

        try {
            refreshIp(context)
            serverInstance = EmbeddedReceiverServer(
                context = context.applicationContext,
                port = port,
                scope = managerScope
            ).apply {
                start()
            }
            _isServerRunning.value = true
            Log.i(TAG, "Embedded HTTP Server successfully started on port $port (IP: ${_serverIp.value})")

            startAutoRefresh(context.applicationContext)
            registerNetworkCallback(context.applicationContext)
            UdpDiscoveryHelper.startListener(context.applicationContext, port)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Embedded HTTP Server on port $port", e)
        }
    }

    fun stopServer() {
        try {
            UdpDiscoveryHelper.stopListener()
            autoRefreshJob?.cancel()
            autoRefreshJob = null
            networkCallback = null
            serverInstance?.stop()
            serverInstance = null
            _isServerRunning.value = false
            Log.i(TAG, "Embedded HTTP Server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Embedded HTTP Server", e)
        }
    }

    fun refreshIp(context: Context) {
        val detectedIp = NetworkUtils.getLocalIpAddress(context)
        if (_serverIp.value != detectedIp) {
            _serverIp.value = detectedIp
            Log.i(TAG, "Updated Server IP to: $detectedIp")
        }
    }

    private fun startAutoRefresh(appContext: Context) {
        autoRefreshJob?.cancel()
        autoRefreshJob = managerScope.launch {
            while (isActive) {
                delay(3000)
                try {
                    refreshIp(appContext)
                } catch (_: Exception) {}
            }
        }
    }

    private fun registerNetworkCallback(appContext: Context) {
        try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .build()

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    refreshIp(appContext)
                }

                override fun onLost(network: Network) {
                    refreshIp(appContext)
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    refreshIp(appContext)
                }
            }

            cm.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            Log.w(TAG, "Failed registering network callback (auto-poll will handle it)", e)
        }
    }

    fun getPortalUrl(context: Context? = null): String {
        val ip = if (context != null) NetworkUtils.getLocalIpAddress(context) else _serverIp.value
        return "http://$ip:$DEFAULT_PORT/"
    }
}
