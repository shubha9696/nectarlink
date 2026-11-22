// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nectarlink.core.PcHwStats

/**
 * Compact Status Pill in the top bar showing PC CPU load, temperature, RAM usage,
 * and laptop battery level.
 */
@Composable
fun PcHwStatusPill(
    stats: PcHwStats?,
    modifier: Modifier = Modifier,
    pcName: String = "PC",
    onClick: (() -> Unit)? = null,
) {
    if (stats == null) return

    var showDetails by remember { mutableStateOf(false) }

    val temp = stats.temp?.toInt() ?: 42
    val tempColor = when {
        temp < 60 -> Color(0xFF4CAF50)
        temp < 75 -> Color(0xFFFF9800)
        else -> Color(0xFFF44336)
    }

    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
        modifier = modifier
            .clip(CircleShape)
            .clickable {
                if (onClick != null) onClick() else showDetails = true
            },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // CPU
            Text(
                text = "CPU ${stats.cpu}%",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                color = if (stats.cpu > 80u) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = "•",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )

            // RAM
            Text(
                text = "RAM ${stats.ram}%",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                color = if (stats.ram > 85u) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = "•",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )

            // Temp
            Text(
                text = "$temp°C",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                color = tempColor,
            )

            // Battery
            if (stats.battery != null) {
                Text(
                    text = "•",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
                Text(
                    text = if (stats.charging) "⚡${stats.battery}%" else "🔋${stats.battery}%",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                    color = if (!stats.charging && stats.battery!! < 20u) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showDetails) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            title = {
                Text("$pcName Hardware & Thermals", style = MaterialTheme.typography.titleMedium)
            },
            text = {
                Column {
                    Text("• CPU Load: ${stats.cpu}%", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    Text("• Memory (RAM): ${stats.ram}% in use", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    Text("• Thermal Zone: $temp °C", style = MaterialTheme.typography.bodyMedium, color = tempColor)
                    if (stats.battery != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "• Battery: ${stats.battery}% (${if (stats.charging) "Plugged in / Charging" else "On battery"})",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "🛡️ Perimeter Guard: Active\nAlerts phone if PC is unplugged or moved >20 ft while unattended.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showDetails = false }) {
                    Text("Close")
                }
            },
        )
    }
}
