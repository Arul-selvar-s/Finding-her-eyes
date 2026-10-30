package app.facefiles.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.window.Dialog
import app.facefiles.ops.FolderEntry
import kotlinx.coroutines.launch

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun RenameDialog(
    currentName: String,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Rename",
    confirmLabel: String = "Rename",
) {
    var name by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onRename(name.trim()) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** In-app folder chooser used by Copy / Move. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderPickerDialog(
    services: AppServices,
    title: String,
    confirmLabel: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var current by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var newFolderDialog by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val folders by produceState<List<FolderEntry>>(emptyList(), current, refresh) {
        val here = current
        value = if (here == null) {
            services.ops.roots().map { FolderEntry(it, it, true, 0L, 0L) }
        } else {
            services.ops.listFolder(here).filter { it.isDirectory }
        }
    }
    PlatformBackHandler(enabled = current != null) {
        val here = current
        current = if (here == null || services.ops.roots().contains(here)) null else services.ops.parentOf(here)
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
            Column(Modifier.padding(16.dp).fillMaxWidth()) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    current ?: "Choose a storage location",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        enabled = current != null,
                        onClick = {
                            val here = current
                            current = if (here == null || services.ops.roots().contains(here)) null else services.ops.parentOf(here)
                        },
                    ) { Text("↑ Up") }
                    TextButton(enabled = current != null, onClick = { newFolderDialog = true }) { Text("+ New folder") }
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                LazyColumn(Modifier.heightIn(min = 120.dp, max = 360.dp)) {
                    items(folders, key = { it.path }) { folder ->
                        Row(
                            Modifier.fillMaxWidth().clickable { current = folder.path }.padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("📁  ")
                            Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(enabled = current != null, onClick = { current?.let(onPick) }) { Text(confirmLabel) }
                }
            }
        }
    }

    if (newFolderDialog) {
        RenameDialog(
            currentName = "New folder",
            title = "New folder",
            confirmLabel = "Create",
            onDismiss = { newFolderDialog = false },
            onRename = { name ->
                newFolderDialog = false
                val here = current ?: return@RenameDialog
                scope.launch {
                    val r = services.ops.createFolder(here, name)
                    message = if (r.ok) null else r.failures.first()
                    refresh++
                }
            },
        )
    }
}
