package app.facefiles.domain

import kotlinx.coroutines.flow.Flow

sealed interface ScanEvent {
    /** Emitted a few times per second while a scan runs. */
    data class Progress(
        val filesSeen: Long,
        val directoriesSeen: Long,
        val newOrChanged: Long,
        val currentDirectory: String,
    ) : ScanEvent

    /** Always the last event of a scan that was not cancelled. */
    data class Finished(val stats: ScanStats) : ScanEvent
}

data class ScanStats(
    val filesSeen: Long = 0,
    val directoriesSeen: Long = 0,
    val added: Long = 0,
    val unchanged: Long = 0,
    val removed: Long = 0,
    val unreadableDirectories: Long = 0,
    val durationMs: Long = 0,
)

/** What the shared UI sees; the implementation lives in the JVM-shared source set. */
interface FileIndexer {
    fun scan(): Flow<ScanEvent>
}
