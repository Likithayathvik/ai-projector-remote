package com.boat.cinehead.ui.equalizer

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs

private val CardBg = Color(0xFF1C1C1E)
private val ChipBg = Color(0xFF2A2A2C)
private val Track = Color(0xFF0E0E0F)
private val Muted = Color(0xFF9A9A9E)
private val BoatRed = Color(0xFFE21B23)
private val Knob = Color(0xFF2B2B2E)

@Composable
fun EqualizerScreen(viewModel: EqualizerViewModel) {
    val s by viewModel.state.collectAsState()

    LaunchedEffect(s.notice) {
        if (s.notice != null) {
            delay(3500)
            viewModel.consumeNotice()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        when {
            s.loading -> Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = BoatRed)
            }
            s.unsupported -> MessageCard(
                "Update CineAgent on the projector to use Dolby Audio and the equalizer.", viewModel::refresh
            )
            s.error != null && s.bandsHz.isEmpty() -> MessageCard(s.error!!, viewModel::refresh)
            else -> {
                s.dolby?.let { DolbyCard(on = it, enabled = !s.busy, onToggle = viewModel::setDolby) }
                EqualizerCard(s, viewModel)
                s.notice?.let { Text(it, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp)) }
                s.error?.let { Text("✗ $it", color = BoatRed, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp)) }
            }
        }
    }
}

@Composable
private fun MessageCard(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CardBg).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(message, color = Color.White, fontSize = 15.sp)
        TextButton(onClick = onRetry) { Text("Retry", color = BoatRed) }
    }
}

/** Card title: a bold brand word, a thin rule, and a small icon at the end (as in the boAt app). */
@Composable
private fun CardHeader(brand: String, title: String, icon: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(brand) }
                append(" $title")
            },
            color = Color.White, fontSize = 20.sp
        )
        Box(Modifier.weight(1f).padding(horizontal = 16.dp).height(1.dp).background(Color(0xFF3A3A3C)))
        icon()
    }
}

@Composable
private fun DolbyCard(on: Boolean, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CardBg).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        CardHeader("Dolby", "Audio") { WaveIcon() }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Dive into audio with an added dimension for an immersive experience.",
                color = Muted, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            DolbyToggle(on, enabled) { onToggle(!on) }
        }
    }
}

/** The ON/OFF pill: the knob (with a sail mark) slides right when on. */
@Composable
private fun DolbyToggle(on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val knobX by animateDpAsState(if (on) 58.dp else 4.dp, label = "knob")
    Box(
        Modifier
            .width(100.dp).height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xFF141415))
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Text(
            if (on) "ON" else "OFF",
            color = if (on) BoatRed else Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(if (on) Alignment.CenterStart else Alignment.CenterEnd)
                .padding(horizontal = 16.dp)
        )
        Box(
            Modifier.offset(x = knobX).align(Alignment.CenterStart).size(38.dp)
                .clip(CircleShape).background(Knob)
                .border(1.dp, Color(0xFF444447), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.size(16.dp)) {
                val c = if (on) BoatRed else Muted
                // A sail and hull, the boAt mark's shape.
                drawPath(Path().apply {
                    moveTo(size.width * 0.55f, 0f); lineTo(size.width * 0.9f, size.height * 0.72f)
                    lineTo(size.width * 0.2f, size.height * 0.72f); close()
                }, c)
                drawRect(c, Offset(0f, size.height * 0.82f), androidx.compose.ui.geometry.Size(size.width, size.height * 0.14f))
            }
        }
    }
}

@Composable
private fun EqualizerCard(s: EqualizerUiState, viewModel: EqualizerViewModel) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CardBg)) {
        Column(
            Modifier.fillMaxWidth().alpha(if (s.busy) 0.45f else 1f).padding(vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(Modifier.padding(horizontal = 20.dp)) { CardHeader("boAt", "Equalizer") { SlidersIcon() } }
            Bands(s, enabled = !s.busy, onDrag = viewModel::dragBand, onDragEnd = viewModel::commitCustom)
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(s.modes, key = { it.id }) { mode ->
                    PresetChip(
                        // WhaleOS "Standard" is the projector's flat sound and "User" its slider-edited
                        // mode: shown as DEFAULT and CUSTOM, matching the NXP equalizer's names.
                        label = when {
                            mode.id == EqualizerUiState.DEFAULT || (s.whaleOs && mode.id == "standard") -> "DEFAULT"
                            s.whaleOs && mode.id == EqualizerUiState.WHALEOS_USER -> "CUSTOM"
                            else -> mode.name.uppercase()
                        },
                        selected = mode.id == s.mode,
                        enabled = !s.busy,
                    ) { viewModel.selectMode(mode.id) }
                }
            }
        }
        if (s.busy) CircularProgressIndicator(color = BoatRed, modifier = Modifier.align(Alignment.Center).size(32.dp), strokeWidth = 3.dp)
    }
}

