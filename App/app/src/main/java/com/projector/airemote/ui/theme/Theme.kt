package com.projector.airemote.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme(
    primary = ProjectorRed,
    secondary = AccentBlue,
    background = SurfaceDark,
    surface = RemoteBlack,
    surfaceVariant = RemoteBlackLight
)

private val LightColors = lightColorScheme(
    primary = ProjectorRed,
    secondary = AccentBlue
)

@Composable
fun AiProjectorRemoteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        typography = Typography,
        content = content
    )
}
