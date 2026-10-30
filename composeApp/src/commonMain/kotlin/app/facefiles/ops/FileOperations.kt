package app.facefiles.ops

data class FolderEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modifiedAt: Long,
)

data class OpResult(
    val done: Int,
    val failures: List<String> = emptyList(),
) {
    val ok: Boolean get() = failures.isEmpty()
    fun summary(past: String, base: String): String = when {
        failures.isEmpty() -> "$past $done item(s)"
        done == 0 -> "Could not $base: ${failures.first()}"
        else -> "$past $done, ${failures.size} failed: ${failures.first()}"
    }
}

interface FileOperations {
    fun roots(): List<String>
    fun parentOf(path: String): String?
    suspend fun listFolder(path: String): List<FolderEntry>
    suspend fun createFolder(parent: String, name: String): OpResult
    suspend fun copy(paths: List<String>, destDir: String): OpResult
    suspend fun move(paths: List<String>, destDir: String): OpResult
    suspend fun rename(path: String, newName: String): OpResult
    suspend fun delete(paths: List<String>): OpResult

    /** Opens with the system's default app. */
    fun open(path: String): Boolean
}
