package app.facefiles.platform

import androidx.room.Room
import androidx.room.RoomDatabase
import app.facefiles.data.AppDatabase
import java.awt.Desktop
import java.io.File

fun desktopDatabaseBuilder(): RoomDatabase.Builder<AppDatabase> {
    val base = System.getenv("APPDATA") ?: System.getProperty("user.home")
    val dir = File(base, "FaceFiles").apply { mkdirs() }
    return Room.databaseBuilder<AppDatabase>(name = File(dir, "facefiles.db").absolutePath)
}

/**
 * The user's own folders (including OneDrive-redirected ones) plus any other drive.
 * C:\Windows, Program Files and AppData are deliberately never entered.
 */
fun desktopScanRoots(): List<File> {
    val home = File(System.getProperty("user.home"))
    val bases = listOf(home) + home.listFiles { f -> f.isDirectory && f.name.startsWith("OneDrive") }.orEmpty()
    val folderNames = listOf("Desktop", "Documents", "Downloads", "Pictures", "Videos", "Music")
    val userFolders = bases.flatMap { base -> folderNames.map { File(base, it) } }.filter { it.isDirectory }

    val homeDrive = home.toPath().root?.toString()
    val otherDrives = File.listRoots().filter { it.path != homeDrive && it.isDirectory }

    return userFolders + otherDrives
}

/** Where the folder browser / folder picker starts: the user profile plus every drive. */
fun desktopBrowseRoots(): List<File> {
    val home = File(System.getProperty("user.home"))
    return (listOf(home) + File.listRoots().filter { it.isDirectory }).distinctBy { it.path }
}

/** Opens with the Windows default app for the file type. */
fun desktopOpen(file: File): Boolean = try {
    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
        Desktop.getDesktop().open(file)
        true
    } else {
        false
    }
} catch (e: Exception) {
    false
}
