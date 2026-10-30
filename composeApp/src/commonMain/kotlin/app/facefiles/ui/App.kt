package app.facefiles.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.facefiles.data.CategoryCount
import app.facefiles.data.FileEntity
import app.facefiles.domain.FileCategory
import app.facefiles.domain.ScanEvent
import app.facefiles.ops.OpResult
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@Composable
fun App(services: AppServices) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            AppRoot(services)
        }
    }
}

private enum class Tab(val label: String, val category: FileCategory?) {
    PHOTOS("Photos", FileCategory.PHOTO),
    VIDEOS("Videos", FileCategory.VIDEO),
    AUDIO("Audio", FileCategory.AUDIO),
    DOCUMENTS("Documents", FileCategory.DOCUMENT),
    FOLDERS("All folders", null),
}

private enum class Pending { COPY, MOVE, DELETE, RENAME }

private class ViewerRequest(val items: List<UiItem>, val index: Int)

@Composable
private fun AppRoot(services: AppServices) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(Tab.PHOTOS) }
    var selection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pending by remember { mutableStateOf<Pending?>(null) }
    var viewer by remember { mutableStateOf<ViewerRequest?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableStateOf(0) }
    var folderPath by remember { mutableStateOf<String?>(null) }
    var folderItems by remember { mutableStateOf<List<UiItem>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var lastEvent by remember { mutableStateOf<ScanEvent?>(null) }

    val countsFlow = remember { services.fileDao.observeCounts() }
    val counts by countsFlow.collectAsState(initial = emptyList<CategoryCount>())

    val filesFlow = remember(tab) {
        tab.category?.let { services.fileDao.observeByCategory(it, Int.MAX_VALUE) } ?: flowOf(emptyList<FileEntity>())
    }
    val files by filesFlow.collectAsState(initial = emptyList<FileEntity>())
    val items = remember(files) { files.map { it.toUiItem() } }
    val visible = if (tab == Tab.FOLDERS) folderItems else items

    fun toggle(path: String) {
        selection = if (path in selection) selection - path else selection + path
    }

    fun runOp(past: String, base: String, block: suspend () -> OpResult) {
        scope.launch {
            busy = true
            val result = try {
                block()
            } catch (e: Exception) {
                OpResult(0, listOf(e.message ?: e.toString()))
            }
            status = result.summary(past, base)
            busy = false
            selection = emptySet()
            refreshKey++
        }
    }

    fun onItemClick(item: UiItem, siblings: List<UiItem>) {
        if (selection.isNotEmpty()) {
            toggle(item.path)
            return
        }
        when {
            item.isDirectory -> folderPath = item.path
            item.isMedia -> {
                val media = siblings.filter { it.isMedia }
                viewer = ViewerRequest(media, media.indexOfFirst { it.path == item.path }.coerceAtLeast(0))
            }
            else -> if (!services.ops.open(item.path)) status = "No app found to open ${item.name}"
        }
    }

    PlatformBackHandler(enabled = viewer == null && selection.isNotEmpty()) { selection = emptySet() }
    PlatformBackHandler(enabled = viewer == null && selection.isEmpty() && tab == Tab.FOLDERS && folderPath != null) {
        val here = folderPath
        folderPath = if (here == null || services.ops.roots().contains(here)) null else services.ops.parentOf(here)
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            if (selection.isEmpty()) {
                Header(
                    scanning = scanning,
                    lastEvent = lastEvent,
                    onScan = {
                        scanning = true
                        scope.launch {
                            try {
                                services.indexer.scan().collect { lastEvent = it }
                            } finally {
                                scanning = false
                                refreshKey++
                            }
                        }
                    },
                )
            } else {
                SelectionBar(
                    count = selection.size,
                    canRename = selection.size == 1,
                    onCopy = { pending = Pending.COPY },
                    onMove = { pending = Pending.MOVE },
                    onRename = { pending = Pending.RENAME },
                    onDelete = { pending = Pending.DELETE },
                    onSelectAll = { selection = visible.map { it.path }.toSet() },
                    onClear = { selection = emptySet() },
                )
            }

            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            status?.let { message ->
                Text(
                    "$message  (tap to dismiss)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickableNoRipple { status = null }
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Tab.entries.forEach { t ->
                    val n = t.category?.let { c -> counts.firstOrNull { it.category == c }?.count } ?: 0
                    FilterChip(
                        selected = t == tab,
                        onClick = {
                            tab = t
                            selection = emptySet()
                        },
                        label = { Text(if (t.category == null) t.label else "${t.label} ($n)") },
                    )
                }
            }

            Box(Modifier.weight(1f)) {
                when (tab) {
                    Tab.PHOTOS, Tab.VIDEOS -> ItemGrid(
                        items = items,
                        services = services,
                        selection = selection,
                        onClick = { onItemClick(it, items) },
                        onLongClick = { toggle(it.path) },
                    )
                    Tab.AUDIO, Tab.DOCUMENTS -> ItemList(
                        items = items,
                        services = services,
                        selection = selection,
                        onClick = { onItemClick(it, items) },
                        onLongClick = { toggle(it.path) },
                    )
                    Tab.FOLDERS -> FolderBrowser(
                        services = services,
                        path = folderPath,
                        onPathChange = { folderPath = it },
                        refreshKey = refreshKey,
                        selection = selection,
                        onEntries = { folderItems = it },
                        onClick = { item, siblings -> onItemClick(item, siblings) },
                        onLongClick = { toggle(it.path) },
                    )
                }
            }
        }

        viewer?.let { request ->
            ViewerScreen(
                items = request.items,
                startIndex = request.index,
                services = services,
                onClose = { viewer = null },
            )
        }
    }

    when (pending) {
        Pending.COPY, Pending.MOVE -> {
            val copying = pending == Pending.COPY
            FolderPickerDialog(
                services = services,
                title = if (copying) "Copy ${selection.size} item(s) to…" else "Move ${selection.size} item(s) to…",
                confirmLabel = if (copying) "Copy here" else "Move here",
                onDismiss = { pending = null },
                onPick = { dest ->
                    pending = null
                    val paths = selection.toList()
                    if (copying) runOp("Copied", "copy") { services.ops.copy(paths, dest) }
                    else runOp("Moved", "move") { services.ops.move(paths, dest) }
                },
            )
        }
        Pending.DELETE -> ConfirmDialog(
            title = "Delete ${selection.size} item(s)?",
            text = "They will be permanently removed from your device.",
            confirmLabel = "Delete",
            onDismiss = { pending = null },
            onConfirm = {
                pending = null
                val paths = selection.toList()
                runOp("Deleted", "delete") { services.ops.delete(paths) }
            },
        )
        Pending.RENAME -> {
            val target = visible.firstOrNull { it.path in selection }
            if (target == null) {
                pending = null
            } else {
                RenameDialog(
                    currentName = target.name,
                    onDismiss = { pending = null },
                    onRename = { newName ->
                        pending = null
                        runOp("Renamed", "rename") { services.ops.rename(target.path, newName) }
                    },
                )
            }
        }
        null -> Unit
    }
}

@Composable
private fun Header(scanning: Boolean, lastEvent: ScanEvent?, onScan: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("FaceFiles", style = MaterialTheme.typography.headlineSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(enabled = !scanning, onClick = onScan) { Text(if (scanning) "Scanning…" else "Scan files") }
            Spacer(Modifier.width(12.dp))
            Text(
                describe(lastEvent),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    canRename: Boolean,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClear) { Text("✕") }
            Text("$count selected", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onSelectAll) { Text("Select all") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = onCopy) { Text("Copy") }
            Button(onClick = onMove) { Text("Move") }
            Button(enabled = canRename, onClick = onRename) { Text("Rename") }
            Button(onClick = onDelete) { Text("Delete") }
        }
    }
}

private fun describe(event: ScanEvent?): String = when (event) {
    null -> "Tap Scan files to index your storage"
    is ScanEvent.Progress -> "${event.filesSeen} files · ${event.directoriesSeen} folders"
    is ScanEvent.Finished -> {
        val s = event.stats
        "Done: ${s.filesSeen} files, ${s.added} new/changed, ${s.removed} removed (${s.durationMs} ms)"
    }
}
