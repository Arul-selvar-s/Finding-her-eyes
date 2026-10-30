package app.facefiles.scan

data class ScanOptions(
    /** Parallel directory walkers. 4 is gentle on phones; desktop SSDs handle 8+. */
    val workers: Int = 4,
    /** Max rows written to SQLite in one transaction. */
    val dbBatchSize: Int = 1_000,
    val includeHidden: Boolean = false,
    val progressIntervalMs: Long = 250,
    /** Lower-case folder names that are never entered. */
    val skipDirectoryNames: Set<String> = setOf(
        "\$recycle.bin",
        "system volume information",
        "lost.dir",
        "node_modules",
    ),
)
