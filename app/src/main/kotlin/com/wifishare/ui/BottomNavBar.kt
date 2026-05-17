package com.wifishare.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class Tab(val label: String, val icon: ImageVector) {
    Status("Status", Icons.Default.Monitor),
    Files("Files", Icons.Default.Folder),
    Devices("Devices", Icons.Default.Smartphone),
    Settings("Settings", Icons.Default.Settings),
}

/**
 * App-wide bottom navigation. Selected tab gets a purple-faint pill
 * behind both icon AND label — that's the visual cue from the mockup
 * (Material's default selectedIndicator only paints behind the icon).
 */
@Composable
fun WiFiShareBottomBar(
    selected: Tab,
    onSelect: (Tab) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Tab.entries.forEach { tab ->
                TabItem(
                    tab = tab,
                    selected = selected == tab,
                    onClick = { onSelect(tab) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun TabItem(
    tab: Tab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        color = if (selected) WiFiShareColors.PrimaryFaint else MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier.padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Icon(
                tab.icon,
                null,
                Modifier.size(22.dp),
                tint = if (selected) WiFiShareColors.PrimaryDeep
                else WiFiShareColors.OnSurfaceMuted,
            )
            Text(
                tab.label,
                fontSize = 11.sp,
                color = if (selected) WiFiShareColors.PrimaryDeep
                else WiFiShareColors.OnSurfaceMuted,
            )
        }
    }
}
