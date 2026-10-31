package app.facefiles.ui

import app.facefiles.data.FileDao
import app.facefiles.domain.FaceScanControl
import app.facefiles.domain.FileIndexer
import app.facefiles.face.FaceSearch
import app.facefiles.media.ThumbnailProvider
import app.facefiles.ops.FileOperations

/** Everything the screens need, wired up per platform in MainActivity / main.kt. */
class AppServices(
    val fileDao: FileDao,
    val indexer: FileIndexer,
    val ops: FileOperations,
    val thumbnails: ThumbnailProvider,
    val faceScan: FaceScanControl,
    val faceSearch: FaceSearch,
)
