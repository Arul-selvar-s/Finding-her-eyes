package app.facefiles

import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import app.facefiles.data.buildDatabase
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
            AppServices(
                fileDao = database.fileDao(),
                indexer = FileScanner(database.fileDao(), ::desktopScanRoots, ScanOptions(workers = 8)),
                ops = JvmFileOperations(database.fileDao(), ::desktopBrowseRoots, ::desktopOpen),
                thumbnails = ThumbnailLoader(DesktopMediaDecoder()),
            )
        }
        Window(onCloseRequest = ::exitApplication, title = "FaceFiles") {
            App(services)
        }
    }
}
