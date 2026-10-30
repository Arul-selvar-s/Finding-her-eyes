package app.facefiles.ui

import app.facefiles.data.FileEntity
import app.facefiles.domain.FileCategory
import app.facefiles.ops.FolderEntry

/** One row/tile in any list: an indexed file or a live folder entry. */
data class UiItem(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val category: FileCategory,
    val sizeBytes: Long,
    val modifiedAt: Long,
) {
    val isMedia: Boolean get() = !isDirectory && (category == FileCategory.PHOTO || category == FileCategory.VIDEO)
}

fun FileEntity.toUiItem() = UiItem(path, name, false, category, sizeBytes, modifiedAt)

fun FolderEntry.toUiItem(): UiItem {
    val category = if (isDirectory) FileCategory.OTHER else FileCategory.fromExtension(name.substringAfterLast('.', "").lowercase())
    return UiItem(path, name, isDirectory, category, sizeBytes, modifiedAt)
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "${bytes / (1L shl 30)} GB"
    bytes >= 1L shl 20 -> "${bytes / (1L shl 20)} MB"
    bytes >= 1L shl 10 -> "${bytes / (1L shl 10)} KB"
    else -> "$bytes B"
}
