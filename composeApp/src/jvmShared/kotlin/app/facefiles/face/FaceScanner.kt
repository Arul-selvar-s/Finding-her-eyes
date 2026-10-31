package app.facefiles.face

import app.facefiles.data.FaceEntity
import app.facefiles.data.FileDao
import app.facefiles.data.PendingFile
import app.facefiles.domain.FaceKind
import app.facefiles.domain.FaceScanControl
import app.facefiles.domain.FaceScanProgress
import app.facefiles.domain.FaceScanState
import app.facefiles.domain.FaceSource
import app.facefiles.domain.FileCategory
import app.facefiles.media.DecodedImage
import app.facefiles.media.ImageOps
import app.facefiles.media.MediaDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

data class FaceScanOptions(
    val workers: Int = 2,
    val imageMaxSide: Int = 1280,
    val detectScore: Float = 0.7f,
    /** Faces smaller than this (in pixels of the decoded image) are ignored. */
    val minFaceSidePx: Float = 28f,
    val videoIntervalMs: Long = 1000L,
    val maxFramesPerVideo: Int = 90,
    val videoFrameMaxSide: Int = 960,
    val maxPdfPages: Int = 25,
    val pdfPageMaxSide: Int = 1200,
    val thumbSize: Int = 96,
)

/**
 * Walks every file whose faceScanState is PENDING and stores each face's box, a small thumbnail and its
 * 128-d embedding. Photos are decoded directly, videos are sampled (about one frame per second,
 * capped), PDF pages are rendered. Works with whatever [MediaDecoder] the platform provides.
 */
class FaceScanner(
    private val fileDao: FileDao,
    private val decoder: MediaDecoder,
    private val engineProvider: () -> FaceEngine,
    private val options: FaceScanOptions = FaceScanOptions(),
) : FaceScanControl {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val state = MutableStateFlow(FaceScanProgress())
    override val progress: StateFlow<FaceScanProgress> = state.asStateFlow()
    private var job: Job? = null

    @Synchronized
    override fun start() {
        if (job?.isActive == true) return
        job = scope.launch { scanAll() }
    }

    override fun stop() {
        job?.cancel()
    }

    /** Runs to completion (or cancellation). Used by [start] and directly by tests. */
    suspend fun scanAll() {
        val engine = try {
            engineProvider()
        } catch (e: Throwable) {
            state.value = FaceScanProgress(error = "Face models could not be loaded: ${e.message}", finished = true)
            return
        }
        state.value = FaceScanProgress(running = true, total = fileDao.pendingFaceCount())
        val attempted = HashSet<String>()
        val gate = Semaphore(max(1, options.workers))
        try {
            while (true) {
                val batch = fileDao.pendingFaceBatch(48).filter { attempted.add(it.path) }
                if (batch.isEmpty()) break
                coroutineScope {
                    for (file in batch) {
                        gate.acquire()
                        launch {
                            try {
                                processFile(engine, file)
                            } finally {
                                gate.release()
                            }
                        }
                    }
                }
            }
            state.update { it.copy(running = false, finished = true, currentFile = "") }
        } catch (e: CancellationException) {
            state.update { it.copy(running = false) }
            throw e
        }
    }

    private suspend fun processFile(engine: FaceEngine, file: PendingFile) {
        state.update { it.copy(currentFile = file.path.substringAfterLast('/').substringAfterLast('\\')) }
        val faces: List<FaceEntity>? = try {
            withContext(Dispatchers.Default) { extract(engine, file) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        }
        try {
            if (faces == null) {
                fileDao.setFaceState(file.path, FaceScanState.FAILED)
            } else {
                fileDao.saveFaces(file.path, faces, FaceScanState.DONE)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The file was removed or moved while scanning; nothing to record.
        }
        state.update { it.copy(processed = it.processed + 1, facesFound = it.facesFound + (faces?.size ?: 0)) }
    }

    /** null = the file could not be decoded at all. */
    private suspend fun extract(engine: FaceEngine, file: PendingFile): List<FaceEntity>? = when {
        file.category == FileCategory.PHOTO ->
            decoder.decodeImage(file.path, options.imageMaxSide)?.let { facesIn(engine, it, file.path, FaceSource.IMAGE, -1L, -1) }
        file.category == FileCategory.VIDEO -> extractVideo(engine, file.path)
        file.extension == "pdf" -> extractPdf(engine, file.path)
        else -> emptyList()
    }

    private suspend fun extractVideo(engine: FaceEngine, path: String): List<FaceEntity>? {
        val duration = decoder.videoDurationMs(path)
        val step = if (duration > 0) max(options.videoIntervalMs, duration / options.maxFramesPerVideo) else options.videoIntervalMs
        val times = if (duration <= 0) listOf(0L) else generateSequence(0L) { it + step }.takeWhile { it < duration }.toList()
        val kept = ArrayList<FaceEntity>()
        val keptVectors = ArrayList<FloatArray>()
        var anyFrame = false
        for (t in times) {
            currentCoroutineContext().ensureActive()
            val frame = decoder.videoFrame(path, t, options.videoFrameMaxSide) ?: continue
            anyFrame = true
            for (face in facesIn(engine, frame, path, FaceSource.VIDEO_FRAME, t, -1)) {
                val vec = Embeddings.fromBytes(face.embedding)
                // The same person in neighbouring frames adds nothing; keep only visibly new looks.
                if (keptVectors.none { Embeddings.dot(it, vec) >= 0.8f }) {
                    kept += face
                    keptVectors += vec
                }
            }
        }
        return if (anyFrame) kept else null
    }

    private suspend fun extractPdf(engine: FaceEngine, path: String): List<FaceEntity>? {
        val pages = decoder.pdfPageCount(path)
        if (pages <= 0) return null
        val out = ArrayList<FaceEntity>()
        for (page in 0 until min(pages, options.maxPdfPages)) {
            currentCoroutineContext().ensureActive()
            val image = decoder.renderPdfPage(path, page, options.pdfPageMaxSide) ?: continue
            out += facesIn(engine, image, path, FaceSource.PDF_IMAGE, -1L, page)
        }
        return out
    }

    private fun facesIn(
        engine: FaceEngine,
        image: DecodedImage,
        path: String,
        source: FaceSource,
        frameTimeMs: Long,
        pdfPage: Int,
    ): List<FaceEntity> =
        engine.detect(image, scoreThreshold = options.detectScore)
            .filter { min(it.width, it.height) >= options.minFaceSidePx }
            .map { face ->
                val aligned = engine.alignedCrop(image, face)
                val embedding = engine.embed(aligned)
                val thumb = decoder.encodeJpeg(ImageOps.resize(aligned, options.thumbSize, options.thumbSize), 80)
                FaceEntity(
                    filePath = path,
                    source = source,
                    frameTimeMs = frameTimeMs,
                    pdfPage = pdfPage,
                    left = face.x1 / image.width,
                    top = face.y1 / image.height,
                    right = face.x2 / image.width,
                    bottom = face.y2 / image.height,
                    kind = FaceKind.HUMAN,
                    embedding = Embeddings.toBytes(embedding),
                    score = face.score,
                    thumb = thumb,
                )
            }
}
