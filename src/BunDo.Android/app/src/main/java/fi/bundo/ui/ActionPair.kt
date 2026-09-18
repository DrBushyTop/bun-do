package fi.bundo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** Related actions share a row unless the available width or text size needs a stack. */
@Composable
internal fun ActionPair(
    modifier: Modifier = Modifier,
    secondary: @Composable (Modifier) -> Unit,
    primary: @Composable (Modifier) -> Unit,
) {
    val scale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (maxWidth < 320.dp || maxWidth < 600.dp && scale > 1.3f) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                secondary(Modifier.fillMaxWidth().heightIn(min = 48.dp))
                primary(Modifier.fillMaxWidth().heightIn(min = 48.dp))
            }
        } else {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                secondary(Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp))
                primary(Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp))
            }
        }
    }
}
