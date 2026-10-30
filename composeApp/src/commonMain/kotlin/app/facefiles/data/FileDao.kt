package app.facefiles.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.facefiles.domain.FileCategory
import kotlinx.coroutines.flow.Flow

/** Stay well below SQLite's bound-variable limit. */
private const val SQL_CHUNK = 500

@Dao
abstract class FileDao {

    // ---------- scanner writes ----------

    @Upsert
    abstract suspend fun upsertAll(files: List<FileEntity>)

    @Query("SELECT path, sizeBytes, modifiedAt FROM files WHERE parentPath = :dir")
    abstract suspend fun signaturesIn(dir: String): List<FileSignature>

    /** Marks every row directly inside [dir] as seen by scan [generation]. */
    @Query("UPDATE files SET scanGeneration = :generation WHERE parentPath = :dir")
    abstract suspend fun touchDirectory(dir: String, generation: Long)

    /** Marks everything whose path is in [lower, upper) as seen (used for unreadable folders). */
    @Query("UPDATE files SET scanGeneration = :generation WHERE path >= :lower AND path < :upper")
    abstract suspend fun touchSubtree(lower: String, upper: String, generation: Long)

    /** Deletes rows in [lower, upper) that no scan of [generation] or newer has seen. */
    @Query("DELETE FROM files WHERE scanGeneration < :generation AND path >= :lower AND path < :upper")
    abstract suspend fun deleteStale(lower: String, upper: String, generation: Long): Int

    @Query("DELETE FROM files WHERE path IN (:paths)")
    abstract suspend fun deleteByPaths(paths: List<String>)

    @Query("DELETE FROM faces WHERE filePath IN (:paths)")
    abstract suspend fun deleteFacesFor(paths: List<String>)

    // ---------- file operations (keep the index in sync without a rescan) ----------

    /** Rename/move one file. Faces follow automatically (FK ON UPDATE CASCADE). */
    @Query(
        "UPDATE files SET path = :newPath, name = :newName, parentPath = :newParent, " +
            "extension = :extension, category = :category WHERE path = :oldPath",
    )
    abstract suspend fun relocate(
        oldPath: String,
        newPath: String,
        newName: String,
        newParent: String,
        extension: String,
        category: FileCategory,
    ): Int

    /** Rename/move a whole folder: every path starting with [lower] ("old/") is re-based onto [newDir]. */
    @Query(
        "UPDATE files SET path = :newDir || substr(path, :oldDirLength + 1), " +
            "parentPath = :newDir || substr(parentPath, :oldDirLength + 1) " +
            "WHERE path >= :lower AND path < :upper",
    )
    abstract suspend fun relocateTree(newDir: String, oldDirLength: Int, lower: String, upper: String): Int

    @Query("DELETE FROM files WHERE path >= :lower AND path < :upper")
    abstract suspend fun deleteSubtree(lower: String, upper: String): Int

    /** One atomic write for a whole batch of directories. */
    @Transaction
    open suspend fun applyBatch(
        generation: Long,
        touchedDirectories: List<String>,
        resetFaces: List<String>,
        removed: List<String>,
        upserts: List<FileEntity>,
    ) {
        touchedDirectories.forEach { touchDirectory(it, generation) }
        resetFaces.chunked(SQL_CHUNK).forEach { deleteFacesFor(it) }
        removed.chunked(SQL_CHUNK).forEach { deleteByPaths(it) }
        upserts.chunked(SQL_CHUNK).forEach { upsertAll(it) }
    }

    // ---------- UI reads ----------

    @Query("SELECT * FROM files WHERE category = :category ORDER BY modifiedAt DESC LIMIT :limit")
    abstract fun observeByCategory(category: FileCategory, limit: Int): Flow<List<FileEntity>>

    @Query("SELECT category, COUNT(*) AS count FROM files GROUP BY category")
    abstract fun observeCounts(): Flow<List<CategoryCount>>

    @Query("SELECT * FROM files WHERE parentPath = :dir ORDER BY name COLLATE NOCASE")
    abstract fun observeFolder(dir: String): Flow<List<FileEntity>>
}
