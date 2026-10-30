package app.facefiles

import androidx.room.Room
import app.facefiles.data.AppDatabase
import app.facefiles.data.FaceEntity
import app.facefiles.data.buildDatabase
import app.facefiles.domain.FaceKind
import app.facefiles.domain.FaceScanState
import app.facefiles.domain.FaceSource
import app.facefiles.domain.FileCategory
import app.facefiles.domain.ScanEvent
import app.facefiles.domain.ScanStats
import app.facefiles.scan.FileScanner
import app.facefiles.scan.ScanOptions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileScannerTest {
    private lateinit var base: File
    private lateinit var root: File
    private lateinit var db: AppDatabase
    private lateinit var scanner: FileScanner

    @BeforeTest
    fun setUp() {
        base = Files.createTempDirectory("facefiles-test").toFile()
        root = File(base, "data").apply { mkdirs() }
        db = buildDatabase(Room.databaseBuilder<AppDatabase>(name = File(base, "test.db").absolutePath))
        scanner = FileScanner(db.fileDao(), { listOf(root) }, ScanOptions(workers = 4))
    }

    @AfterTest
    fun tearDown() {
        db.close()
        base.walkTopDown().forEach { it.setReadable(true); it.setExecutable(true) }
        base.deleteRecursively()
    }

    // ---- helpers ----
    private fun file(rel: String, bytes: Int = 16): File =
        File(root, rel).also { it.parentFile.mkdirs(); it.writeBytes(ByteArray(bytes) { 1 }) }

    private fun scan(): ScanStats = runBlocking {
        scanner.scan().toList().filterIsInstance<ScanEvent.Finished>().single().stats
    }

    private fun all(category: FileCategory) =
        runBlocking { db.fileDao().observeByCategory(category, 100_000).first() }

    private fun total(): Int = runBlocking { db.fileDao().observeCounts().first().sumOf { it.count } }

    private fun faces(): Int = runBlocking { db.faceDao().observeFaceCount().first() }

    private fun addFace(path: String) = runBlocking {
        db.faceDao().insertAll(
            listOf(
                FaceEntity(
                    filePath = path, source = FaceSource.IMAGE,
                    left = 0f, top = 0f, right = 1f, bottom = 1f,
                    kind = FaceKind.HUMAN, embedding = ByteArray(512),
                ),
            ),
        )
    }

    // ---- tests ----

    @Test
    fun indexes_and_categorizes_and_skips_hidden() {
        file("a.jpg"); file("b.MP4"); file("c.mp3"); file("d.pdf"); file("e.xlsx"); file("f.bin")
        file("sub/deeper/g.png")
        file(".hidden/h.jpg"); file(".nomedia"); file("\$RECYCLE.BIN/x.jpg")

        val s = scan()
        assertEquals(7, s.filesSeen, "files seen")
        assertEquals(7, s.added)
        assertEquals(3, s.directoriesSeen, "root + sub + deeper only")
        assertEquals(listOf("a.jpg", "g.png"), all(FileCategory.PHOTO).map { it.name }.sorted())
        assertEquals(listOf("b.MP4"), all(FileCategory.VIDEO).map { it.name })
        assertEquals(listOf("c.mp3"), all(FileCategory.AUDIO).map { it.name })
        assertEquals(listOf("d.pdf", "e.xlsx"), all(FileCategory.DOCUMENT).map { it.name }.sorted())
        assertEquals(listOf("f.bin"), all(FileCategory.OTHER).map { it.name })
        assertTrue(all(FileCategory.PHOTO).all { it.faceScanState == FaceScanState.PENDING })
        assertEquals(FaceScanState.SKIPPED, all(FileCategory.AUDIO).single().faceScanState)
        assertEquals(
            mapOf("d.pdf" to FaceScanState.PENDING, "e.xlsx" to FaceScanState.SKIPPED),
            all(FileCategory.DOCUMENT).associate { it.name to it.faceScanState },
        )
    }

    @Test
    fun rescan_of_unchanged_tree_is_a_noop() {
        repeat(5) { file("d$it/f$it.jpg") }
        scan()
        val s = scan()
        assertEquals(0, s.added)
        assertEquals(0, s.removed)
        assertEquals(5, s.unchanged)
        assertEquals(5, total())
    }

    @Test
    fun detects_modified_files() {
        val f = file("a.jpg"); file("b.jpg")
        scan()
        f.writeBytes(ByteArray(99) { 2 })
        f.setLastModified(f.lastModified() + 5_000)
        val s = scan()
        assertEquals(1, s.added)
        assertEquals(1, s.unchanged)
        assertEquals(99L, all(FileCategory.PHOTO).first { it.name == "a.jpg" }.sizeBytes)
    }

    @Test
    fun removes_deleted_files_and_folders() {
        file("keep.jpg"); file("gone.jpg"); file("dir/x.jpg"); file("dir/y.png"); file("dir/inner/z.jpg")
        scan()
        assertEquals(5, total())
        File(root, "gone.jpg").delete()
        File(root, "dir").deleteRecursively()
        val s = scan()
        assertEquals(4, s.removed)
        assertEquals(listOf("keep.jpg"), all(FileCategory.PHOTO).map { it.name })
        assertEquals(1, total())
    }

    @Test
    fun deleting_a_file_cascades_to_its_faces() {
        val f = file("a.jpg")
        scan()
        addFace(f.absolutePath)
        assertEquals(1, faces())
        f.delete()
        scan()
        assertEquals(0, faces(), "foreign key cascade must remove faces of a deleted file")
    }

    @Test
    fun modifying_a_file_resets_its_faces() {
        val f = file("a.jpg")
        scan()
        addFace(f.absolutePath)
        f.writeBytes(ByteArray(50))
        f.setLastModified(f.lastModified() + 5_000)
        scan()
        assertEquals(0, faces(), "stale faces of a changed file must be dropped")
    }

    @Test
    fun unreadable_folder_keeps_its_old_rows() {
        file("ok.jpg"); file("locked/a.jpg"); file("locked/b.jpg")
        scan()
        val locked = File(root, "locked")
        locked.setReadable(false); locked.setExecutable(false)
        if (locked.canRead()) return // running as root: cannot simulate
        val s = scan()
        assertEquals(1, s.unreadableDirectories)
        assertEquals(0, s.removed, "unreadable != deleted")
        assertEquals(3, total())
    }

    @Test
    fun symlinks_are_not_followed() {
        file("real/a.jpg")
        val outside = File(base, "outside").apply { mkdirs() }
        File(outside, "secret.jpg").writeBytes(ByteArray(4))
        try {
            Files.createSymbolicLink(File(root, "loop").toPath(), root.toPath())
            Files.createSymbolicLink(File(root, "ext").toPath(), outside.toPath())
        } catch (e: Exception) {
            return // symlinks unsupported here
        }
        val s = scan() // must terminate (no cycle)
        assertEquals(1, s.filesSeen)
        assertEquals(1, total())
    }

    @Test
    fun unicode_and_emoji_paths_roundtrip() {
        val f = file("📷 фото é/naïve 🙂.jpg")
        file("📷 фото é/other.png")
        assertEquals(2, scan().added)
        assertEquals(2, scan().unchanged)
        f.delete()
        assertEquals(1, scan().removed, "stale cleanup must work for non-BMP paths")
        assertEquals(1, total())
    }

    @Test
    fun missing_root_is_harmless() {
        file("a.jpg")
        scan()
        val s = runBlocking {
            FileScanner(db.fileDao(), { listOf(File(base, "nope")) })
                .scan().toList().filterIsInstance<ScanEvent.Finished>().single().stats
        }
        assertEquals(0, s.filesSeen)
        assertEquals(1, total(), "a missing root must not wipe the index")
    }

    @Test
    fun handles_fifteen_thousand_files() {
        repeat(300) { d -> repeat(50) { i -> File(root, "d$d").also { it.mkdirs() }.resolve("f$i.jpg").writeBytes(ByteArray(8)) } }
        val t0 = System.nanoTime()
        val s = scan()
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("15k files: first scan ${ms} ms")
        assertEquals(15_000, s.filesSeen)
        assertEquals(15_000, total())
        val t1 = System.nanoTime()
        val s2 = scan()
        println("15k files: rescan ${(System.nanoTime() - t1) / 1_000_000} ms")
        assertEquals(15_000, s2.unchanged)
        assertEquals(0, s2.added)
        assertTrue(ms < 120_000, "first scan took $ms ms")
    }
}
