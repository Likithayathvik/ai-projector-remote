package com.projector.airemote.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable

private data class QuickLaunchApp(val label: String, val toolName: String, val icon: ImageVector, val color: Color)

private val quickLaunchApps = listOf(
    QuickLaunchApp("Netflix", "launch_netflix", Icons.Filled.Movie, Color(0xFFB71C1C)),
    QuickLaunchApp("YouTube", "launch_youtube", Icons.Filled.SmartDisplay, Color(0xFFCC0000)),
    QuickLaunchApp("Prime Video", "launch_prime_video", Icons.Filled.PlayCircle, Color(0xFF1565C0)),
    QuickLaunchApp("Hotstar", "launch_hotstar", Icons.Filled.LiveTv, Color(0xFF0D2B4E))
)

@Composable
fun QuickLaunchRow(onLaunch: (toolName: String, label: String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text("Quick Launch", style = MaterialTheme.typography.titleLarge)
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) {
            quickLaunchApps.forEach { app ->
                Surface(
                    color = app.color,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(84.dp)
                        .clickable { onLaunch(app.toolName, app.label) }
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(app.icon, contentDescription = app.label, tint = Color.White)
                        Text(app.label, color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
