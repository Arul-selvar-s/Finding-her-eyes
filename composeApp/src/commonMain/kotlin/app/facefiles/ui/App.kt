package app.facefiles.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.facefiles.data.CategoryCount
import app.facefiles.data.FileDao
import app.facefiles.data.FileEntity
import app.facefiles.domain.FileCategory
import app.facefiles.domain.FileIndexer
import app.facefiles.domain.ScanEvent
import kotlinx.coroutines.launch

/** Phase-1 shell: scan button + category tabs fed straight from the Room index. */
@Composable
fun App(indexer: FileIndexer, fileDao: FileDao) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            var selected by remember { mutableStateOf(FileCategory.PHOTO) }
            var lastEvent by remember { mutableStateOf<ScanEvent?>(null) }
            var scanning by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()

            val countsFlow = remember { fileDao.observeCounts() }
            val counts by countsFlow.collectAsState(initial = emptyList<CategoryCount>())

            val filesFlow = remember(selected) { fileDao.observeByCategory(selected, 300) }
            val files by filesFlow.collectAsState(initial = emptyList<FileEntity>())

            Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
                Text("FaceFiles", style = MaterialTheme.typography.headlineMedium)

                Row(
                    modifier = Modifier.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        enabled = !scanning,
                        onClick = {
                            scanning = true
                            scope.launch {
                                try {
                                    indexer.scan().collect { lastEvent = it }
                                } finally {
                                    scanning = false
                                }
                            }
                        },
                    ) { Text(if (scanning) "Scanning…" else "Scan files") }
                    Spacer(Modifier.width(12.dp))
                    Text(describe(lastEvent), style = MaterialTheme.typography.bodySmall)
                }

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FileCategory.entries.forEach { category ->
                        val n = counts.firstOrNull { it.category == category }?.count ?: 0
                        FilterChip(
                            selected = category == selected,
                            onClick = { selected = category },
                            label = { Text("${category.label} ($n)") },
                        )
                    }
                }

                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(files, key = { it.path }) { file ->
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${formatBytes(file.sizeBytes)} · ${file.parentPath}",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun describe(event: ScanEvent?): String = when (event) {
    null -> "Not scanned yet"
    is ScanEvent.Progress -> "${event.filesSeen} files · ${event.directoriesSeen} folders"
    is ScanEvent.Finished -> {
        val s = event.stats
        "Done: ${s.filesSeen} files, ${s.added} new/changed, ${s.removed} removed (${s.durationMs} ms)"
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "${bytes / (1L shl 30)} GB"
    bytes >= 1L shl 20 -> "${bytes / (1L shl 20)} MB"
    bytes >= 1L shl 10 -> "${bytes / (1L shl 10)} KB"
    else -> "$bytes B"
}
