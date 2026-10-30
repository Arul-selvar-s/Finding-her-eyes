package app.facefiles.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.facefiles.domain.FaceScanState
import app.facefiles.domain.FileCategory

@Entity(
    tableName = "files",
    indices = [
        Index(value = ["parentPath"]),
        Index(value = ["category", "modifiedAt"]),
    ],
)
data class FileEntity(
    @PrimaryKey val path: String,
    val name: String,
    val parentPath: String,
    val extension: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val category: FileCategory,
    /** Stamp of the last scan that saw this file; rows with an older stamp are stale. */
    val scanGeneration: Long,
    val faceScanState: FaceScanState,
)

/** Lightweight projection used for change detection during a scan. */
data class FileSignature(
    val path: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
)

data class CategoryCount(
    val category: FileCategory,
    val count: Int,
)
