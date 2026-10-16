package com.boat.cinehead.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Equalizer
import androidx.compose.material.icons.outlined.SettingsRemote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The app's two sections, switched from the bottom bar. */
enum class MainTab(val label: String, val icon: ImageVector) {
    Remote("Remote", Icons.Outlined.SettingsRemote),
    Equalizer("Equalizer", Icons.Outlined.Equalizer),
}

private val TabRed = Color(0xFFE53935)
private val TabMuted = Color(0xFF9AA0A6)

@Composable
fun MainTabBar(selected: MainTab, onSelect: (MainTab) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF15181C))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceAround
    ) {
        MainTab.entries.forEach { tab ->
            val on = tab == selected
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (on) TabRed.copy(alpha = 0.16f) else Color.Transparent)
                    .clickable { onSelect(tab) }
                    .padding(horizontal = 18.dp, vertical = 6.dp)
            ) {
                Icon(tab.icon, contentDescription = tab.label, tint = if (on) TabRed else TabMuted, modifier = Modifier.size(24.dp))
                Text(tab.label, color = if (on) TabRed else TabMuted, fontSize = 12.sp)
            }
        }
    }
}
