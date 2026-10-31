package app.facefiles

import app.facefiles.face.Embeddings
import app.facefiles.face.FaceEngine
import app.facefiles.face.ModelSource
import app.facefiles.media.DecodedImage
import app.facefiles.platform.DesktopMediaDecoder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal object TestFaces {
    val decoder = DesktopMediaDecoder()
    val engine: FaceEngine by lazy {
        FaceEngine(ModelSource { name ->
            TestFaces::class.java.getResourceAsStream("/models/$name")
                ?: error("model $name missing - run scripts/fetch-models.sh")
        })
    }

    fun path(name: String): String {
        val url = TestFaces::class.java.getResource("/faces/$name") ?: error("test image $name missing - run scripts/fetch-test-data.sh")
        return File(url.toURI()).path
    }

    fun image(name: String): DecodedImage = assertNotNull(decoder.decodeImage(path(name), 1600), "cannot decode $name")

    fun embedding(name: String): FloatArray {
        val img = image(name)
        val faces = engine.detect(img)
        assertTrue(faces.isNotEmpty(), "no face in $name")
        return engine.embed(engine.alignedCrop(img, faces.maxByOrNull { it.width * it.height }!!))
    }
}

class FaceEngineTest {

    @Test
    fun models_load_and_report_io() {
        println("MODEL IO:\n" + TestFaces.engine.describe())
    }

    @Test
    fun detects_exactly_one_face_in_a_portrait() {
        val img = TestFaces.image("obama.jpg")
        val faces = TestFaces.engine.detect(img)
        faces.forEach { println("obama.jpg face: box=(${it.x1},${it.y1},${it.x2},${it.y2}) score=${it.score} image=${img.width}x${img.height}") }
        assertEquals(1, faces.size)
        val f = faces.single()
        assertTrue(f.score > 0.8f, "score ${f.score}")
        // eyes above mouth, landmarks inside the box (rough sanity of the landmark decoding)
        val lm = f.landmarks
        assertTrue(lm[1] < lm[7] && lm[3] < lm[9], "eyes must be above the mouth: ${lm.toList()}")
        assertTrue(lm[0] < lm[2], "first landmark is the eye on the image-left")
        for (i in 0 until 5) {
            assertTrue(lm[2 * i] in (f.x1 - 0.1f * f.width)..(f.x2 + 0.1f * f.width), "landmark $i x outside box")
            assertTrue(lm[2 * i + 1] in (f.y1 - 0.1f * f.height)..(f.y2 + 0.1f * f.height), "landmark $i y outside box")
        }
    }

    @Test
    fun detects_both_faces_in_a_group_photo() {
        val faces = TestFaces.engine.detect(TestFaces.image("obama_and_biden.jpg"))
        faces.forEach { println("group face: box=(${it.x1},${it.y1},${it.x2},${it.y2}) score=${it.score}") }
        assertEquals(2, faces.size)
    }

    @Test
    fun finds_no_face_in_a_blank_image() {
        val blank = DecodedImage(400, 300, IntArray(400 * 300) { 0xFFFFFFFF.toInt() })
        assertEquals(0, TestFaces.engine.detect(blank).size)
    }

    @Test
    fun embeddings_are_128d_and_unit_length() {
        val e = TestFaces.embedding("obama.jpg")
        assertEquals(128, e.size)
        assertEquals(1f, Embeddings.dot(e, e), 1e-3f)
    }

    @Test
    fun same_person_is_closer_than_a_different_person() {
        val obama1 = TestFaces.embedding("obama.jpg")
        val obama2 = TestFaces.embedding("obama2.jpg")
        val biden = TestFaces.embedding("biden.jpg")
        val same = Embeddings.dot(obama1, obama2)
        val diff1 = Embeddings.dot(obama1, biden)
        val diff2 = Embeddings.dot(obama2, biden)
        println("COSINE same(obama,obama2)=$same  different(obama,biden)=$diff1  different(obama2,biden)=$diff2")
        assertTrue(same > 0.363f, "same-person similarity $same is below SFace's recommended threshold 0.363")
        assertTrue(same > diff1 + 0.1f && same > diff2 + 0.1f, "identities not separated: same=$same diff=$diff1/$diff2")
    }

    @Test
    fun alignment_is_robust_to_image_flip_and_rescale() {
        // Same photo, mirrored and downscaled, must still match itself far better than another person.
        val img = TestFaces.image("obama.jpg")
        val flipped = app.facefiles.media.ImageOps.orient(img, 2)
        val small = app.facefiles.media.ImageOps.fitWithin(img, 400)
        fun emb(i: DecodedImage) = TestFaces.engine.detect(i).let { TestFaces.engine.embed(TestFaces.engine.alignedCrop(i, it.first())) }
        val base = emb(img)
        val sFlip = Embeddings.dot(base, emb(flipped))
        val sSmall = Embeddings.dot(base, emb(small))
        val sOther = Embeddings.dot(base, TestFaces.embedding("biden.jpg"))
        println("COSINE flipped=$sFlip small=$sSmall other=$sOther")
        assertTrue(sSmall > 0.6f, "downscaled self-similarity $sSmall")
        assertTrue(sFlip > sOther, "flipped self-similarity $sFlip should beat other person $sOther")
    }
}
