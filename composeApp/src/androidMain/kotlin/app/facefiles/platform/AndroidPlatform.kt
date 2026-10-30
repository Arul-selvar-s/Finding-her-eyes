package app.facefiles.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
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

/** Opens [file] with the user's default app for its type (images, video, PDF, ...). */
fun openWithSystem(context: Context, file: File): Boolean = try {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
    true
} catch (e: ActivityNotFoundException) {
    false
} catch (e: Exception) {
    false
}

/** Tells the gallery/MediaStore about files we created, moved or deleted. */
fun refreshMediaStore(context: Context, paths: List<String>) {
    if (paths.isEmpty()) return
    try {
        MediaScannerConnection.scanFile(context.applicationContext, paths.toTypedArray(), null, null)
    } catch (e: Exception) {
        // best effort
    }
}
