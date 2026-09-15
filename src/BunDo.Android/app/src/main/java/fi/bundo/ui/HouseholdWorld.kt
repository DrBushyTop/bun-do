package fi.bundo.ui

import android.content.SharedPreferences
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import fi.bundo.R
import androidx.core.content.edit

@Composable
private fun worldPreference(): Pair<Boolean, (Boolean) -> Unit> {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("appearance", 0) }
    var visible by remember(preferences) { mutableStateOf(preferences.getBoolean("world", true)) }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "world") visible = preferences.getBoolean("world", true)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return visible to { value -> preferences.edit { putBoolean("world", value) } }
}

/** Decoration only. No sample quests, progress or inferred household mood. */
@Composable
internal fun HouseholdWorld() {
    val configuration = LocalConfiguration.current
    val height = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    if (!worldPreference().first || height < 600.dp || configuration.fontScale >= 1.6f) return
    val paper = MaterialTheme.colorScheme.surface
    Image(painterResource(R.drawable.dojo_garden), null,
        contentScale = ContentScale.Crop, alignment = Alignment.BottomCenter,
        modifier = Modifier.fillMaxWidth().height(180.dp).testTag("household-world").drawWithContent {
            drawContent()
            drawRect(Brush.verticalGradient(0f to paper, .18f to Color.Transparent, .8f to Color.Transparent, 1f to paper))
        })
}

@Composable
internal fun WorldPreference() {
    val (visible, change) = worldPreference()
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("show-world")
        .toggleable(visible, role = Role.Switch, onValueChange = change), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.show_world), Modifier.weight(1f))
        Switch(visible, onCheckedChange = null)
    }
    Text(stringResource(R.string.show_world_hint), style = MaterialTheme.typography.bodySmall)
}