/** Vertical sliders over a red area following the levels; drag a slider to make a Custom curve. */
@Composable
private fun Bands(s: EqualizerUiState, enabled: Boolean, onDrag: (Int, Float) -> Unit, onDragEnd: () -> Unit) {
    val n = s.gains.size
    if (n == 0) return
    var active by remember { mutableStateOf(-1) }
    val range = (s.maxDb - s.minDb).toFloat()
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Canvas(
            Modifier.fillMaxWidth().height(250.dp)
                .pointerInput(n, enabled, s.minDb, s.maxDb) {
                    if (!enabled) return@pointerInput
                    fun x(i: Int) = size.width * (i + 0.5f) / n
                    fun db(y: Float) = s.maxDb - (y / size.height).coerceIn(0f, 1f) * range
                    detectDragGestures(
                        onDragStart = { p ->
                            active = (0 until n).minBy { abs(x(it) - p.x) }
                            onDrag(active, db(p.y))
                        },
                        onDragEnd = { active = -1; onDragEnd() },
                        onDragCancel = { active = -1; onDragEnd() },
                    ) { change, _ -> if (active >= 0) onDrag(active, db(change.position.y)) }
                }
        ) {
            val pad = 14.dp.toPx()
            val top = pad
            val bottom = size.height - pad
            fun x(i: Int) = size.width * (i + 0.5f) / n
            fun y(db: Float) = top + (s.maxDb - db) / range * (bottom - top)
            val points = s.gains.mapIndexed { i, g -> Offset(x(i), y(g)) }

            // Red area under a smooth curve through the knobs, fading downwards.
            val area = Path().apply {
                moveTo(points.first().x - size.width / n / 2, bottom)
                lineTo(points.first().x - size.width / n / 2, points.first().y)
                lineTo(points.first().x, points.first().y)
                for (i in 1 until n) {
                    val a = points[i - 1]; val b = points[i]
                    val mid = (a.x + b.x) / 2
                    cubicTo(mid, a.y, mid, b.y, b.x, b.y)
                }
                lineTo(points.last().x + size.width / n / 2, points.last().y)
                lineTo(points.last().x + size.width / n / 2, bottom)
                close()
            }
            drawPath(area, Brush.verticalGradient(listOf(Color(0xCC8E0E14), Color(0x22400608)), startY = top, endY = bottom))

            points.forEachIndexed { i, p ->
                drawLine(Track, Offset(p.x, top), Offset(p.x, bottom), strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
                if (i == active) drawLine(BoatRed, p, Offset(p.x, bottom), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(Knob, radius = 11.dp.toPx(), center = p)
                drawCircle(Color(0xFF55555A), radius = 11.dp.toPx(), center = p, style = Stroke(1.5.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            s.bandsHz.forEach { hz ->
                Text(hzLabel(hz), color = Muted, fontSize = 15.sp, modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

@Composable
private fun PresetChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(
                if (selected) Brush.verticalGradient(listOf(Color(0xFF3A1416), ChipBg))
                else Brush.verticalGradient(listOf(ChipBg, ChipBg))
            )
            .then(if (selected) Modifier.border(BorderStroke(1.5.dp, BoatRed), RoundedCornerShape(22.dp)) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 26.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (selected) Color.White else Muted, fontSize = 14.sp, letterSpacing = 0.5.sp)
    }
}

/** 60 -> "60", 230 -> "230", 3600 -> "3.6K", 14000 -> "14K". */
private fun hzLabel(hz: Int): String = when {
    hz < 1000 -> "$hz"
    hz % 1000 == 0 -> "${hz / 1000}K"
    else -> "%.1fK".format(hz / 1000.0).replace(".0K", "K")
}

/** Small vertical bars, the "audio" mark beside the Dolby title. */
@Composable
private fun WaveIcon() {
    Canvas(Modifier.width(52.dp).height(22.dp)) {
        val heights = listOf(0.5f, 0.8f, 1f, 0.6f, 0.9f, 0.7f, 0.45f)
        val step = size.width / heights.size
        heights.forEachIndexed { i, h ->
            val x = step * (i + 0.5f)
            drawLine(Muted, Offset(x, size.height * (1 - h) / 2), Offset(x, size.height * (1 + h) / 2), 2.dp.toPx(), StrokeCap.Round)
        }
    }
}

/** Three slider lines with knobs, the mark beside the Equalizer title. */
@Composable
private fun SlidersIcon() {
    Canvas(Modifier.width(44.dp).height(24.dp)) {
        val knobs = listOf(0.3f, 0.65f, 0.4f, 0.2f)
        val step = size.width / knobs.size
        knobs.forEachIndexed { i, k ->
            val x = step * (i + 0.5f)
            drawLine(Muted, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx(), StrokeCap.Round)
            drawLine(Muted, Offset(x - 3.dp.toPx(), size.height * k), Offset(x + 3.dp.toPx(), size.height * k), 2.dp.toPx(), StrokeCap.Round)
        }
    }
}
