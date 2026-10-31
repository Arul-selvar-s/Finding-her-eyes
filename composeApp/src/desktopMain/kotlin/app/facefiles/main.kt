package app.facefiles

import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import app.facefiles.data.buildDatabase
import app.facefiles.face.FaceEngine
import app.facefiles.face.FaceScanOptions
import app.facefiles.face.FaceScanner
import app.facefiles.face.FaceSearch
import app.facefiles.face.ModelSource
import app.facefiles.media.ThumbnailLoader
import app.facefiles.ops.JvmFileOperations
import app.facefiles.platform.DesktopMediaDecoder
import app.facefiles.platform.desktopBrowseRoots
import app.facefiles.platform.desktopDatabaseBuilder
import app.facefiles.platform.desktopOpen
import app.facefiles.platform.desktopScanRoots
import app.facefiles.scan.FileScanner
import app.facefiles.scan.ScanOptions
import app.facefiles.ui.App
import app.facefiles.ui.AppServices
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--selftest") exitProcess(runSelfTest(args))

    application {
        val services = remember {
            val database = buildDatabase(desktopDatabaseBuilder())
            val decoder = DesktopMediaDecoder()
            val engine = lazy {
                FaceEngine(ModelSource { name ->
                    FaceEngine::class.java.getResourceAsStream("/models/$name") ?: error("model $name is not bundled")
                }, intraThreads = 4)
            }
            AppServices(
                fileDao = database.fileDao(),
                indexer = FileScanner(database.fileDao(), ::desktopScanRoots, ScanOptions(workers = 8)),
                ops = JvmFileOperations(database.fileDao(), ::desktopBrowseRoots, ::desktopOpen),
                thumbnails = ThumbnailLoader(decoder),
                faceScan = FaceScanner(database.fileDao(), decoder, { engine.value }, FaceScanOptions(workers = 3)),
                faceSearch = FaceSearch(database.faceDao(), database.fileDao()),
            )
        }
        Window(onCloseRequest = ::exitApplication, title = "FaceFiles") {
            App(services)
        }
    }
}
