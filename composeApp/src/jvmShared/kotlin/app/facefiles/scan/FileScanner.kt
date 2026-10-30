package app.facefiles.scan

import app.facefiles.data.FileDao
import app.facefiles.data.FileEntity
import app.facefiles.domain.FaceScanState
import app.facefiles.domain.FileCategory
import app.facefiles.domain.FileIndexer
import app.facefiles.domain.ScanEvent
import app.facefiles.domain.ScanStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.TimeSource

/**
 * Incremental, multi-threaded file indexer shared by Android and Windows.
 *
 * Pipeline:
 *
 *   roots ─► dirQueue ─► N walker coroutines ─► deltas ─► 1 writer coroutine ─► Room
 *              ▲               │
 *              └── sub-folders ┘
 *
 *  - Walkers list one directory at a time (a single stat per entry via NIO), diff it
 *    against what Room already knows and push only the *difference* to the writer.
 *  - The writer merges whatever has queued up into one transaction, so batches grow
 *    automatically when the disk is faster than SQLite.
 *  - Unchanged files cost one UPDATE per directory, not one per file.
 *  - Files that disappeared are removed after a *completed* scan; a cancelled or failed
 *    scan never deletes anything. Folders we cannot read keep their old rows.
 *  - Symlinks / junctions are never followed (no cycles).
 *  - Cancel the collector and every worker stops at its next directory.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileScanner(
    private val fileDao: FileDao,
    private val rootsProvider: () -> List<File>,
    private val options: ScanOptions = ScanOptions(),
) : FileIndexer {

    override fun scan(): Flow<ScanEvent> = channelFlow {
        val clock = TimeSource.Monotonic.markNow()
        val generation = System.currentTimeMillis()
        val roots = normalizeRoots(rootsProvider())
        if (roots.isEmpty()) {
            send(ScanEvent.Finished(ScanStats()))
            return@channelFlow
        }

        val counters = Counters()
        val deltas = Channel<DirDelta>(capacity = 256)
        val dirQueue = Channel<File>(Channel.UNLIMITED)
        val pending = AtomicInteger(0)

        fun enqueue(dir: File) {
            pending.incrementAndGet()
            dirQueue.trySend(dir)
        }
        roots.forEach { enqueue(it) }

        val writer = launch(Dispatchers.IO) { writeLoop(deltas, generation, counters) }
        val ticker = launch {
            while (isActive) {
                send(counters.toProgress())
                delay(options.progressIntervalMs)
            }
        }

        val walkerDispatcher = Dispatchers.IO.limitedParallelism(options.workers.coerceAtLeast(1))
        coroutineScope {
            repeat(options.workers.coerceAtLeast(1)) {
                launch(walkerDispatcher) {
                    for (dir in dirQueue) {
                        try {
                            walkDirectory(dir, generation, counters, deltas) { enqueue(it) }
                        } finally {
                            // The last directory in flight closes the queue, ending every worker.
                            if (pending.decrementAndGet() == 0) dirQueue.close()
                        }
                    }
                }
            }
        }
        deltas.close()
        writer.join()

        // Reaching this line means every directory was walked and every batch committed,
        // so anything under a root that was not stamped with this generation is gone.
        var staleRemoved = 0L
        for (root in roots) {
            val lower = directoryPrefix(root)
            staleRemoved += fileDao.deleteStale(lower, upperBound(lower), generation)
        }

        ticker.cancelAndJoin()
        send(ScanEvent.Finished(counters.toStats(staleRemoved, clock.elapsedNow().inWholeMilliseconds)))
    }.conflate()

    // ------------------------------------------------------------------------------------
    // Walker
    // ------------------------------------------------------------------------------------

    private suspend fun walkDirectory(
        dir: File,
        generation: Long,
        counters: Counters,
        deltas: SendChannel<DirDelta>,
        enqueue: (File) -> Unit,
    ) {
        currentCoroutineContext().ensureActive()
        counters.currentDirectory = dir.path
        counters.directories.incrementAndGet()

        val entries = listDirectory(dir)
        if (entries == null) {
            // Deleted while queued -> let stale cleanup drop its rows.
            if (!dir.exists()) return
            // Exists but unreadable (permission, I/O error): keep whatever we indexed before.
            counters.unreadable.incrementAndGet()
            val lower = directoryPrefix(dir)
            fileDao.touchSubtree(lower, upperBound(lower), generation)
            return
        }

        val prefix = directoryPrefix(dir)
        val known = fileDao.signaturesIn(dir.path).associateBy { it.path }
        val present = HashSet<String>(entries.size * 2)
        val upserts = ArrayList<FileEntity>()
        val resetFaces = ArrayList<String>()

        for (entry in entries) {
            if (entry.isDirectory) {
                enqueue(File(dir, entry.name))
                continue
            }
            counters.files.incrementAndGet()
            val path = prefix + entry.name
            present += path

            val signature = known[path]
            if (signature != null &&
                signature.sizeBytes == entry.size &&
                signature.modifiedAt == entry.modifiedMs
            ) {
                counters.unchanged.incrementAndGet()
            } else {
                // Content changed (or brand new): faces found earlier are no longer trustworthy.
                if (signature != null) resetFaces += path
                counters.added.incrementAndGet()
                val extension = entry.name.substringAfterLast('.', "").lowercase()
                val category = FileCategory.fromExtension(extension)
                upserts += FileEntity(
                    path = path,
                    name = entry.name,
                    parentPath = dir.path,
                    extension = extension,
                    sizeBytes = entry.size,
                    modifiedAt = entry.modifiedMs,
                    category = category,
                    scanGeneration = generation,
                    faceScanState = FaceScanState.initialFor(category, extension),
                )
            }
        }

        val removed = if (known.isEmpty()) emptyList() else known.keys.filter { it !in present }
        if (known.isNotEmpty() || upserts.isNotEmpty()) {
            deltas.send(
                DirDelta(
                    touchedDirectory = if (known.isNotEmpty()) dir.path else null,
                    resetFaces = resetFaces,
                    removed = removed,
                    upserts = upserts,
                ),
            )
        }
    }

    /** One blocking listing: names + size + mtime with a single stat per entry. null = unreadable. */
    private fun listDirectory(dir: File): List<Entry>? {
        val entries = ArrayList<Entry>()
        try {
            Files.newDirectoryStream(dir.toPath()).use { stream ->
                for (path in stream) {
                    val name = path.fileName?.toString() ?: continue
                    val attrs = try {
                        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    } catch (e: IOException) {
                        continue
                    }
                    // Symlinks are skipped; Windows junctions report isOther and fall through both branches.
                    if (attrs.isSymbolicLink) continue
                    when {
                        attrs.isDirectory -> {
                            if (shouldDescend(dir, name, path)) entries += Entry(name, true, 0L, 0L)
                        }
                        attrs.isRegularFile -> {
                            if (options.includeHidden || !name.startsWith('.')) {
                                entries += Entry(name, false, attrs.size(), attrs.lastModifiedTime().toMillis())
                            }
                        }
                    }
                }
            }
        } catch (e: IOException) {
            return null
        } catch (e: SecurityException) {
            return null
        } catch (e: DirectoryIteratorException) {
            return null
        }
        return entries
    }

    private fun shouldDescend(parent: File, name: String, path: Path): Boolean {
        if (name.lowercase() in options.skipDirectoryNames) return false
        if (!options.includeHidden && (name.startsWith('.') || isHidden(path))) return false
        // Android app-private areas: inaccessible even with all-files access.
        if (parent.name == "Android" && (name == "data" || name == "obb")) return false
        return true
    }

    private fun isHidden(path: Path): Boolean =
        try {
            Files.isHidden(path)
        } catch (e: IOException) {
            false
        }

    // ------------------------------------------------------------------------------------
    // Writer
    // ------------------------------------------------------------------------------------

    private suspend fun writeLoop(
        deltas: ReceiveChannel<DirDelta>,
        generation: Long,
        counters: Counters,
    ) {
        val batch = Batch()
        while (true) {
            val first = deltas.receiveCatching().getOrNull() ?: break
            batch.add(first)
            // Drain whatever is already waiting so SQLite commits fewer, bigger transactions.
            while (batch.size < options.dbBatchSize) {
                val next = deltas.tryReceive().getOrNull() ?: break
                batch.add(next)
            }
            fileDao.applyBatch(
                generation = generation,
                touchedDirectories = batch.touched,
                resetFaces = batch.resetFaces,
                removed = batch.removed,
                upserts = batch.upserts,
            )
            counters.removed.addAndGet(batch.removed.size.toLong())
            batch.clear()
        }
    }

    // ------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------

    /** Drops missing roots and roots nested inside another root. */
    private fun normalizeRoots(raw: List<File>): List<File> {
        val sorted = raw
            .map { it.absoluteFile }
            .filter { it.isDirectory }
            .distinctBy { it.path }
            .sortedBy { it.path.length }
        val kept = ArrayList<File>()
        for (candidate in sorted) {
            if (kept.none { candidate.path.startsWith(directoryPrefix(it)) }) kept += candidate
        }
        return kept
    }

    private class Entry(
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
        val modifiedMs: Long,
    )

    private class DirDelta(
        val touchedDirectory: String?,
        val resetFaces: List<String>,
        val removed: List<String>,
        val upserts: List<FileEntity>,
    )

    private class Batch {
        val touched = ArrayList<String>()
        val resetFaces = ArrayList<String>()
        val removed = ArrayList<String>()
        val upserts = ArrayList<FileEntity>()

        val size: Int get() = touched.size + removed.size + upserts.size

        fun add(delta: DirDelta) {
            val dir = delta.touchedDirectory
            if (dir != null) touched += dir
            resetFaces += delta.resetFaces
            removed += delta.removed
            upserts += delta.upserts
        }

        fun clear() {
            touched.clear()
            resetFaces.clear()
            removed.clear()
            upserts.clear()
        }
    }

    private class Counters {
        val files = AtomicLong()
        val directories = AtomicLong()
        val added = AtomicLong()
        val unchanged = AtomicLong()
        val removed = AtomicLong()
        val unreadable = AtomicLong()
        @Volatile var currentDirectory: String = ""

        fun toProgress() = ScanEvent.Progress(
            filesSeen = files.get(),
            directoriesSeen = directories.get(),
            newOrChanged = added.get(),
            currentDirectory = currentDirectory,
        )

        fun toStats(staleRemoved: Long, durationMs: Long) = ScanStats(
            filesSeen = files.get(),
            directoriesSeen = directories.get(),
            added = added.get(),
            unchanged = unchanged.get(),
            removed = removed.get() + staleRemoved,
            unreadableDirectories = unreadable.get(),
            durationMs = durationMs,
        )
    }
}

/** "/storage/emulated/0/DCIM" -> "/storage/emulated/0/DCIM/" ; "C:\" stays "C:\". */
internal fun directoryPrefix(dir: File): String {
    val p = dir.path
    return if (p.endsWith(File.separatorChar)) p else p + File.separatorChar
}

/** Smallest string greater than every path that starts with [prefix] (which ends in a separator). */
internal fun upperBound(prefix: String): String = prefix.dropLast(1) + (prefix.last() + 1)
