// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
 * Integrated Bento Hardware & Thermal Status Strip showing PC CPU load, temperature,
 * RAM usage, and laptop battery / AC level.
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

    val hasTemp = stats.temp != null && stats.temp!! > 25u && stats.temp!! != 20u
    val temp = stats.temp?.toInt() ?: 0
    val tempColor = when {
        temp < 60 -> Color(0xFF4CAF50)
        temp < 75 -> Color(0xFFFF9800)
        else -> Color(0xFFF44336)
    }

    val ink = MaterialTheme.colorScheme.onPrimaryContainer
    val labelColor = ink.copy(alpha = 0.65f)
    val valueColor = ink
    val sepColor = ink.copy(alpha = 0.22f)

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = ink.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, ink.copy(alpha = 0.14f)),
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable {
                if (onClick != null) onClick() else showDetails = true
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            // CPU
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "CPU",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                    color = labelColor,
                )
                Text(
                    text = "${stats.cpu}%",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
                    color = if (stats.cpu > 80u) MaterialTheme.colorScheme.error else valueColor,
                )
            }

            Text(
                text = "•",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                color = sepColor,
            )

            // RAM
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "RAM",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                    color = labelColor,
                )
                Text(
                    text = "${stats.ram}%",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
                    color = if (stats.ram > 85u) MaterialTheme.colorScheme.error else valueColor,
                )
            }

            if (hasTemp) {
                Text(
                    text = "•",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                    color = sepColor,
                )

                // Temp
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = "$temp°C",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
                        color = tempColor,
                    )
                }
            }

            Text(
                text = "•",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                color = sepColor,
            )

            // Battery / Power
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                if (stats.charging) {
                    Text(
                        text = "⚡",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                        color = Color(0xFFFFC107),
                    )
                } else {
                    Text(
                        text = "🔋",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    )
                }
                Text(
                    text = if (stats.battery != null) {
                        "${stats.battery}%"
                    } else {
                        if (stats.charging) "AC" else "Plugged"
                    },
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
                    color = if (stats.battery != null && !stats.charging && stats.battery!! < 20u) MaterialTheme.colorScheme.error else valueColor,
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
                    if (hasTemp) {
                        Text("• Thermal Zone: $temp °C", style = MaterialTheme.typography.bodyMedium, color = tempColor)
                    } else {
                        Text("• Thermal Zone: Not exposed by BIOS/hardware", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
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
