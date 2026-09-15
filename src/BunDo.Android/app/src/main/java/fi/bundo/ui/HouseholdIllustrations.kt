package fi.bundo.ui

import androidx.core.content.edit
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import fi.bundo.R
import kotlin.math.sin

/** Decorative events are consumed once per app session, including when motion is disabled. */
class HouseholdMotion {
    var preferred by mutableStateOf(true)
    var systemEnabled by mutableStateOf(false)
    val enabled get() = preferred && systemEnabled
    private val claims = mutableMapOf<String, String?>()
    fun observeClaim(task: String, claimant: String?): Boolean {
        val fresh = claimant != null && claims[task] != claimant
        claims[task] = claimant
        return fresh
    }
}

val LocalHouseholdMotion = staticCompositionLocalOf { HouseholdMotion() }

@Composable
fun HouseholdMotionProvider(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("appearance", 0) }
    val motion = remember { HouseholdMotion().apply { preferred = preferences.getBoolean("motion", true) } }
    DisposableEffect(context) {
        fun update() { motion.systemEnabled = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = update()
        }
        update()
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    CompositionLocalProvider(LocalHouseholdMotion provides motion, content = content)
}

@Composable
internal fun MotionPreference() {
    val context = LocalContext.current
    val motion = LocalHouseholdMotion.current
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("decorative-motion")
        .toggleable(motion.preferred, role = Role.Switch, onValueChange = {
            motion.preferred = it
            context.getSharedPreferences("appearance", 0).edit { putBoolean("motion", it) }
        }), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.decorative_motion), Modifier.weight(1f))
        Switch(checked = motion.preferred, onCheckedChange = null)
    }
    Text(stringResource(R.string.motion_system_override), style = MaterialTheme.typography.bodySmall)
}

// These cues are presentation only. They never become task fields or AI classifications.
internal fun taskCue(title: String): String {
    val text = title.lowercase(java.util.Locale.ROOT)
    return when {
        listOf("pyör", "bike", "bicycle").any(text::contains) -> "bike"
        listOf("kissa", "koira", "eläin", "pet", "dog", "cat ", "vet").any(text::contains) -> "pet"
        listOf("osta", "kauppa", "buy ", "shop", "grocer").any(text::contains) -> "shop"
        listOf("varasto", "storage", "hylly", "shelf").any(text::contains) -> "storage"
        else -> "task"
    }
}

@Composable
internal fun TaskCue(title: String) {
    Icon(painterResource(when (taskCue(title)) {
        "bike" -> R.drawable.cue_bike
        "pet" -> R.drawable.cue_pet
        "shop" -> R.drawable.cue_shop
        "storage" -> R.drawable.cue_home
        else -> R.drawable.cue_task
    }), contentDescription = null, tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp).size(28.dp))
}

@Composable
internal fun IllustratedTaskHeader(title: String, attribution: (@Composable () -> Unit)? = null) {
    val art = when (taskCue(title)) {
        "bike" -> R.drawable.bike_royal
        "pet" -> R.drawable.pet_royal
        "storage" -> R.drawable.dojo_storage
        else -> null
    }
    val surface = MaterialTheme.colorScheme.surface
    Box(Modifier.fillMaxWidth().heightIn(min = if (art == null) 0.dp else 156.dp)) {
        if (art != null) {
            Image(painterResource(art), null, contentScale = ContentScale.Fit, alignment = Alignment.CenterEnd,
                modifier = Modifier.matchParentSize())
            // One continuous veil keeps all text legible without cropping the subject.
            Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
                0f to surface, .55f to surface.copy(alpha = .96f), 1f to surface.copy(alpha = .62f))))
        }
        Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            attribution?.invoke()
        }
    }
}

