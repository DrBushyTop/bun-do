package fi.bundo.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import fi.bundo.R
import fi.bundo.data.AdventureSnapshot

/** A single scene owns the wordmark and signpost, without owning any task actions. */
@Composable
internal fun HouseholdScene(scene: String, visible: Boolean, adventure: AdventureSnapshot?,
    onAdventure: () -> Unit, acknowledge: suspend (String) -> Boolean, topBar: @Composable () -> Unit) {
    if (shortWorldWindow()) {
        Row(Modifier.fillMaxWidth().testTag("household-header"), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { topBar() }
            Box(Modifier.weight(1f).statusBarsPadding()) { AdventureSignpost(adventure, onAdventure, acknowledge, compact = true) }
        }
        return
    }
    val illustrated = visible && worldVisible()
    val paper = MaterialTheme.colorScheme.surface
    val context = LocalContext.current
    val mood = scene.takeIf { illustrated && it in listOf("rest", "paperwork", "joy") }
    val moodPainter = remember(mood, context) { mood?.let {
        context.assets.open("world/bun_$it.webp").use { stream -> BitmapPainter(android.graphics.BitmapFactory.decodeStream(stream).asImageBitmap()) }
    } }
    Box(Modifier.fillMaxWidth().testTag("household-header")) {
        if (illustrated) Image(moodPainter ?: painterResource(R.drawable.dojo_garden), null, contentScale = ContentScale.Crop, alignment = Alignment.Center,
            modifier = Modifier.matchParentSize().testTag("household-world").drawWithContent {
                drawContent()
                drawRect(Brush.verticalGradient(0f to paper.copy(alpha = .95f), .28f to Color.Transparent, .74f to Color.Transparent, 1f to paper))
            })
        Column(Modifier.fillMaxWidth()) {
            topBar()
            if (illustrated) Spacer(Modifier.fillMaxWidth().height(112.dp).testTag("world-$scene"))
            AdventureSignpost(adventure, onAdventure, acknowledge)
        }
    }
}

@Composable
internal fun AdventureSignpost(snapshot: AdventureSnapshot?, onOpen: () -> Unit, acknowledge: suspend (String) -> Boolean, compact: Boolean = false) {
    val active = snapshot?.active
    val complete = snapshot?.complete == true
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val motion = LocalHouseholdMotion.current
    val stamp = remember(active?.id) { Animatable(1f) }
    LaunchedEffect(active?.id, complete, motion.enabled) {
        if (active != null && complete) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            stamp.snapTo(1f)
            if (acknowledge(active.id) && motion.enabled) {
                stamp.snapTo(1.2f); stamp.animateTo(1f, tween(320))
            }
        }
    }
    Surface(onClick = onOpen, modifier = Modifier.fillMaxWidth().testTag("adventure-signpost"),
        color = MaterialTheme.colorScheme.surfaceContainer, contentColor = MaterialTheme.colorScheme.onSurface) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = if (compact) 4.dp else 8.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painterResource(R.drawable.adventure_sign), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (complete) stringResource(R.string.adventure_complete_short) else
                    if (compact) stringResource(R.string.adventure_title) else active?.draft?.title ?: stringResource(R.string.adventure_title),
                    maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                if (active != null) {
                    val accessibleProgress = stringResource(R.string.adventure_progress, snapshot.completed, snapshot.total)
                    Text(if (compact) "${snapshot.completed} / ${snapshot.total}" else
                        pluralStringResource(R.plurals.adventure_signpost_progress, snapshot.total, snapshot.completed, snapshot.total), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("signpost-progress").semantics { contentDescription = accessibleProgress })
                    LinearProgressIndicator(progress = { if (snapshot.total == 0) 0f else snapshot.completed.toFloat() / snapshot.total },
                        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { }, gapSize = 0.dp, drawStopIndicator = {})
                }
            }
            Icon(if (complete) Icons.Outlined.CheckCircle else Icons.AutoMirrored.Outlined.ArrowForward, null,
                modifier = Modifier.size(24.dp).graphicsLayer { scaleX = stamp.value; scaleY = stamp.value }, tint = MaterialTheme.colorScheme.primary)
        }
    }
}
