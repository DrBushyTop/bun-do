package fi.bundo.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import fi.bundo.R

@Composable
internal fun SharedHouseholdNavigation(selected: String, rail: Boolean = false, onSelect: (String) -> Unit) {
    val destinations = listOf(
            Triple("queue", R.string.task_active_view, Icons.AutoMirrored.Outlined.List),
            Triple("lists", R.string.lists_title, Icons.Outlined.CheckCircle),
            Triple("together", R.string.progress_together, Icons.Outlined.FavoriteBorder),
            Triple("activity", R.string.progress_activity, Icons.Outlined.DateRange))
    if (rail) NavigationRail(Modifier.fillMaxHeight(), windowInsets = WindowInsets(0, 0, 0, 0)) {
        for ((id, label, icon) in destinations) NavigationRailItem(selected = selected == id, onClick = { onSelect(id) },
            modifier = Modifier.testTag("household-$id"), icon = { Icon(icon, null) }, label = { Text(stringResource(label)) })
    } else NavigationBar {
        for ((id, label, icon) in destinations) {
            NavigationBarItem(selected = selected == id, onClick = { onSelect(id) },
                modifier = Modifier.testTag("household-$id"),
                icon = { Icon(icon, contentDescription = null) }, label = { Text(stringResource(label)) })
        }
    }
}
