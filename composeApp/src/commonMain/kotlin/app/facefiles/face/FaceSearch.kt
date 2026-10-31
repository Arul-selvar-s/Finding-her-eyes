package app.facefiles.face

import app.facefiles.data.FaceDao
import app.facefiles.data.FaceInfo
import app.facefiles.data.FileDao
import app.facefiles.data.FileEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One file that contains the searched person (best matching face of that file). */
data class FileMatch(
    val path: String,
    val similarity: Float,
    val bestFaceId: Long,
    val frameTimeMs: Long,
    val pdfPage: Int,
    val facesMatched: Int,
)

/** A cluster of faces judged to be the same person. */
data class Person(
    val id: Int,
    val coverFaceId: Long,
    val faceCount: Int,
    val fileCount: Int,
    val centroid: FloatArray,
)

/** Face embeddings held in one flat array for fast brute-force cosine search. */
class VectorIndex(
    val dim: Int,
    val ids: LongArray,
    val paths: Array<String>,
    val frameTimes: LongArray,
    val pages: IntArray,
    val scores: FloatArray,
    val data: FloatArray,
) {
    val size: Int get() = ids.size
}

class FaceSearch(
    private val faceDao: FaceDao,
    private val fileDao: FileDao,
) {
    private val mutex = Mutex()
    private var index: VectorIndex? = null
    private var indexedCount = -1

    /** Call after files were moved/deleted so cached paths are reloaded. */
    fun invalidate() {
        index = null
        indexedCount = -1
    }

    suspend fun facesOfFile(path: String): List<FaceInfo> = faceDao.facesOfFile(path)

    suspend fun vectorOf(faceId: Long): FloatArray? = faceDao.embeddingOf(faceId)?.let { Embeddings.fromBytes(it) }

    /** Every file containing a face whose cosine similarity to [query] is >= [threshold], best first. */
    suspend fun search(query: FloatArray, threshold: Float): List<FileMatch> = withContext(Dispatchers.Default) {
        val ix = loadIndex()
        val dim = ix.dim
        if (query.size != dim) return@withContext emptyList()
        val best = HashMap<String, FileMatch>()
        for (i in 0 until ix.size) {
            val off = i * dim
            var s = 0f
            for (k in 0 until dim) s += ix.data[off + k] * query[k]
            if (s < threshold) continue
            val path = ix.paths[i]
            val cur = best[path]
            best[path] = if (cur == null) {
                FileMatch(path, s, ix.ids[i], ix.frameTimes[i], ix.pages[i], 1)
            } else if (s > cur.similarity) {
                FileMatch(path, s, ix.ids[i], ix.frameTimes[i], ix.pages[i], cur.facesMatched + 1)
            } else {
                cur.copy(facesMatched = cur.facesMatched + 1)
            }
        }
        best.values.sortedByDescending { it.similarity }
    }

    /** Matches joined with their indexed file rows (rows that no longer exist are dropped). */
    suspend fun searchFiles(query: FloatArray, threshold: Float): List<Pair<FileEntity, FileMatch>> {
        val matches = search(query, threshold)
        if (matches.isEmpty()) return emptyList()
        val rows = HashMap<String, FileEntity>()
        for (chunk in matches.map { it.path }.chunked(500)) {
            for (row in fileDao.filesByPaths(chunk)) rows[row.path] = row
        }
        return matches.mapNotNull { m -> rows[m.path]?.let { it to m } }
    }

    /**
     * Groups all faces into people: highest-quality faces first, each joins the closest existing
     * person if similar enough, else starts a new one.
     */
    suspend fun people(threshold: Float = 0.5f, minFaces: Int = 2, maxFaces: Int = 25_000): List<Person> =
        withContext(Dispatchers.Default) {
            val ix = loadIndex()
            val dim = ix.dim
            if (ix.size == 0) return@withContext emptyList()
            val order = (0 until ix.size).sortedByDescending { ix.scores[it] }.take(maxFaces)

            val sums = ArrayList<FloatArray>()
            val units = ArrayList<FloatArray>()
            val covers = ArrayList<Long>()
            val faceCounts = ArrayList<Int>()
            val files = ArrayList<HashSet<String>>()
            val v = FloatArray(dim)

            for (i in order) {
                val off = i * dim
                for (k in 0 until dim) v[k] = ix.data[off + k]
                var bestIdx = -1
                var bestSim = threshold
                for (p in units.indices) {
                    val s = Embeddings.dot(units[p], v)
                    if (s >= bestSim) { bestSim = s; bestIdx = p }
                }
                if (bestIdx < 0) {
                    sums.add(v.copyOf())
                    units.add(v.copyOf())
                    covers.add(ix.ids[i])
                    faceCounts.add(1)
                    files.add(hashSetOf(ix.paths[i]))
                } else {
                    val sum = sums[bestIdx]
                    for (k in 0 until dim) sum[k] += v[k]
                    val unit = units[bestIdx]
                    for (k in 0 until dim) unit[k] = sum[k]
                    Embeddings.normalize(unit)
                    faceCounts[bestIdx] = faceCounts[bestIdx] + 1
                    files[bestIdx].add(ix.paths[i])
                }
            }
            units.indices
                .map { Person(it, covers[it], faceCounts[it], files[it].size, units[it]) }
                .filter { it.faceCount >= minFaces }
                .sortedWith(compareByDescending<Person> { it.fileCount }.thenByDescending { it.faceCount })
        }

    private suspend fun loadIndex(): VectorIndex = mutex.withLock {
        val count = faceDao.count()
        val cached = index
        if (cached != null && count == indexedCount) return cached
        val rows = faceDao.allVectors()
        val dim = rows.firstOrNull()?.let { it.embedding.size / 4 } ?: Embeddings.DIM
        val ids = LongArray(rows.size)
        val frameTimes = LongArray(rows.size)
        val pages = IntArray(rows.size)
        val scores = FloatArray(rows.size)
        val data = FloatArray(rows.size * dim)
        val paths = Array(rows.size) { "" }
        for ((i, r) in rows.withIndex()) {
            ids[i] = r.id
            paths[i] = r.filePath
            frameTimes[i] = r.frameTimeMs
            pages[i] = r.pdfPage
            scores[i] = r.score
            val vec = Embeddings.fromBytes(r.embedding)
            if (vec.size == dim) vec.copyInto(data, i * dim)
        }
        val built = VectorIndex(dim, ids, paths, frameTimes, pages, scores, data)
        index = built
        indexedCount = count
        built
    }
}
