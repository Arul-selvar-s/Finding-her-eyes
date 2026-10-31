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

    private companion object {
        // obama x2, biden, two_people (Obama + Biden), alex, lin  =>  7 faces
        val ALBUM = listOf("obama.jpg", "obama2.jpg", "biden.jpg", "two_people.jpg", "alex-lacamoire.png", "lin-manuel-miranda.png")
    }

    @BeforeTest
    fun setUp() {
        base = Files.createTempDirectory("facefiles-faces").toFile()
        root = File(base, "media").apply { mkdirs() }
        db = buildDatabase(Room.databaseBuilder<AppDatabase>(name = File(base, "t.db").absolutePath))
        search = FaceSearch(db.faceDao(), db.fileDao())
        fileScanner = FileScanner(db.fileDao(), { listOf(root) })
        faceScanner = FaceScanner(db.fileDao(), TestFaces.decoder, { TestFaces.engine })
        for (n in ALBUM) {
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

    private suspend fun filesOfPerson(sourcePhoto: String, threshold: Float = 0.363f): Set<String> {
        val face = search.facesOfFile(File(root, "album/$sourcePhoto").path).single()
        val query = checkNotNull(search.vectorOf(face.id))
        val found = search.searchFiles(query, threshold)
        found.forEach { println("query=$sourcePhoto -> ${it.first.name} sim=${it.second.similarity}") }
        return names(found)
    }

    @Test
    fun finds_every_file_containing_the_selected_person() = runBlocking {
        indexAndScanFaces()
        val dao = db.fileDao()
        val states = ALBUM.map { n -> n to dao.faceStateOf(File(root, "album/$n").path) }
        assertTrue(states.all { it.second == FaceScanState.DONE }, "all photos scanned: $states")
        assertEquals(FaceScanState.SKIPPED, dao.faceStateOf(File(root, "notes.txt").path))
        assertEquals(7, db.faceDao().count(), "1 + 1 + 1 + 2 + 1 + 1 faces")

        // View A/B: the faces of one photo, as thumbnails
        val faces = search.facesOfFile(File(root, "album/two_people.jpg").path)
        assertEquals(2, faces.size, "Obama and Biden")
        assertTrue(faces.all { it.thumb.isNotEmpty() }, "face thumbnails stored")

        // View C/D: pick a face -> every file with that person (and nobody else)
        assertEquals(setOf("obama.jpg", "obama2.jpg", "two_people.jpg"), filesOfPerson("obama.jpg"))
        assertEquals(setOf("biden.jpg", "two_people.jpg"), filesOfPerson("biden.jpg"))
        assertEquals(setOf("alex-lacamoire.png"), filesOfPerson("alex-lacamoire.png"))
        assertEquals(setOf("lin-manuel-miranda.png"), filesOfPerson("lin-manuel-miranda.png"))
    }

    @Test
    fun groups_faces_into_people() = runBlocking {
        indexAndScanFaces()
        val people = search.people(threshold = 0.4f, minFaces = 2)
        people.forEach { println("person faces=${it.faceCount} files=${it.fileCount}") }
        assertEquals(2, people.size, "Obama (3 faces) and Biden (2 faces); Alex and Lin appear once")
        assertEquals(listOf(3, 2), people.map { it.faceCount })
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

        assertTrue("report.pdf" in filesOfPerson("obama.jpg"), "PDF must show up in the person's results")
    }
}
