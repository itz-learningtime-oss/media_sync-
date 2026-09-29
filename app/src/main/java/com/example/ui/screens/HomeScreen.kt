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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.DetectedMedia
import com.example.data.model.ServerConfig
import com.example.data.model.TransferProtocol
import com.example.ui.components.MediaItemCard
import com.example.ui.components.ServiceStatusHero
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyanGlow
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.Slate400
import com.example.ui.theme.Slate800

@Composable
fun HomeScreen(
    isServiceRunning: Boolean,
    eventCount: Int,
    serviceStartTime: Long,
    serverConfig: ServerConfig,
    detectedMediaList: List<DetectedMedia>,
    hasStoragePermission: Boolean,
    hasNotificationPermission: Boolean,
    onRequestPermissions: () -> Unit,
    onToggleService: (Boolean) -> Unit,
    onTriggerScan: () -> Unit,
    onSimulateScreenshot: () -> Unit,
    onSendHttp: (DetectedMedia) -> Unit,
    onSendFtp: (DetectedMedia) -> Unit,
    onSelectMedia: (DetectedMedia) -> Unit,
    onClearDetected: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("home_screen_lazy_column"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Permission Warning Banner (if any required permission is missing)
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
                onToggleService = onToggleService,
                onTriggerScan = onTriggerScan,
                onSimulateScreenshot = onSimulateScreenshot
            )
        }

        // 3. Quick PC Connection Summary Pill
        item(key = "pc_connection_pill") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(DarkSurfaceVariant)
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
                        color = Slate400,
                        fontWeight = FontWeight.Medium
                    )
                }

                Text(
                    text = "FTP :${serverConfig.ftpPort}",
                    style = MaterialTheme.typography.bodySmall,
                    color = CyanGlow,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // 4. Detected Media Section Header
        item(key = "detected_section_header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Live Media Stream",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(CyanPrimary.copy(alpha = 0.2f))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "${detectedMediaList.size}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = CyanGlow
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
                            tint = Slate400,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        // 5. Media List or Empty State
        if (detectedMediaList.isEmpty()) {
            item(key = "empty_media_state") {
                EmptyMediaState(
                    onTriggerScan = onTriggerScan,
                    onSimulateScreenshot = onSimulateScreenshot
                )
            }
        } else {
            items(
                items = detectedMediaList,
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
        colors = CardDefaults.cardColors(containerColor = AmberWarning.copy(alpha = 0.15f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, AmberWarning.copy(alpha = 0.4f))
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
                    text = if (!hasStorage && !hasNotification) "Media access & Notifications needed"
                    else if (!hasStorage) "Photos/Media access needed"
                    else "Notification permission needed for popups",
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate400
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = onRequestPermissions,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AmberWarning,
                    contentColor = Color(0xFF0B1120)
                ),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("Grant", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun EmptyMediaState(
    onTriggerScan: () -> Unit,
    onSimulateScreenshot: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
        border = androidx.compose.foundation.BorderStroke(1.dp, Slate800)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Slate800),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PhotoLibrary,
                    contentDescription = null,
                    tint = CyanGlow,
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "No Media Events Detected Yet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Take a screenshot or capture a photo on your device. The ContentObserver will immediately trigger a heads-up notification!",
                style = MaterialTheme.typography.bodySmall,
                color = Slate400,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            Spacer(modifier = Modifier.height(18.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onSimulateScreenshot,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyanPrimary,
                        contentColor = Color(0xFF0B1120)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Simulate Screenshot", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
