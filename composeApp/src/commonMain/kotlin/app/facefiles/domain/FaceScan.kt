package app.facefiles.domain

import kotlinx.coroutines.flow.StateFlow

data class FaceScanProgress(
    val running: Boolean = false,
    val processed: Int = 0,
    val total: Int = 0,
    val facesFound: Int = 0,
    val currentFile: String = "",
    val error: String? = null,
    val finished: Boolean = false,
)

/** Start/stop the background face-finding pass over every not-yet-scanned photo, video and PDF. */
interface FaceScanControl {
    val progress: StateFlow<FaceScanProgress>
    fun start()
    fun stop()
}
