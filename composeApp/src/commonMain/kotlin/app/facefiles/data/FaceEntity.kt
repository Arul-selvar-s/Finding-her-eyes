package app.facefiles.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.facefiles.domain.FaceKind
import app.facefiles.domain.FaceSource

/**
 * One detected face. Deleting (or un-indexing) the parent file cascades to its faces.
 * Box coordinates are normalised to 0..1 so they survive resizing.
 */
@Entity(
    tableName = "faces",
    foreignKeys = [
        ForeignKey(
            entity = FileEntity::class,
            parentColumns = ["path"],
            childColumns = ["filePath"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["filePath"])],
)
data class FaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val filePath: String,
    val source: FaceSource,
    /** Video position in ms, or -1 when not a video frame. */
    val frameTimeMs: Long = -1,
    /** PDF page index, or -1 when not from a PDF. */
    val pdfPage: Int = -1,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val kind: FaceKind,
    /** 128 little-endian float32 values = 512 bytes, L2-normalised. */
    val embedding: ByteArray,
    /** Detector confidence 0..1. */
    val score: Float = 1f,
    /** Small JPEG of the aligned face, for thumbnails. */
    val thumb: ByteArray = ByteArray(0),
)

data class FaceVector(
    val id: Long,
    val filePath: String,
    val kind: FaceKind,
    val frameTimeMs: Long,
    val pdfPage: Int,
    val score: Float,
    val embedding: ByteArray,
)

/** Everything the UI needs about one face of one file (no embedding). */
data class FaceInfo(
    val id: Long,
    val source: FaceSource,
    val frameTimeMs: Long,
    val pdfPage: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val score: Float,
    val thumb: ByteArray,
)
