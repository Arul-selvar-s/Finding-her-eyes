package app.facefiles.platform

import android.content.Context
import android.os.Environment
import androidx.room.Room
import androidx.room.RoomDatabase
import app.facefiles.data.AppDatabase
import java.io.File

fun androidDatabaseBuilder(context: Context): RoomDatabase.Builder<AppDatabase> {
    val appContext = context.applicationContext
    val dbFile = appContext.getDatabasePath("facefiles.db")
    return Room.databaseBuilder<AppDatabase>(
        context = appContext,
        name = dbFile.absolutePath,
    )
}

/** Internal storage + every mounted SD card / USB volume. */
fun androidScanRoots(context: Context): List<File> {
    val primary = Environment.getExternalStorageDirectory()
    val extraVolumes = context.getExternalFilesDirs(null)
        .filterNotNull()
        .mapNotNull { appDir ->
            // /storage/ABCD-1234/Android/data/<pkg>/files  ->  /storage/ABCD-1234
            val cut = appDir.absolutePath.indexOf("/Android/")
            if (cut > 0) File(appDir.absolutePath.substring(0, cut)) else null
        }
    return (listOf(primary) + extraVolumes).distinctBy { it.absolutePath }.filter { it.exists() }
}
