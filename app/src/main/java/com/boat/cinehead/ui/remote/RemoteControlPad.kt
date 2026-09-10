package com.boat.cinehead.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SettingsInputHdmi
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.boat.cinehead.net.VolumeInfo
import com.boat.cinehead.ui.theme.ProjectorRed
import com.boat.cinehead.ui.theme.RemoteBlack

private val ButtonSurface = Color(0xFF2E343C)
private val DPadTrack = Color(0xFF23282F)

/** Visual layout mirrors the physical IR remote photographed for this project: power + source
 *  selector share the top row, the D-pad and vertical volume rocker share the middle row (equal
 *  height, side by side), and back/menu/home/mute form the bottom row, evenly spaced - matching
 *  the reference layout mockup. */
@Composable
fun RemoteControlPad(volume: VolumeInfo?, isMuted: Boolean, usbSupported: Boolean = true, menuSupported: Boolean = true, onPress: (toolName: String, label: String) -> Unit) {
    Surface(
        color = RemoteBlack,
        shape = RoundedCornerShape(32.dp),
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                RoundIconButton(Icons.Filled.PowerSettingsNew, "Power", ProjectorRed, Color.White, size = 60.dp) {
                    onPress("power", "Power")
                }
                SourceSelector(onPress, usbSupported, modifier = Modifier.weight(1f))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Box(modifier = Modifier.weight(1f).height(MiddleRowHeight), contentAlignment = Alignment.Center) {
                    DPad(onPress, modifier = Modifier.fillMaxHeight().aspectRatio(1f))
                }
                VerticalVolumeRocker(volume, onPress, modifier = Modifier.width(64.dp).height(MiddleRowHeight))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    RoundIconButton(Icons.Filled.ArrowBack, "Back", ButtonSurface, Color.White, size = 52.dp) {
                        onPress("back", "Back")
                    }
                    // Some projector models (F1, F1 Pro) map Menu to a recents action their
                    // launcher has no surface for - hidden entirely rather than shown dead,
                    // driven once per connection by CineAgent's GET_DEVICE_INFO, same as USB.
                    if (menuSupported) {
                        RoundIconButton(Icons.Filled.Menu, "Menu", ButtonSurface, Color.White, size = 52.dp) {
                            onPress("menu", "Menu")
                        }
                    }
                    RoundIconButton(Icons.Filled.Home, "Home", ButtonSurface, Color.White, size = 52.dp) {
                        onPress("home", "Home")
                    }
                    RoundIconButton(Icons.Filled.Search, "Search", ButtonSurface, Color.White, size = 52.dp) {
                        onPress("search", "Search")
                    }
                }
                // Same 64.dp width as the volume rocker above so Mute lines up under it.
                Box(modifier = Modifier.width(64.dp), contentAlignment = Alignment.Center) {
                    RoundIconButton(
                        if (isMuted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                        "Mute",
                        if (isMuted) ProjectorRed else ButtonSurface,
                        Color.White,
                        size = 52.dp
                    ) {
                        onPress("mute", "Mute")
                    }
                }
            }
        }
    }
}

/** Media transport row (previous / rewind / play-pause / fast-forward / next) - same dark card
 *  and round-button styling as [RemoteControlPad] so it reads as part of the same remote. */
@Composable
fun PlayControlRow(onPress: (toolName: String, label: String) -> Unit) {
    Surface(
        color = RemoteBlack,
        shape = RoundedCornerShape(32.dp),
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp, horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            RoundIconButton(Icons.Filled.SkipPrevious, "Previous", ButtonSurface, Color.White, size = 52.dp) {
                onPress("media_previous", "Previous")
            }
            RoundIconButton(Icons.Filled.FastRewind, "Rewind", ButtonSurface, Color.White, size = 52.dp) {
                onPress("media_rewind", "Rewind")
            }
            PlayPauseButton(onPress)
            RoundIconButton(Icons.Filled.FastForward, "Fast forward", ButtonSurface, Color.White, size = 52.dp) {
                onPress("media_fast_forward", "Fast forward")
            }
            RoundIconButton(Icons.Filled.SkipNext, "Next", ButtonSurface, Color.White, size = 52.dp) {
                onPress("media_next", "Next")
            }
        }
    }
}

