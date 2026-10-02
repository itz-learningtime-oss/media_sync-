package com.example.util

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.provider.Settings
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.ByteOrder

object NetworkUtils {

    private const val TAG = "NetworkUtils"
    const val STANDARD_HOTSPOT_IP = "192.168.43.1"
    const val DEFAULT_PORT = 8080

    /**
     * Finds the best active IPv4 address for local network file sharing.
     * Prioritizes active Wi-Fi, Hotspot, and USB interfaces.
     */
    fun getLocalIpAddress(context: Context? = null): String {
        try {
            // 1. Enumerate all active non-loopback Network Interfaces
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
            val candidates = mutableListOf<Pair<String, Int>>() // Pair<IP, Priority Score>

            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue

                val name = networkInterface.name.lowercase()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("127.") || ip == "0.0.0.0") continue

                        // Score priorities based on interface name and IP range
                        val score = when {
                            name.startsWith("ap") || name.startsWith("softap") || name.startsWith("hotspot") -> 110 // Mobile Hotspot AP
                            ip.startsWith("192.168.43.") -> 105 // Android standard hotspot gateway
                            name.startsWith("wlan") || name.startsWith("wifi") -> 100 // Standard Wi-Fi station
                            name.startsWith("rndis") || name.startsWith("usb") -> 90 // USB Tethering
                            name.startsWith("p2p") -> 85 // Wi-Fi Direct
                            name.startsWith("eth") -> 70 // Ethernet / Emulator
                            ip.startsWith("192.168.49.") -> 102 // Android Wi-Fi Direct/Hotspot alternate
                            ip.startsWith("192.168.50.") -> 101 // Alternate AP subnet
                            ip.startsWith("192.168.") -> 60
                            ip.startsWith("10.") -> 50
                            ip.startsWith("172.") -> 40
                            else -> 10
                        }
                        candidates.add(ip to score)
                    }
                }
            }

            // Return highest scored candidate
            val best = candidates.maxByOrNull { it.second }?.first
            if (best != null) {
                Log.i(TAG, "Resolved Best Local IP: $best (from ${candidates.size} interfaces)")
                return best
            }

            // Fallback to WifiManager if network interfaces were inaccessible
            if (context != null) {
                try {
                    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    val wifiIpInt = wifiManager?.connectionInfo?.ipAddress ?: 0
                    if (wifiIpInt != 0) {
                        val formattedIp = formatIpInt(wifiIpInt)
                        if (formattedIp != "0.0.0.0" && formattedIp != "127.0.0.1") {
                            return formattedIp
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "WifiManager IP fallback check skipped", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving local IP", e)
        }
        return "127.0.0.1"
    }

    /**
     * Resolves the Hotspot / SoftAP IPv4 address of this Android phone.
     * If Hotspot is active, Android devices assign themselves 192.168.43.1 (or detected AP IP).
     */
    fun getHotspotIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return STANDARD_HOTSPOT_IP
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                val name = networkInterface.name.lowercase()

                val isApInterface = name.startsWith("ap") || name.startsWith("softap") ||
                        name.startsWith("hotspot") || name.startsWith("swlan") || name.startsWith("wlan1")

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        if (isApInterface || ip.startsWith("192.168.43.") || ip.startsWith("192.168.49.")) {
                            Log.i(TAG, "Found active Hotspot AP IP: $ip on interface $name")
                            return ip
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding hotspot IP", e)
        }
        return STANDARD_HOTSPOT_IP
    }

    /**
     * Returns a list of all active IPv4 addresses on the device (Wi-Fi, Hotspot, etc.)
     */
    fun getAllActiveIpv4Addresses(): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>() // Pair<InterfaceName, IP>
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                val name = networkInterface.name
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        if (!ip.startsWith("127.") && ip != "0.0.0.0") {
                            val label = when {
                                name.startsWith("ap") || name.startsWith("softap") -> "Mobile Hotspot ($name)"
                                ip.startsWith("192.168.43.") -> "Hotspot Gateway ($name)"
                                name.startsWith("wlan") -> "Wi-Fi ($name)"
                                name.startsWith("rndis") || name.startsWith("usb") -> "USB ($name)"
                                name.startsWith("p2p") -> "Wi-Fi Direct ($name)"
                                name.startsWith("eth") -> "Ethernet ($name)"
                                else -> name
                            }
                            result.add(label to ip)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error listing IPv4 addresses", e)
        }
        return result
    }

    /**
     * Intent to open the device's Tethering / Portable Hotspot settings.
     */
    fun getHotspotSettingsIntent(): Intent {
        return Intent("android.settings.TETHER_SETTINGS").apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    fun openHotspotSettings(context: Context): Boolean {
        return try {
            val intent = getHotspotSettingsIntent()
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            try {
                val fallbackIntent = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(fallbackIntent)
                true
            } catch (e2: Exception) {
                Log.e(TAG, "Failed opening hotspot settings", e2)
                false
            }
        }
    }

    private fun formatIpInt(ipInt: Int): String {
        val ip = if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
            Integer.reverseBytes(ipInt)
        } else {
            ipInt
        }
        val b1 = (ip shr 24) and 0xFF
        val b2 = (ip shr 16) and 0xFF
        val b3 = (ip shr 8) and 0xFF
        val b4 = ip and 0xFF
        return "$b1.$b2.$b3.$b4"
    }
}
