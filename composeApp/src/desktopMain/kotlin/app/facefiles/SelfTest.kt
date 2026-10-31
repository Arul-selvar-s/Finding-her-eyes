package app.facefiles

import ai.onnxruntime.OrtEnvironment
import androidx.room.Room
import app.facefiles.data.AppDatabase
import app.facefiles.data.buildDatabase
import app.facefiles.domain.ScanEvent
import app.facefiles.domain.ScanStats
import app.facefiles.face.FaceEngine
import app.facefiles.face.ModelSource
import app.facefiles.platform.DesktopMediaDecoder
import app.facefiles.scan.FileScanner
import app.facefiles.scan.ScanOptions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Headless check used by CI against the *packaged* app (bundled JRE, jlink modules, native libs):
 *   FaceFiles.exe --selftest <folder-to-scan> <result-file>
 * A packaged GUI exe has no console, so the verdict goes into a file.
 */
internal fun runSelfTest(args: Array<String>): Int {
    val scanDir = File(args.getOrElse(1) { "." })
    val outFile = File(args.getOrElse(2) { "selftest.txt" })
    val report = StringBuilder()
    return try {
        OrtEnvironment.getEnvironment() // throws if the ONNX native library cannot load
        report.appendLine("onnxruntime: native library loaded")

        // Optional 4th argument: a portrait. Runs the REAL bundled models from the packaged app.
        args.getOrNull(3)?.let { faceImage ->
            val engine = FaceEngine(ModelSource { name ->
                FaceEngine::class.java.getResourceAsStream("/models/$name") ?: error("model $name is not bundled")
            })
            engine.use {
                val image = checkNotNull(DesktopMediaDecoder().decodeImage(faceImage, 1280)) { "cannot decode $faceImage" }
                val faces = it.detect(image)
                report.appendLine("faces detected in sample portrait: ${faces.size}")
                check(faces.isNotEmpty()) { "no face detected in the sample portrait" }
                val embedding = it.embed(it.alignedCrop(image, faces.first()))
                report.appendLine("embedding length: ${embedding.size}")
                check(embedding.size == 128) { "embedding is not 128-d" }
            }
        }

        val dbFile = File.createTempFile("facefiles-selftest", ".db")
        val db = buildDatabase(Room.databaseBuilder<AppDatabase>(name = dbFile.absolutePath))
        try {
            val scanner = FileScanner(db.fileDao(), { listOf(scanDir) }, ScanOptions(workers = 4))
            val first = runBlocking { scanner.scan().toList().filterIsInstance<ScanEvent.Finished>().single().stats }
            report.appendLine("scan:   $first")
            val counts = runBlocking { db.fileDao().observeCounts().first() }
            report.appendLine("counts: $counts")
            val second: ScanStats = runBlocking { scanner.scan().toList().filterIsInstance<ScanEvent.Finished>().single().stats }
            report.appendLine("rescan: $second")
            check(first.filesSeen > 0) { "scan found no files in $scanDir" }
            check(second.added == 0L && second.removed == 0L) { "rescan of unchanged folder was not a no-op" }
        } finally {
            db.close()
            dbFile.delete()
        }
        report.appendLine("RESULT: OK")
        outFile.writeText(report.toString())
        0
    } catch (t: Throwable) {
        report.appendLine("RESULT: FAILED")
        report.appendLine(t.stackTraceToString())
        outFile.writeText(report.toString())
        1
    }
}