@Composable
internal fun ClaimantIllustration(taskId: String, claimant: String?, name: String) {
    val motion = LocalHouseholdMotion.current
    val progress = remember(taskId) { Animatable(1f) }
    LaunchedEffect(taskId, claimant, motion.enabled) {
        val fresh = motion.observeClaim(taskId, claimant)
        if (fresh && motion.enabled) { progress.snapTo(0f); progress.animateTo(1f, tween(800)) }
        else progress.snapTo(1f)
    }
    if (claimant == null) return
    val bunny = claimant.hashCode() and 1 == 0
    val ink = MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(Modifier.size(32.dp)) {
            val unit = size.minDimension / 24f
            fun ellipse(x: Float, y: Float, w: Float, h: Float) =
                drawOval(ink, Offset(x * unit, y * unit), Size(w * unit, h * unit), style = Stroke(1.4f * unit))
            if (bunny) { ellipse(4f, 1f, 5f, 10f); ellipse(13f, 1f, 5f, 10f); ellipse(3f, 10f, 16f, 12f) }
            else { ellipse(0f, 3f, 8f, 8f); ellipse(15f, 3f, 8f, 8f); ellipse(4f, 5f, 15f, 17f) }
            drawCircle(ink, unit * .8f, Offset(8f * unit, 13f * unit))
            drawCircle(ink, unit * .8f, Offset(15f * unit, 13f * unit))
            drawOval(ink, Offset(10f * unit, 14f * unit), Size(3f * unit, (if (bunny) 2f else 5f) * unit))
            val stroke = if (progress.value < 1f) sin(progress.value * Math.PI * 4).toFloat() * 1.2f else 0f
            drawLine(ink, Offset((18 + stroke) * unit, 23f * unit), Offset((23 + stroke) * unit, 17f * unit), unit * 1.5f)
        }
        Text(name, style = MaterialTheme.typography.labelLarge)
    }
}

data class HouseholdFeedback(val id: Long, val kind: String)

@Composable
internal fun HouseholdSnackbar(host: SnackbarHostState, event: HouseholdFeedback?) {
    SnackbarHost(host) { data ->
        Snackbar(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            action = data.visuals.actionLabel?.let { label -> {
                TextButton(onClick = data::performAction) { Text(label) }
            } },
            dismissAction = if (data.visuals.withDismissAction) ({
                IconButton(onClick = data::dismiss) { Icon(Icons.Outlined.Close, stringResource(R.string.dismiss_confirmation)) }
            }) else null,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionIllustration(event)
                Text(data.visuals.message, Modifier.weight(1f))
            }
        }
    }
}

/** This never owns action state or intercepts input. A static check remains with reduced motion. */
@Composable
internal fun ActionIllustration(event: HouseholdFeedback?) {
    if (event == null) return
    val motion = LocalHouseholdMotion.current
    val progress = remember { Animatable(1f) }
    LaunchedEffect(event.id, motion.enabled) {
        progress.snapTo(if (motion.enabled) 0f else 1f)
        if (motion.enabled) progress.animateTo(1f, tween(360))
    }
    if (event.kind == "file") {
        Box(Modifier.size(32.dp).testTag("action-feedback")) {
            Icon(painterResource(R.drawable.filing_paper), null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.matchParentSize().graphicsLayer { translationY = -(1f - progress.value) * 10.dp.toPx() })
            Icon(painterResource(R.drawable.filing), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.matchParentSize())
        }
        return
    }
    val pulse = sin(progress.value * Math.PI).toFloat()
    val variant = if (event.kind == "complete") event.id % 3 else -1
    Icon(
        if (variant == 0L || variant == 1L) painterResource(R.drawable.bun_do)
        else painterResource(R.drawable.action_check),
        null, tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(32.dp).testTag("action-feedback").graphicsLayer {
            rotationZ = if (variant == 0L) pulse * 14f else 0f
            translationY = if (variant == 1L) -pulse * 8.dp.toPx() else 0f
            scaleX = 1f + if (variant == 2L || event.kind == "claim") pulse * .12f else 0f
            scaleY = scaleX
        },
    )
}
