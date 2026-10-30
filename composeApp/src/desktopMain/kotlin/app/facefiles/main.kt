package app.facefiles

import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import app.facefiles.data.buildDatabase
import app.facefiles.platform.desktopDatabaseBuilder
import app.facefiles.platform.desktopScanRoots
import app.facefiles.scan.FileScanner
import app.facefiles.scan.ScanOptions
import app.facefiles.ui.App

fun main() = application {
    val database = remember { buildDatabase(desktopDatabaseBuilder()) }
    val scanner = remember {
        FileScanner(
            fileDao = database.fileDao(),
            rootsProvider = ::desktopScanRoots,
            options = ScanOptions(workers = 8),
        )
    }
    Window(onCloseRequest = ::exitApplication, title = "FaceFiles") {
        App(indexer = scanner, fileDao = database.fileDao())
    }
}
