package com.example.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Http
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.TransferLog
import com.example.data.model.TransferProtocol
import com.example.data.model.TransferStatus
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyanGlow
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.RoseError
import com.example.ui.theme.Slate400
import com.example.ui.theme.Slate800
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TransferLogItem(
    log: TransferLog,
    modifier: Modifier = Modifier
) {
    val timeFormatted = SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault())
        .format(Date(log.timestampMillis))

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
        border = androidx.compose.foundation.BorderStroke(1.dp, Slate800)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Protocol Icon Box
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (log.protocol == TransferProtocol.HTTP) CyanPrimary.copy(alpha = 0.15f)
                        else Color(0xFF6366F1).copy(alpha = 0.15f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (log.protocol == TransferProtocol.HTTP) Icons.Default.Http else Icons.Default.Send,
                    contentDescription = log.protocol.name,
                    tint = if (log.protocol == TransferProtocol.HTTP) CyanGlow else Color(0xFFA5B4FC),
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // File and details
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = log.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = "${log.targetIp}:${log.targetPort} • $timeFormatted",
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate400,
                    fontSize = 11.sp
                )

                if (log.errorMessage != null && log.status == TransferStatus.FAILED) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = log.errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = RoseError,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Status Badge
            StatusBadge(status = log.status, durationMs = log.durationMs)
        }
    }
}

@Composable
private fun StatusBadge(status: TransferStatus, durationMs: Long) {
    val (bg, fg, icon, label) = when (status) {
        TransferStatus.SUCCESS -> Quad(
            EmeraldSuccess.copy(alpha = 0.15f),
            EmeraldSuccess,
            Icons.Default.CheckCircle,
            if (durationMs > 0) "${durationMs}ms" else "Sent"
        )
        TransferStatus.FAILED -> Quad(
            RoseError.copy(alpha = 0.15f),
            RoseError,
            Icons.Default.Error,
            "Failed"
        )
        TransferStatus.IN_PROGRESS -> Quad(
            AmberWarning.copy(alpha = 0.15f),
            AmberWarning,
            Icons.Default.HourglassTop,
            "Sending"
        )
        TransferStatus.PENDING -> Quad(
            Slate400.copy(alpha = 0.15f),
            Slate400,
            Icons.Default.HourglassTop,
            "Queued"
        )
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(12.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = fg
        )
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
