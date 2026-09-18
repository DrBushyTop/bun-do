package fi.bundo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import fi.bundo.R

/** Automatic reads stay quiet. Only an explicit pull shows refresh progress. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RefreshPage(label: String, refreshing: Boolean, enabled: Boolean, onRefresh: () -> Unit,
    modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    var manual by remember { mutableStateOf(false) }
    LaunchedEffect(manual, refreshing) { if (!refreshing) manual = false }
    val refresh = { if (enabled && !refreshing) { manual = true; onRefresh() } }
    PullToRefreshBox(isRefreshing = manual && refreshing, onRefresh = refresh,
        modifier = modifier.fillMaxSize().semantics {
            customActions = listOf(CustomAccessibilityAction(label) {
                if (enabled && !refreshing) { refresh(); true } else false
            })
        }, content = content)
}

@Composable
internal fun RefreshHeading(title: String, refreshLabel: String, enabled: Boolean, onRefresh: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.weight(1f).semantics { heading() })
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.testTag("page-options")) {
                Icon(Icons.Outlined.MoreVert, stringResource(R.string.queue_tools))
            }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text(refreshLabel) }, enabled = enabled,
                    modifier = Modifier.testTag("page-refresh"), onClick = { menu = false; onRefresh() })
            }
        }
    }
}
