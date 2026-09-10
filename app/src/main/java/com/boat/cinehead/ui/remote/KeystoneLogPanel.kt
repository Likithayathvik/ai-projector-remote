package com.boat.cinehead.ui.remote

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.util.Locale

private val QUAD_COLOR = Color(0xFF7CFFB2)
private val FRAME_COLOR = Color(0xFF3A424C)

/**
 * Auto Keystone Correction panel, opened by [QuickLaunchRow]'s button. Floats over the
 * *bottom* of the content column (below Quick Launch), leaving RemoteControlPad's D-pad clear.
 *
 * Draws the electronic keystone-correction geometry - a faint rectangle for the ideal full
 * frame, with the warped quad ([quad], absolute coords in a [KEYSTONE_REF_W] x
 * [KEYSTONE_REF_H] frame) drawn inside it and the coordinates at each corner. This is the
 * region the compositor warps the source into, not the shape on the wall (which also carries
 * the projector's fixed physical throw angle). RemoteViewModel cycles it: quad for ~2s, then
 * [loading] for ~3s while the next GET_KEYSTONE round-trips, then the quad is re-shown.
 */
@Composable
fun KeystonePanel(
    quad: KeystoneQuad?,
    loading: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = Color(0xE6101418),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Keystone position",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(KEYSTONE_REF_W / KEYSTONE_REF_H)
                    .padding(top = 4.dp, bottom = 8.dp)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val pad = 20.dp.toPx()
                    val availW = size.width - 2 * pad
                    val availH = size.height - 2 * pad

                    fun map(p: KeystonePoint) = Offset(
                        pad + (p.x / KEYSTONE_REF_W) * availW,
                        pad + (p.y / KEYSTONE_REF_H) * availH,
                    )

                    // Ideal full frame.
                    drawRect(
                        color = FRAME_COLOR,
                        topLeft = Offset(pad, pad),
                        size = Size(availW, availH),
                        style = Stroke(width = 1.dp.toPx()),
                    )

                    if (quad != null) {
                        val a = map(quad.topLeft)
                        val b = map(quad.topRight)
                        val c = map(quad.bottomRight)
                        val d = map(quad.bottomLeft)
                        val path = Path().apply {
                            moveTo(a.x, a.y); lineTo(b.x, b.y)
                            lineTo(c.x, c.y); lineTo(d.x, d.y); close()
                        }
                        drawPath(path, color = QUAD_COLOR.copy(alpha = 0.16f))
                        drawPath(path, color = QUAD_COLOR, style = Stroke(width = 2.dp.toPx()))
                        listOf(a, b, c, d).forEach { drawCircle(QUAD_COLOR, 3.dp.toPx(), it) }
                    }
                }

                CornerLabel(quad?.topLeft, Modifier.align(Alignment.TopStart), TextAlign.Start)
                CornerLabel(quad?.topRight, Modifier.align(Alignment.TopEnd), TextAlign.End)
                CornerLabel(quad?.bottomLeft, Modifier.align(Alignment.BottomStart), TextAlign.Start)
                CornerLabel(quad?.bottomRight, Modifier.align(Alignment.BottomEnd), TextAlign.End)

                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = QUAD_COLOR,
                        strokeWidth = 2.dp,
                    )
                } else if (quad == null) {
                    Text(
                        "Reading…",
                        color = Color(0xFFB0B8C0),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
        }
    }
}

@Composable
private fun CornerLabel(point: KeystonePoint?, modifier: Modifier, align: TextAlign) {
    Text(
        text = point?.let { String.format(Locale.US, "(%.0f, %.0f)", it.x, it.y) } ?: "(–, –)",
        color = QUAD_COLOR,
        style = MaterialTheme.typography.labelSmall,
        textAlign = align,
        modifier = modifier
    )
}
