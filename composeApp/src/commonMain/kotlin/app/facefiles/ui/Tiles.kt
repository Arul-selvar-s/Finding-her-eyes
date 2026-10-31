package app.facefiles.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.facefiles.domain.FileCategory

/** Async preview of a photo / video frame / first PDF page. */
@Composable
fun Thumbnail(
    path: String,
    category: FileCategory,
    services: AppServices,
    modifier: Modifier = Modifier,
    sizePx: Int = 320,
    timeMs: Long = -1L,
    contentScale: ContentScale = ContentScale.Crop,
    contentDescription: String? = null,
) {
    val bitmap by produceState<ImageBitmap?>(null, path, sizePx, timeMs) {
        value = services.thumbnails.load(path, category, sizePx, timeMs)
    }
    val b = bitmap
    if (b != null) {
        Image(bitmap = b, contentDescription = contentDescription, modifier = modifier, contentScale = contentScale)
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

/** Square tile for the photo/video grid and the folder grid. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ItemTile(
    item: UiItem,
    services: AppServices,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        when {
            item.isDirectory -> Column(Modifier.align(Alignment.Center).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("📁", fontSize = 36.sp)
                Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
            item.isMedia -> {
                Thumbnail(item.path, item.category, services, Modifier.fillMaxSize(), contentDescription = item.name)
                if (item.category == FileCategory.VIDEO) {
                    Text(
                        "▶",
                        color = Color.White,
                        fontSize = 20.sp,
                        modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
                    )
                }
            }
            else -> Column(Modifier.align(Alignment.Center).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(item.name.substringAfterLast('.', "file").uppercase(), fontWeight = FontWeight.Bold)
                Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (selected) SelectedOverlay()
    }
}

@Composable
private fun SelectedOverlay() {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)))
    Box(Modifier.fillMaxSize().padding(6.dp)) {
        Text(
            "✓",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            modifier = Modifier.align(Alignment.TopEnd).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)).padding(horizontal = 6.dp),
        )
    }
}

/** Thumbnail grid (photos, videos, folder contents). */
@Composable
fun ItemGrid(
    items: List<UiItem>,
    services: AppServices,
    selection: Set<String>,
    onClick: (UiItem) -> Unit,
    onLongClick: (UiItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(columns = GridCells.Adaptive(104.dp), modifier = modifier.fillMaxSize()) {
        items(items, key = { it.path }) { item ->
            ItemTile(item, services, item.path in selection, { onClick(item) }, { onLongClick(item) })
        }
    }
}

/** One-line rows (audio, documents, folder listing). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ItemList(
    items: List<UiItem>,
    services: AppServices,
    selection: Set<String>,
    onClick: (UiItem) -> Unit,
    onLongClick: (UiItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxSize()) {
        items(items, key = { it.path }) { item ->
            val selected = item.path in selection
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent)
                    .combinedClickable(onClick = { onClick(item) }, onLongClick = { onLongClick(item) })
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                    when {
                        item.isDirectory -> Text("📁", fontSize = 28.sp)
                        item.isMedia || item.path.endsWith(".pdf", ignoreCase = true) ->
                            Thumbnail(item.path, item.category, services, Modifier.fillMaxSize(), sizePx = 128)
                        item.category == FileCategory.AUDIO -> Text("🎵", fontSize = 26.sp)
                        else -> Text(item.name.substringAfterLast('.', "").uppercase().take(4), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (item.isDirectory) "Folder" else "${formatBytes(item.sizeBytes)} · ${item.path.substringBeforeLast('/', item.path.substringBeforeLast('\\'))}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (selected) Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }
    }
}