/** Toggles its own icon between play/pause on each tap - the projector doesn't report actual
 *  playback state, so this reflects what the button just told it to do, not a confirmed state. */
@Composable
private fun PlayPauseButton(onPress: (String, String) -> Unit) {
    var isPlaying by remember { mutableStateOf(true) }
    RoundIconButton(
        icon = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
        contentDescription = if (isPlaying) "Pause" else "Play",
        background = ProjectorRed,
        iconTint = Color.White,
        size = 60.dp
    ) {
        onPress("media_play_pause", if (isPlaying) "Pause" else "Play")
        isPlaying = !isPlaying
    }
}

/** Shared height for the D-pad and the vertical volume rocker so the two line up exactly. */
private val MiddleRowHeight = 240.dp

@Composable
private fun SourceSelector(onPress: (String, String) -> Unit, usbSupported: Boolean, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = DPadTrack,
        modifier = modifier.height(56.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
            SourceButton(Icons.Filled.SettingsInputHdmi, "HDMI", Modifier.weight(1f)) {
                onPress("switch_to_hdmi", "Source: HDMI")
            }
            // Some projector models (F1, F1 Pro) have no working USB source - hidden entirely
            // rather than shown disabled, since CineAgent reports this once per connection via
            // GET_DEVICE_INFO, not something the user can toggle or unlock from here.
            if (usbSupported) {
                SourceButton(Icons.Filled.Usb, "USB", Modifier.weight(1f)) {
                    onPress("switch_to_usb", "Source: USB")
                }
            }
        }
    }
}

@Composable
private fun SourceButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        modifier = modifier.fillMaxWidth()
    ) {
        IconButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(icon, contentDescription = label, tint = Color.White)
                Text(label, color = Color.White, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun DPad(onPress: (String, String) -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(DPadTrack, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        RoundIconButton(
            icon = Icons.Filled.KeyboardArrowUp,
            contentDescription = "Up",
            background = Color.Transparent,
            iconTint = Color.White,
            size = 56.dp,
            modifier = Modifier.align(Alignment.TopCenter)
        ) { onPress("up", "Up") }

        RoundIconButton(
            icon = Icons.Filled.KeyboardArrowDown,
            contentDescription = "Down",
            background = Color.Transparent,
            iconTint = Color.White,
            size = 56.dp,
            modifier = Modifier.align(Alignment.BottomCenter)
        ) { onPress("down", "Down") }

        RoundIconButton(
            icon = Icons.Filled.KeyboardArrowLeft,
            contentDescription = "Left",
            background = Color.Transparent,
            iconTint = Color.White,
            size = 56.dp,
            modifier = Modifier.align(Alignment.CenterStart)
        ) { onPress("left", "Left") }

        RoundIconButton(
            icon = Icons.Filled.KeyboardArrowRight,
            contentDescription = "Right",
            background = Color.Transparent,
            iconTint = Color.White,
            size = 56.dp,
            modifier = Modifier.align(Alignment.CenterEnd)
        ) { onPress("right", "Right") }

        Surface(
            shape = CircleShape,
            color = ProjectorRed,
            shadowElevation = 4.dp,
            modifier = Modifier.size(72.dp).align(Alignment.Center)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = { onPress("ok", "OK") }, modifier = Modifier.fillMaxWidth()) {
                    Text("OK", color = Color.White, style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}

@Composable
private fun VerticalVolumeRocker(volume: VolumeInfo?, onPress: (String, String) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(50),
        color = DPadTrack,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = { onPress("volume_up", "Volume +") }) {
                Icon(Icons.Filled.Add, contentDescription = "Volume up", tint = Color.White)
            }
            Text(
                volume?.current?.toString() ?: "-",
                color = Color(0xFF9AA0A6),
                style = MaterialTheme.typography.bodyLarge
            )
            IconButton(onClick = { onPress("volume_down", "Volume -") }) {
                Icon(Icons.Filled.Remove, contentDescription = "Volume down", tint = Color.White)
            }
        }
    }
}

@Composable
private fun RoundIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    background: Color,
    iconTint: Color,
    size: Dp = 56.dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = background,
        modifier = modifier.size(size)
    ) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = contentDescription, tint = iconTint)
        }
    }
}
