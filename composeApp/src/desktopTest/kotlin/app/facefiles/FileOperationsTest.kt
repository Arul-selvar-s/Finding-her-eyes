package app.facefiles

import androidx.room.Room
import app.facefiles.data.AppDatabase
import app.facefiles.data.FaceEntity
import app.facefiles.data.buildDatabase
import app.facefiles.domain.FaceKind
import app.facefiles.domain.FaceSource
import app.facefiles.domain.FileCategory
import app.facefiles.domain.ScanEvent
import app.facefiles.ops.JvmFileOperations
import app.facefiles.scan.FileScanner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileOperationsTest {
    private lateinit var base: File
    private lateinit var root: File
    private lateinit var db: AppDatabase
    private lateinit var scanner: FileScanner
    private lateinit var ops: JvmFileOperations
    private val changed = ArrayList<String>()

    @BeforeTest
    fun setUp() {
        base = Files.createTempDirectory("facefiles-ops").toFile()
        root = File(base, "data").apply { mkdirs() }
        db = buildDatabase(Room.databaseBuilder<AppDatabase>(name = File(base, "t.db").absolutePath))
        scanner = FileScanner(db.fileDao(), { listOf(root) })
        ops = JvmFileOperations(db.fileDao(), { listOf(root) }, { true }, { changed += it })
    }

    @AfterTest
    fun tearDown() {
        db.close()
        base.deleteRecursively()
    }

    private fun file(rel: String, bytes: Int = 16) =
        File(root, rel).also { it.parentFile.mkdirs(); it.writeBytes(ByteArray(bytes) { 7 }) }

    private fun scan() = runBlocking { scanner.scan().toList().filterIsInstance<ScanEvent.Finished>().single().stats }
    private fun photos() = runBlocking { db.fileDao().observeByCategory(FileCategory.PHOTO, 1000).first() }
    private fun total() = runBlocking { db.fileDao().observeCounts().first().sumOf { it.count } }
    private fun faces() = runBlocking { db.faceDao().observeFaceCount().first() }
    private fun addFace(path: String) = runBlocking {
        db.faceDao().insertAll(
            listOf(FaceEntity(filePath = path, source = FaceSource.IMAGE, left = 0f, top = 0f, right = 1f, bottom = 1f, kind = FaceKind.HUMAN, embedding = ByteArray(512))),
        )
    }

    @Test
    fun copy_creates_file_and_index_row_and_keeps_original() {
        val a = file("a.jpg")
        val dest = File(root, "out").apply { mkdirs() }
        scan()
        val r = runBlocking { ops.copy(listOf(a.path), dest.path) }
        assertTrue(r.ok, r.failures.toString())
        assertTrue(a.exists())
        assertTrue(File(dest, "a.jpg").exists())
        assertEquals(2, total(), "copy must be indexed immediately")
        assertTrue(changed.isNotEmpty(), "afterChange hook called")
    }

    @Test
    fun copy_to_same_folder_gets_a_unique_name() {
        val a = file("a.jpg")
        runBlocking { ops.copy(listOf(a.path), root.path) }
        assertTrue(File(root, "a (1).jpg").exists())
        runBlocking { ops.copy(listOf(a.path), root.path) }
        assertTrue(File(root, "a (2).jpg").exists())
    }

    @Test
    fun move_relocates_file_row_and_faces_follow() {
        val a = file("a.jpg")
        val dest = File(root, "out").apply { mkdirs() }
        scan()
        addFace(a.path)
        val r = runBlocking { ops.move(listOf(a.path), dest.path) }
        assertTrue(r.ok, r.failures.toString())
        assertFalse(a.exists())
        val moved = File(dest, "a.jpg")
        assertTrue(moved.exists())
        assertEquals(listOf(moved.path), photos().map { it.path })
        assertEquals(dest.path, photos().single().parentPath)
        assertEquals(1, faces(), "faces must follow the moved file (FK ON UPDATE CASCADE)")
    }

    @Test
    fun move_refuses_same_folder_and_into_itself() {
        val a = file("a.jpg")
        val dir = File(root, "dir").apply { mkdirs() }
        file("dir/x.jpg")
        assertFalse(runBlocking { ops.move(listOf(a.path), root.path) }.ok)
        assertFalse(runBlocking { ops.move(listOf(dir.path), File(dir, "child").apply { mkdirs() }.path) }.ok)
        assertTrue(a.exists() && dir.exists())
    }

    @Test
    fun move_folder_rebases_every_row_with_emoji_names() {
        file("📷 album/a.jpg"); file("📷 album/deep/b.jpg"); file("keep.jpg")
        val dest = File(root, "archive").apply { mkdirs() }
        scan()
        val r = runBlocking { ops.move(listOf(File(root, "📷 album").path), dest.path) }
        assertTrue(r.ok, r.failures.toString())
        val paths = photos().map { it.path }.sorted()
        assertEquals(
            listOf(
                File(root, "archive/📷 album/a.jpg").path,
                File(root, "archive/📷 album/deep/b.jpg").path,
                File(root, "keep.jpg").path,
            ).sorted(),
            paths,
        )
        assertEquals(File(root, "archive/📷 album/deep").path, photos().first { it.name == "b.jpg" }.parentPath)
        // and a rescan agrees with the incrementally-updated index
        val s = scan()
        assertEquals(0, s.added, "index must already match disk")
        assertEquals(0, s.removed)
    }

    @Test
    fun rename_updates_row_extension_category_and_faces() {
        val a = file("a.jpg")
        scan()
        addFace(a.path)
        val r = runBlocking { ops.rename(a.path, "holiday.png") }
        assertTrue(r.ok, r.failures.toString())
        assertTrue(File(root, "holiday.png").exists())
        val row = photos().single()
        assertEquals("holiday.png", row.name)
        assertEquals("png", row.extension)
        assertEquals(1, faces())
        assertFalse(runBlocking { ops.rename(File(root, "holiday.png").path, "a/b") }.ok, "slashes rejected")
    }

    @Test
    fun rename_refuses_existing_name() {
        file("a.jpg"); file("b.jpg")
        val r = runBlocking { ops.rename(File(root, "a.jpg").path, "b.jpg") }
        assertFalse(r.ok)
        assertTrue(File(root, "a.jpg").exists())
    }

    @Test
    fun delete_removes_files_folders_rows_and_faces() {
        val a = file("a.jpg"); file("dir/x.jpg"); file("dir/inner/y.jpg"); file("keep.jpg")
        scan()
        addFace(a.path)
        val r = runBlocking { ops.delete(listOf(a.path, File(root, "dir").path)) }
        assertTrue(r.ok, r.failures.toString())
        assertFalse(a.exists())
        assertFalse(File(root, "dir").exists())
        assertEquals(listOf("keep.jpg"), photos().map { it.name })
        assertEquals(0, faces())
    }

    @Test
    fun list_and_create_folder() {
        file("b.txt"); file("sub/x.jpg")
        runBlocking { ops.createFolder(root.path, "newdir") }
        val entries = runBlocking { ops.listFolder(root.path) }
        assertEquals(listOf("newdir", "sub", "b.txt"), entries.map { it.name }, "folders first, alphabetical")
        assertFalse(runBlocking { ops.createFolder(root.path, "newdir") }.ok)
    }
}
