package app.facefiles

import androidx.room.Room
import app.facefiles.data.AppDatabase
import app.facefiles.data.buildDatabase
import app.facefiles.domain.FaceScanState
import app.facefiles.domain.FaceSource
import app.facefiles.domain.ScanEvent
import app.facefiles.face.FaceScanner
import app.facefiles.face.FaceSearch
import app.facefiles.scan.FileScanner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The whole feature end to end: index files -> find faces -> pick a face -> every file with that person. */
class FacePipelineTest {
    private lateinit var base: File
    private lateinit var root: File
    private lateinit var db: AppDatabase
    private lateinit var search: FaceSearch
    private lateinit var fileScanner: FileScanner
    private lateinit var faceScanner: FaceScanner

    @BeforeTest
    fun setUp() {
        base = Files.createTempDirectory("facefiles-faces").toFile()
        root = File(base, "media").apply { mkdirs() }
        db = buildDatabase(Room.databaseBuilder<AppDatabase>(name = File(base, "t.db").absolutePath))
        search = FaceSearch(db.faceDao(), db.fileDao())
        fileScanner = FileScanner(db.fileDao(), { listOf(root) })
        faceScanner = FaceScanner(db.fileDao(), TestFaces.decoder, { TestFaces.engine })
        for (n in listOf("obama.jpg", "obama2.jpg", "biden.jpg", "obama_and_biden.jpg")) {
            File(TestFaces.path(n)).copyTo(File(root, "album/$n").also { it.parentFile.mkdirs() })
        }
        File(root, "notes.txt").writeText("not an image")
    }

    @AfterTest
    fun tearDown() {
        db.close()
        base.deleteRecursively()
    }

    private fun indexAndScanFaces() = runBlocking {
        fileScanner.scan().toList().filterIsInstance<ScanEvent.Finished>().single()
        faceScanner.scanAll()
    }

    private fun names(matches: List<Pair<app.facefiles.data.FileEntity, app.facefiles.face.FileMatch>>) =
        matches.map { it.first.name }.toSet()

    @Test
    fun finds_every_file_containing_the_selected_person() = runBlocking {
        indexAndScanFaces()
        val dao = db.fileDao()
        val states = listOf("obama.jpg", "obama2.jpg", "biden.jpg", "obama_and_biden.jpg").map { n ->
            dao.faceStateOf(File(root, "album/$n").path)
        }
        assertTrue(states.all { it == FaceScanState.DONE }, "all photos scanned: $states")
        assertEquals(FaceScanState.SKIPPED, dao.faceStateOf(File(root, "notes.txt").path))
        assertEquals(5, db.faceDao().count(), "1 + 1 + 1 + 2 faces")

        // View A/B: the faces of one photo, as thumbnails
        val faces = search.facesOfFile(File(root, "album/obama.jpg").path)
        assertEquals(1, faces.size)
        assertTrue(faces.single().thumb.isNotEmpty(), "face thumbnail stored")

        // View C/D: select that face -> everything containing him
        val query = checkNotNull(search.vectorOf(faces.single().id))
        val found = search.searchFiles(query, 0.363f)
        found.forEach { println("match ${it.first.name} sim=${it.second.similarity}") }
        val result = names(found)
        assertTrue("obama.jpg" in result && "obama2.jpg" in result && "obama_and_biden.jpg" in result, "found: $result")
        assertTrue("biden.jpg" !in result, "must not return the other person: $result")
        assertEquals("obama.jpg", found.first().first.name, "the photo the face came from is the best match")
    }

    @Test
    fun groups_faces_into_people() = runBlocking {
        indexAndScanFaces()
        val people = search.people(threshold = 0.4f, minFaces = 2)
        people.forEach { println("person faces=${it.faceCount} files=${it.fileCount}") }
        assertEquals(2, people.size, "Obama and Biden")
        assertEquals(3, people[0].faceCount)
        assertEquals(2, people[1].faceCount)
    }

    @Test
    fun rescanning_faces_is_idempotent_and_changed_files_are_rescanned() = runBlocking {
        indexAndScanFaces()
        val before = db.faceDao().count()
        faceScanner.scanAll()
        assertEquals(before, db.faceDao().count(), "nothing pending -> nothing new")
        val p = File(root, "album/obama.jpg")
        p.setLastModified(p.lastModified() + 10_000)
        fileScanner.scan().toList()
        assertEquals(FaceScanState.PENDING, db.fileDao().faceStateOf(p.path), "changed file is queued again")
        faceScanner.scanAll()
        assertEquals(before, db.faceDao().count(), "its faces were replaced, not duplicated")
    }

    @Test
    fun finds_a_person_inside_a_pdf() = runBlocking {
        val img = TestFaces.image("obama.jpg")
        val pdf = File(root, "docs/report.pdf").also { it.parentFile.mkdirs() }
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            val pic = PDImageXObject.createFromFile(TestFaces.path("obama.jpg"), doc)
            val w = 360f
            val h = w * img.height / img.width
            PDPageContentStream(doc, page).use { it.drawImage(pic, 100f, 400f, w, h) }
            doc.save(pdf)
        }
        indexAndScanFaces()
        val pdfFaces = search.facesOfFile(pdf.path)
        println("faces found inside the PDF: ${pdfFaces.size}")
        assertTrue(pdfFaces.isNotEmpty(), "a face embedded in a PDF page must be found")
        assertEquals(FaceSource.PDF_IMAGE, pdfFaces.first().source)

        val query = checkNotNull(search.vectorOf(search.facesOfFile(File(root, "album/obama.jpg").path).single().id))
        assertTrue("report.pdf" in names(search.searchFiles(query, 0.363f)), "PDF must show up in the person's results")
    }
}
