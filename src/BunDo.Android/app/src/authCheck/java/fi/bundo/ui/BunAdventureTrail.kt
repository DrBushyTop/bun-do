package fi.bundo.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import fi.bundo.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.sin

/** Bun's position is task progress. The occasional light never advances the trail. */
@Composable
internal fun BunAdventureTrail(completed: Int, total: Int, animated: Boolean, compact: Boolean) {
    val fraction = if (total <= 0) 0f else (completed.toFloat() / total).coerceIn(0f, 1f)
    val progress = remember { Animatable(fraction) }
    val light = remember { Animatable(0f) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val moving = animated && LocalHouseholdMotion.current.enabled && lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    LaunchedEffect(fraction, moving) {
        if (moving) progress.animateTo(fraction, tween(420)) else progress.snapTo(fraction)
    }
    LaunchedEffect(moving, completed, total) {
        light.snapTo(0f)
        if (moving && total > 0 && completed < total) while (isActive) {
            delay(4500)
            light.animateTo(1f, tween(1600, easing = LinearEasing))
            light.snapTo(0f)
        }
    }
    val colors = adventureSignpostColors()
    val ink = MaterialTheme.colorScheme.primary
    val bun = painterResource(R.drawable.bun_do)
    Canvas(Modifier.fillMaxWidth().height(if (compact) 4.dp else 22.dp)
        .testTag("bun-adventure-trail").clearAndSetSemantics { }) {
        val marker = if (compact) 0f else 18.dp.toPx()
        val inset = marker / 2
        val y = if (compact) size.height / 2 else size.height - 3.dp.toPx()
        val end = size.width - inset
        val x = inset + (end - inset) * progress.value
        val stroke = 4.dp.toPx()
        drawLine(colors.track, Offset(inset, y), Offset(end, y), stroke, StrokeCap.Round)
        if (progress.value > 0f) drawLine(ink, Offset(inset, y), Offset(x, y), stroke, StrokeCap.Round)
        // A brief amber reflection travels only through work already completed.
        val gleam = light.value
        if (gleam > 0f && gleam < 1f) {
            val width = 28.dp.toPx()
            val center = inset - width + (x - inset + width * 2) * gleam
            clipRect(left = (inset - stroke / 2).coerceAtLeast(0f), right = (x + stroke / 2).coerceAtMost(size.width)) {
                drawLine(Brush.horizontalGradient(listOf(Color.Transparent, colors.amber, Color.Transparent),
                    center - width, center + width), Offset(inset, y), Offset(x, y), stroke, StrokeCap.Round)
            }
            if (!compact) drawCircle(colors.amber.copy(alpha = .3f * sin(gleam * Math.PI).toFloat()),
                7.dp.toPx(), Offset(x, y))
        }
        if (!compact) {
            // Reuse the approved silhouette unchanged. It rests at the true progress frontier.
            drawCircle(colors.paper, marker / 2 + 1.dp.toPx(), Offset(x, y - marker / 2))
            translate(left = x - marker / 2, top = y - marker) {
                with(bun) { draw(Size(marker, marker), colorFilter = ColorFilter.tint(ink)) }
            }
        }
    }
}
