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
 * Integrated 4-Tile Bento Hardware & Thermal Metric Dashboard showing PC CPU load,
 * temperature, RAM usage, and laptop battery / AC power status.
 */
@Composable
fun PcHwStatusPill(
    stats: PcHwStats?,
    modifier: Modifier = Modifier,
    pcName: String = "PC",
    compact: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    if (stats == null) return

    var showDetails by remember { mutableStateOf(false) }

    val temp = stats.temp?.toInt() ?: 0
    val hasTemp = temp > 0
    val tempColor = when {
        temp < 60 -> Color(0xFF4CAF50)
        temp < 75 -> Color(0xFFFF9800)
        else -> Color(0xFFF44336)
    }

    val ink = MaterialTheme.colorScheme.onPrimaryContainer
    val valueColor = ink

    if (compact) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = ink.copy(alpha = 0.08f),
            border = BorderStroke(1.dp, ink.copy(alpha = 0.14f)),
            modifier = modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable { if (onClick != null) onClick() else showDetails = true },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "CPU ${stats.cpu}%",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                    color = if (stats.cpu.toInt() > 80) MaterialTheme.colorScheme.error else valueColor,
                )
                Text("•", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = ink.copy(alpha = 0.35f))
                Text(
                    text = "RAM ${stats.ram}%",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                    color = if (stats.ram.toInt() > 85) MaterialTheme.colorScheme.error else valueColor,
                )
                if (hasTemp) {
                    Text("•", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = ink.copy(alpha = 0.35f))
                    Text(
                        text = "$temp°C",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                        color = tempColor,
                    )
                }
                Text("•", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = ink.copy(alpha = 0.35f))
                val batText = if (stats.battery != null) {
                    "${if (stats.charging) "⚡" else ""}${stats.battery}%"
                } else {
                    if (stats.charging) "⚡AC" else "AC"
                }
                Text(
                    text = batText,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                    color = if (stats.battery != null && !stats.charging && stats.battery!!.toInt() < 20) MaterialTheme.colorScheme.error else valueColor,
                )
            }
        }
    } else {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 1. CPU Bento Tile
            BentoStatTile(
                label = "CPU",
                value = "${stats.cpu}%",
                valueColor = if (stats.cpu.toInt() > 80) MaterialTheme.colorScheme.error else valueColor,
                modifier = Modifier.weight(1f),
                onClick = { if (onClick != null) onClick() else showDetails = true },
            )

            // 2. RAM Bento Tile
            BentoStatTile(
                label = "RAM",
                value = "${stats.ram}%",
                valueColor = if (stats.ram.toInt() > 85) MaterialTheme.colorScheme.error else valueColor,
                modifier = Modifier.weight(1f),
                onClick = { if (onClick != null) onClick() else showDetails = true },
            )

            // 3. TEMP Bento Tile
            BentoStatTile(
                label = "TEMP",
                value = if (hasTemp) "$temp°C" else "--",
                valueColor = if (hasTemp) tempColor else valueColor.copy(alpha = 0.5f),
                modifier = Modifier.weight(1f),
                onClick = { if (onClick != null) onClick() else showDetails = true },
            )

            // 4. BATTERY / AC Bento Tile
            val batText = if (stats.battery != null) {
                "${if (stats.charging) "⚡" else ""}${stats.battery}%"
            } else {
                if (stats.charging) "⚡AC" else "AC"
            }
            BentoStatTile(
                label = if (stats.charging) "POWER" else "BAT",
                value = batText,
                valueColor = if (stats.battery != null && !stats.charging && stats.battery!!.toInt() < 20) MaterialTheme.colorScheme.error else valueColor,
                modifier = Modifier.weight(1f),
                onClick = { if (onClick != null) onClick() else showDetails = true },
            )
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
                        Text("• Thermal Package: $temp °C", style = MaterialTheme.typography.bodyMedium, color = tempColor)
                    } else {
                        Text("• Thermal Package: Optimal", style = MaterialTheme.typography.bodyMedium)
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

@Composable
private fun BentoStatTile(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val ink = MaterialTheme.colorScheme.onPrimaryContainer
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = ink.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, ink.copy(alpha = 0.12f)),
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.4.sp,
                ),
                color = ink.copy(alpha = 0.58f),
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = valueColor,
                maxLines = 1,
            )
        }
    }
}
