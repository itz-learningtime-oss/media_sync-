package com.example.data.model

data class ServerConfig(
    val pcHostIp: String = "192.168.1.100",
    val httpPort: Int = 8000,
    val httpEndpoint: String = "/upload",
    val ftpPort: Int = 2121,
    val ftpUsername: String = "user",
    val ftpPassword: String = "password",
    val autoSyncMode: AutoSyncMode = AutoSyncMode.NOTIFICATION_CHOICE
)

enum class AutoSyncMode {
    NOTIFICATION_CHOICE, // Default: shows prompt with HTTP & FTP buttons
    AUTO_HTTP,           // Automatically upload via HTTP POST
    AUTO_FTP             // Automatically upload via FTP
}
