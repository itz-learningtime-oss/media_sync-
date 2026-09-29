package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyanGlow
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.Slate400
import com.example.ui.theme.Slate600
import com.example.ui.theme.Slate800
import kotlinx.coroutines.delay

@Composable
fun ServiceStatusHero(
    isRunning: Boolean,
    eventCount: Int,
    serviceStartTime: Long,
    onToggleService: (Boolean) -> Unit,
    onTriggerScan: () -> Unit,
    onSimulateScreenshot: () -> Unit,
    modifier: Modifier = Modifier
) {
    var uptimeString by remember { mutableStateOf("00:00:00") }

    LaunchedEffect(isRunning, serviceStartTime) {
        if (!isRunning || serviceStartTime == 0L) {
            uptimeString = "00:00:00"
            return@LaunchedEffect
        }
        while (isRunning) {
            val diffSec = ((System.currentTimeMillis() - serviceStartTime) / 1000).coerceAtLeast(0)
            val hours = diffSec / 3600
            val minutes = (diffSec % 3600) / 60
            val seconds = diffSec % 60
            uptimeString = "%02d:%02d:%02d".format(hours, minutes, seconds)
            delay(1000)
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val glowColor by animateColorAsState(
        targetValue = if (isRunning) EmeraldSuccess else Slate600,
        label = "glowColor"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("service_status_hero_card"),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = DarkSurfaceVariant
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isRunning) CyanPrimary.copy(alpha = 0.4f) else Slate800
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            if (isRunning) CyanPrimary.copy(alpha = 0.15f) else Color.Transparent,
                            Color.Transparent
                        ),
                        radius = 800f
                    )
                )
                .padding(20.dp)
        ) {
            Column {
                // Header row with status pill and switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Pulsing Beacon
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(28.dp)
                        ) {
                            if (isRunning) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .scale(pulseScale)
                                        .clip(CircleShape)
                                        .background(glowColor.copy(alpha = 0.35f))
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(glowColor)
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Column {
                            Text(
                                text = if (isRunning) "LISTENER ACTIVE" else "LISTENER PAUSED",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isRunning) CyanGlow else Slate400,
                                letterSpacing = 1.2.sp
                            )
                            Text(
                                text = if (isRunning) "Monitoring MediaStore" else "Foreground Service Off",
                                style = MaterialTheme.typography.bodySmall,
                                color = Slate400
                            )
                        }
                    }

                    // Main switch
                    Switch(
                        checked = isRunning,
                        onCheckedChange = onToggleService,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = CyanPrimary,
                            uncheckedThumbColor = Slate400,
                            uncheckedTrackColor = Slate800
                        ),
                        modifier = Modifier.testTag("service_toggle_switch")
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Stats row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF0F172A).copy(alpha = 0.7f))
                        .padding(vertical = 12.dp, horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    StatPill(
                        label = "Uptime",
                        value = uptimeString,
                        icon = Icons.Default.FlashOn,
                        tint = CyanGlow
                    )
                    StatPill(
                        label = "Captured",
                        value = "$eventCount items",
                        icon = Icons.Default.CameraAlt,
                        tint = EmeraldSuccess
                    )
                    StatPill(
                        label = "Mode",
                        value = "Foreground",
                        icon = Icons.Default.Radar,
                        tint = AmberWarning
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Action buttons row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onTriggerScan,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyanPrimary,
                            contentColor = Color(0xFF0B1120)
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("scan_media_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudSync,
                            contentDescription = "Scan Media",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Scan Media", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }

                    FilledTonalButton(
                        onClick = onSimulateScreenshot,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Slate800,
                            contentColor = CyanGlow
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("simulate_screenshot_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = "Test Notification",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Test Popup", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatPill(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = Slate400
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}
