package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ConnectedPeer
import com.example.data.model.DetectedMedia
import com.example.data.model.ServerConfig
import com.example.data.model.TransferProtocol
import com.example.service.ReceivedFileItem
import com.example.ui.components.ConnectedPeersHubCard
import com.example.ui.components.MediaItemCard
import com.example.ui.components.ReceivedFilesCard
import com.example.ui.components.ServiceStatusHero
import com.example.ui.components.SharingActionHub
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyanGlow
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.EmeraldSuccess

@Composable
fun HomeScreen(
    isServiceRunning: Boolean,
    eventCount: Int,
    serviceStartTime: Long,
    serverIp: String,
    serverConfig: ServerConfig,
    detectedMediaList: List<DetectedMedia>,
    receivedFiles: List<ReceivedFileItem> = emptyList(),
    peers: List<ConnectedPeer> = emptyList(),
    isDiscoveringPeers: Boolean = false,
    discoveryProgress: Float = 0f,
    hasStoragePermission: Boolean,
    hasNotificationPermission: Boolean,
    onRequestPermissions: () -> Unit,
    onToggleService: (Boolean) -> Unit,
    onTriggerScan: () -> Unit,
    onSimulateScreenshot: () -> Unit,
    onOpenQrPairing: () -> Unit,
    onPickDocuments: () -> Unit,
    onShareApk: () -> Unit,
    onDiscoverPeers: () -> Unit = {},
    onTogglePeerSelection: (String) -> Unit = {},
    onSelectAllPeers: (Boolean) -> Unit = {},
    onAddManualPeer: (String, Int) -> Unit = { _, _ -> },
    onRemovePeer: (String) -> Unit = {},
    onBroadcastToPeers: () -> Unit = {},
    onSaveReceivedToGallery: (ReceivedFileItem) -> Unit,
    onDismissReceived: (ReceivedFileItem) -> Unit,
    onSendHttp: (DetectedMedia) -> Unit,
    onSendFtp: (DetectedMedia) -> Unit,
    onSelectMedia: (DetectedMedia) -> Unit,
    onClearDetected: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedFilter by remember { mutableStateOf("All") }

    val filteredList = remember(detectedMediaList, selectedFilter) {
        when (selectedFilter) {
            "Screenshots" -> detectedMediaList.filter { it.isScreenshot }
            "Documents" -> detectedMediaList.filter {
                !it.isScreenshot && (it.bucketName == "Documents" || it.mimeType?.contains("pdf") == true || it.displayName.endsWith(".doc", true) || it.displayName.endsWith(".docx", true) || it.displayName.endsWith(".zip", true))
            }
            "APKs" -> detectedMediaList.filter {
                it.displayName.endsWith(".apk", ignoreCase = true) || it.mimeType == "application/vnd.android.package-archive" || it.bucketName == "Apps"
            }
            else -> detectedMediaList
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("home_screen_lazy_column"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Permission Warning Banner
        if (!hasStoragePermission || !hasNotificationPermission) {
            item(key = "permissions_banner") {
                PermissionAlertBanner(
                    hasStorage = hasStoragePermission,
                    hasNotification = hasNotificationPermission,
                    onRequestPermissions = onRequestPermissions
                )
            }
        }

        // 2. Service Status Hero Card
        item(key = "service_hero") {
            ServiceStatusHero(
                isRunning = isServiceRunning,
                eventCount = eventCount,
                serviceStartTime = serviceStartTime,
                serverIp = serverIp,
                onToggleService = onToggleService,
                onTriggerScan = onTriggerScan,
                onSimulateScreenshot = onSimulateScreenshot,
                onOpenQrPairing = onOpenQrPairing
            )
        }

        // 3. Sharing Action Hub (Pick Documents, Share APK, iOS Portal)
        item(key = "sharing_action_hub") {
            SharingActionHub(
                serverIp = serverIp,
                onPickDocuments = onPickDocuments,
                onShareApk = onShareApk,
                onOpenIosPortal = onOpenQrPairing
            )
        }

        // 4. Connected Mobile Peers & Group Hub Card (Multi-User Sharing)
        item(key = "connected_peers_hub") {
            ConnectedPeersHubCard(
                peers = peers,
                isDiscovering = isDiscoveringPeers,
                discoveryProgress = discoveryProgress,
                onDiscoverPeers = onDiscoverPeers,
                onTogglePeerSelection = onTogglePeerSelection,
                onSelectAll = onSelectAllPeers,
                onAddManualPeer = onAddManualPeer,
                onRemovePeer = onRemovePeer,
                onBroadcastToSelected = onBroadcastToPeers
            )
        }

        // 5. Received Files Card (Files received from PC / iOS / Other Android phones)
        if (receivedFiles.isNotEmpty()) {
            item(key = "received_files_section") {
                ReceivedFilesCard(
                    receivedFiles = receivedFiles,
                    onSaveToGallery = onSaveReceivedToGallery,
                    onDismiss = onDismissReceived
                )
            }
        }

        // 5. Quick PC Connection Summary Pill
        item(key = "pc_connection_pill") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(EmeraldSuccess)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Target PC: ${serverConfig.pcHostIp}:${serverConfig.httpPort}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                    )
                }

                Text(
                    text = "FTP :${serverConfig.ftpPort}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // 6. Detected Media & Documents Section Header with Filters
        item(key = "detected_section_header") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Live Files & Media Stream",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "${detectedMediaList.size}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    if (detectedMediaList.isNotEmpty()) {
                        IconButton(
                            onClick = onClearDetected,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = "Clear List",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                // Category Chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("All", "Documents", "Screenshots", "APKs").forEach { filter ->
                        FilterChip(
                            selected = selectedFilter == filter,
                            onClick = { selectedFilter = filter },
                            label = { Text(filter, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            modifier = Modifier.height(28.dp)
                        )
                    }
                }
            }
        }

        // 7. Media List or Empty State
        if (filteredList.isEmpty()) {
            item(key = "empty_media_state") {
                EmptyMediaState(
                    onTriggerScan = onTriggerScan,
                    onPickDocuments = onPickDocuments
                )
            }
        } else {
            items(
                items = filteredList,
                key = { it.mediaStoreId }
            ) { media ->
                MediaItemCard(
                    media = media,
                    onSendHttp = { onSendHttp(media) },
                    onSendFtp = { onSendFtp(media) },
                    onClick = { onSelectMedia(media) }
                )
            }
        }
    }
}

@Composable
private fun PermissionAlertBanner(
    hasStorage: Boolean,
    hasNotification: Boolean,
    onRequestPermissions: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("permission_alert_banner"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AmberWarning.copy(alpha = 0.12f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, AmberWarning.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = AmberWarning,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Permissions Required",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = AmberWarning
                )
                Text(
                    text = when {
                        !hasStorage && !hasNotification -> "Storage and Notification access are needed for file sync."
                        !hasStorage -> "Storage access required to read media and documents."
                        else -> "Notification access required for instant transfer popups."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = onRequestPermissions,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AmberWarning,
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.testTag("grant_permissions_button")
            ) {
                Text("Grant", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun EmptyMediaState(
    onTriggerScan: () -> Unit,
    onPickDocuments: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("empty_media_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PhotoLibrary,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "No files in transfer queue",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Take a screenshot, snap a photo, or choose any document to share instantly via Wi-Fi or Web Browser.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(18.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onPickDocuments,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Select Documents", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = onTriggerScan,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Scan Photos", fontSize = 12.sp)
                }
            }
        }
    }
}
