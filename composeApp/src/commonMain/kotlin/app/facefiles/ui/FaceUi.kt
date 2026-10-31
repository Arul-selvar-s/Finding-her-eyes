package app.facefiles.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.facefiles.data.FaceInfo
import app.facefiles.domain.FaceScanProgress
import app.facefiles.domain.FaceScanState
import app.facefiles.domain.FaceSource
import app.facefiles.face.Person

/** A search for one person: the face embedding to compare everything against. */
class PersonQuery(val title: String, val vector: FloatArray)

@Composable
fun FaceCircle(
    key: String,
    loadBytes: suspend () -> ByteArray?,
    services: AppServices,
    size: Dp,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    val bitmap by produceState<ImageBitmap?>(null, key) {
        val bytes = loadBytes()
        value = if (bytes == null || bytes.isEmpty()) null else services.thumbnails.fromJpeg(key, bytes)
    }
    val b = bitmap
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (b != null) {
            Image(b, contentDescription = description, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

private fun formatTime(ms: Long): String {
    val s = ms / 1000
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

/** Viewer footer: the faces found in the current photo/video/PDF; tap one to find that person everywhere. */
@Composable
fun FacesStrip(item: UiItem, services: AppServices, tick: Int, onFaceClick: (Long) -> Unit) {
    val faces by produceState<List<FaceInfo>?>(null, item.path, tick) { value = services.faceSearch.facesOfFile(item.path) }
    val state by produceState<FaceScanState?>(null, item.path, tick) { value = services.fileDao.faceStateOf(item.path) }
    val list = faces
    when {
        list == null -> Text("…", color = Color.White)
        list.isNotEmpty() -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Tap a face to find this person in all your files",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                list.forEach { face ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FaceCircle(
                            key = "face${face.id}",
                            loadBytes = { face.thumb },
                            services = services,
                            size = 60.dp,
                            modifier = Modifier.clickable { onFaceClick(face.id) },
                            description = "Face",
                        )
                        val label = when {
                            face.source == FaceSource.VIDEO_FRAME -> formatTime(face.frameTimeMs)
                            face.source == FaceSource.PDF_IMAGE -> "p.${face.pdfPage + 1}"
                            else -> ""
                        }
                        if (label.isNotEmpty()) Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        state == FaceScanState.PENDING -> Text("Faces not scanned yet - tap “Find faces” on the main screen", color = Color.White, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        state == FaceScanState.FAILED -> Text("Could not read this file for faces", color = Color.White, style = MaterialTheme.typography.bodySmall)
        else -> Text("No faces found", color = Color.White, style = MaterialTheme.typography.bodySmall)
    }
}

/** Main-screen controls for the background face pass. */
@Composable
fun FaceScanBar(progress: FaceScanProgress, onStart: () -> Unit, onStop: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(enabled = !progress.running, onClick = onStart) { Text(if (progress.running) "Finding faces…" else "Find faces") }
        if (progress.running) TextButton(onClick = onStop) { Text("Stop") }
        Spacer(Modifier.width(8.dp))
        val text = when {
            progress.error != null -> progress.error
            progress.running -> "${progress.processed}/${progress.total} files · ${progress.facesFound} faces"
            progress.finished -> "Faces: done · ${progress.facesFound} found"
            else -> "Scan faces to search by person"
        }
        Text(text ?: "", style = MaterialTheme.typography.bodySmall, maxLines = 2)
    }
}

/** "People" tab: faces grouped by who they look like. */
@Composable
fun PeopleScreen(services: AppServices, refreshKey: Int, scanRunning: Boolean, onPerson: (Person) -> Unit) {
    val people by produceState<List<Person>?>(null, refreshKey, scanRunning) { value = services.faceSearch.people() }
    val list = people
    when {
        list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Grouping faces…") }
        list.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                "No people yet. Run “Find faces”; people who appear in two or more items show up here.",
                textAlign = TextAlign.Center,
            )
        }
        else -> LazyVerticalGrid(columns = GridCells.Adaptive(110.dp), modifier = Modifier.fillMaxSize()) {
            items(list, key = { it.id }) { person ->
                Column(
                    Modifier.padding(8.dp).clickable { onPerson(person) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    FaceCircle(
                        key = "face${person.coverFaceId}",
                        loadBytes = { services.faceSearch.thumbOf(person.coverFaceId) },
                        services = services,
                        size = 88.dp,
                        description = "Person",
                    )
                    Text("${person.fileCount} item(s)", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
