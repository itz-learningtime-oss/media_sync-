package com.example.data.model

import java.util.UUID

enum class PeerTransferStatus {
    IDLE,
    PREPARING,
    SENDING,
    COMPLETED,
    FAILED
}

data class ConnectedPeer(
    val id: String = UUID.randomUUID().toString(),
    val deviceName: String,
    val ipAddress: String,
    val httpPort: Int = 8080,
    val authToken: String? = null,
    val isOnline: Boolean = true,
    val isSelected: Boolean = true,
    val isPaired: Boolean = false,
    val lastSeenMillis: Long = System.currentTimeMillis(),
    val transferStatus: PeerTransferStatus = PeerTransferStatus.IDLE,
    val transferProgress: Float = 0f,
    val transferStatusMessage: String? = null,
    val isHotspotGateway: Boolean = false
) {
    val baseUrl: String
        get() = "http://$ipAddress:$httpPort"

    val displayAddress: String
        get() = "$ipAddress:$httpPort"
}
