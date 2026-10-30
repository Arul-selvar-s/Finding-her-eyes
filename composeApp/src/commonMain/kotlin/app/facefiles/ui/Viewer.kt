package app.facefiles.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.facefiles.domain.FileCategory

/** Full-screen preview with swipe between items, double-tap / pinch zoom, and "Open with…". */
@Composable
fun ViewerScreen(
    items: List<UiItem>,
    startIndex: Int,
    services: AppServices,
    onClose: () -> Unit,
    extraActions: @Composable (UiItem) -> Unit = {},
) {
    PlatformBackHandler(enabled = true, onBack = onClose)
    if (items.isEmpty()) {
        onClose()
        return
    }
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, items.lastIndex)) { items.size }
    var zoomed by remember { mutableStateOf(false) }
    val current = items[pager.currentPage.coerceIn(0, items.lastIndex)]

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = !zoomed,
            key = { items[it].path },
        ) { page ->
            ViewerPage(items[page], services, onZoom = { zoomed = it })
        }

        Row(
            Modifier.fillMaxWidth().safeDrawingPadding().background(Color(0x66000000)).padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                current.name,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClose) { Text("✕ Close", color = Color.White) }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding().background(Color(0x66000000)).padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            extraActions(current)
            Button(onClick = { services.ops.open(current.path) }) { Text("Open with…") }
        }
    }
}

@Composable
private fun ViewerPage(item: UiItem, services: AppServices, onZoom: (Boolean) -> Unit) {
    val bitmap by produceState<ImageBitmap?>(null, item.path) {
        value = services.thumbnails.loadLarge(item.path, item.category, if (item.category == FileCategory.VIDEO) 1280 else 2048)
    }
    val b = bitmap
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (b == null) {
            Text("Loading…", color = Color.White)
        } else {
            ZoomableBitmap(b, onZoom)
        }
        if (item.category == FileCategory.VIDEO) {
            TextButton(onClick = { services.ops.open(item.path) }) {
                Text("▶", color = Color.White, fontSize = 64.sp)
            }
        }
    }
}

@Composable
private fun ZoomableBitmap(bitmap: ImageBitmap, onZoom: (Boolean) -> Unit) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    scale = if (scale > 1f) 1f else 2.5f
                    offset = Offset.Zero
                    onZoom(scale > 1f)
                })
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        // One finger at 1x is left alone so the pager can swipe.
                        if (event.changes.size >= 2 || scale > 1f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            offset = if (scale <= 1f) Offset.Zero else offset + pan
                            onZoom(scale > 1f)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        )
    }
}
