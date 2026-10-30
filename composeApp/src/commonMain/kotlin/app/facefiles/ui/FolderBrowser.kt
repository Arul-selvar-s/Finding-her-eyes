package app.facefiles.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.facefiles.domain.FileCategory

/** Live file-explorer view ("All folders"): folders first, then files, straight from the file system. */
@Composable
fun FolderBrowser(
    services: AppServices,
    path: String?,
    onPathChange: (String?) -> Unit,
    refreshKey: Int,
    selection: Set<String>,
    onEntries: (List<UiItem>) -> Unit,
    onClick: (UiItem, List<UiItem>) -> Unit,
    onLongClick: (UiItem) -> Unit,
) {
    val entries by produceState<List<UiItem>>(emptyList(), path, refreshKey) {
        value = if (path == null) {
            services.ops.roots().map { UiItem(it, it, true, FileCategory.OTHER, 0L, 0L) }
        } else {
            services.ops.listFolder(path).map { it.toUiItem() }
        }
    }
    LaunchedEffect(entries) { onEntries(entries) }

    Column(Modifier.fillMaxWidth()) {
        Text(
            path ?: "Storage",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )
        if (path != null) {
            TextButton(onClick = {
                onPathChange(if (services.ops.roots().contains(path)) null else services.ops.parentOf(path))
            }) { Text("↑ Up") }
        }
        ItemList(
            items = entries,
            services = services,
            selection = selection,
            onClick = { onClick(it, entries) },
            onLongClick = onLongClick,
        )
    }
}

/** Click without the ripple/indication (used for the dismissible status line). */
fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = composed {
    clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
}
