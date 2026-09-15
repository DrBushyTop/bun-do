package fi.bundo.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import fi.bundo.R

@Composable
internal fun SharedHouseholdNavigation(selected: String, onSelect: (String) -> Unit) {
    NavigationBar {
        for ((id, label, icon) in listOf(
            Triple("queue", R.string.task_active_view, Icons.AutoMirrored.Outlined.List),
            Triple("activity", R.string.progress_activity, Icons.Outlined.DateRange),
            Triple("together", R.string.progress_together, Icons.Outlined.FavoriteBorder))) {
            NavigationBarItem(selected = selected == id, onClick = { onSelect(id) },
                modifier = Modifier.testTag("household-$id"),
                icon = { Icon(icon, contentDescription = null) }, label = { Text(stringResource(label)) })
        }
    }
}
