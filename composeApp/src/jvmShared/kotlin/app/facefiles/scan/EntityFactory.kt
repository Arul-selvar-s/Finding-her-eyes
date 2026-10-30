package app.facefiles.scan

import app.facefiles.data.FileEntity
import app.facefiles.domain.FaceScanState
import app.facefiles.domain.FileCategory

internal fun newFileEntity(
    path: String,
    name: String,
    parentPath: String,
    sizeBytes: Long,
    modifiedAt: Long,
    generation: Long,
): FileEntity {
    val extension = name.substringAfterLast('.', "").lowercase()
    val category = FileCategory.fromExtension(extension)
    return FileEntity(
        path = path,
        name = name,
        parentPath = parentPath,
        extension = extension,
        sizeBytes = sizeBytes,
        modifiedAt = modifiedAt,
        category = category,
        scanGeneration = generation,
        faceScanState = FaceScanState.initialFor(category, extension),
    )
}
