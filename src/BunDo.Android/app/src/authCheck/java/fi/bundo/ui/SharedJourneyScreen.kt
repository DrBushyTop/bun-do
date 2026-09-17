package fi.bundo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import fi.bundo.R
import fi.bundo.data.JourneyProgress
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun SharedJourneyScreen(progress: String?, busy: Boolean, failed: Boolean, allowed: Boolean,
    onStart: () -> Unit, onRefresh: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val snapshot = progress?.let(::JSONObject)
    val journey = snapshot?.let(JourneyProgress::read)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("journey-screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.journey_back)) }
            Text(stringResource(R.string.journey_title), style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() })
        }
        HouseholdWorld()
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("journey-busy"))
            if (failed) Text(stringResource(R.string.journey_failed), color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("journey-error"))
            if (snapshot == null) {
                Text(stringResource(R.string.journey_unavailable))
                OutlinedButton(onClick = onRefresh, enabled = allowed && !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("journey-refresh")) {
                    Text(stringResource(R.string.household_refresh))
                }
            } else if (journey == null) {
                Text(stringResource(R.string.journey_intro), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.journey_start_body))
                Button(onClick = onStart, enabled = allowed && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("journey-start")) {
                    Text(stringResource(R.string.journey_start))
                }
                Text(stringResource(R.string.journey_start_hint), style = MaterialTheme.typography.bodySmall)
            } else {
                Text(stringResource(routeName(journey.routeId)), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.journey_at, locationName(journey.locationId, journey.locationIndex)))
                Text(pluralStringResource(R.plurals.journey_progress, journey.completionsPerLocation, journey.locationCompletions, journey.completionsPerLocation),
                    modifier = Modifier.testTag("journey-progress"))
                LinearProgressIndicator(progress = { journey.locationCompletions.toFloat() / journey.completionsPerLocation },
                    modifier = Modifier.fillMaxWidth().clearAndSetSemantics { }, gapSize = 0.dp, drawStopIndicator = {})
                if (journey.resting) Text(stringResource(R.string.journey_resting), modifier = Modifier.testTag("journey-resting"))
                JourneyStops(journey)
                HelpDisclosure(stringResource(R.string.journey_how), Modifier.testTag("journey-details")) {
                    Text(stringResource(R.string.journey_explanation), style = MaterialTheme.typography.bodyMedium)
                }
                val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(LocalConfiguration.current.locales[0])
                    .withZone(ZoneId.of(snapshot.getJSONObject("statistics").getString("zoneId")))
                Text(stringResource(R.string.progress_as_of, formatter.format(Instant.parse(snapshot.getString("asOf")))),
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onRefresh, enabled = allowed && !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("journey-refresh")) {
                    Text(stringResource(R.string.household_refresh))
                }
                if (journey.completedRoutes.isNotEmpty()) {
                    HorizontalDivider()
                    Text(stringResource(R.string.journey_history), style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() })
                    journey.completedRoutes.forEach { route ->
                        Text(stringResource(routeName(route)), modifier = Modifier.testTag("journey-history-$route"))
                    }
                }
            }
        }
    }
}

@Composable
private fun JourneyStops(journey: JourneyProgress) {
    val stops = if (journey.routeId == "dojo-garden")
        listOf("dojo-gate", "bamboo-path", "moss-bridge", "cedar-ridge", "lantern-garden")
    else listOf(journey.locationId)
    Column {
        stops.forEachIndexed { index, location ->
            val current = location == journey.locationId
            val visited = journey.resting || journey.routeId == "dojo-garden" && index < journey.locationIndex
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 8.dp).semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.size(48.dp).background(
                    if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer, CircleShape),
                    contentAlignment = Alignment.Center) {
                    when {
                        current -> Icon(painterResource(R.drawable.bun_do), null,
                            Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onPrimary)
                        visited -> Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary)
                        else -> Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.outline, CircleShape))
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(locationName(location, if (stops.size == 1) journey.locationIndex else index),
                        style = if (current) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge)
                    if (current || visited) Text(stringResource(if (current) R.string.journey_here else R.string.journey_visited),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun routeName(id: String) = if (id == "dojo-garden") R.string.journey_route_dojo else R.string.journey_route_other

@Composable
private fun locationName(id: String, index: Int): String = when (id) {
    "dojo-gate" -> stringResource(R.string.journey_dojo_gate)
    "bamboo-path" -> stringResource(R.string.journey_bamboo_path)
    "moss-bridge" -> stringResource(R.string.journey_moss_bridge)
    "cedar-ridge" -> stringResource(R.string.journey_cedar_ridge)
    "lantern-garden" -> stringResource(R.string.journey_lantern_garden)
    else -> stringResource(R.string.journey_location_other, index + 1)
}
