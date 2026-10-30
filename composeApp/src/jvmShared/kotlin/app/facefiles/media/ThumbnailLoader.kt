package app.facefiles.media

import androidx.compose.ui.graphics.ImageBitmap
import app.facefiles.domain.FileCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class ThumbnailLoader(
    private val decoder: MediaDecoder,
    private val maxEntries: Int = 220,
) : ThumbnailProvider {

    private val cache = object : LinkedHashMap<String, ImageBitmap>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean =
            size > maxEntries
    }
    private val dispatcher = Dispatchers.IO.limitedParallelism(3)

    private fun cached(key: String): ImageBitmap? = synchronized(cache) { cache[key] }
    private fun remember(key: String, bitmap: ImageBitmap) { synchronized(cache) { cache[key] = bitmap } }

    override suspend fun load(path: String, category: FileCategory, sizePx: Int, timeMs: Long): ImageBitmap? {
        val key = "$path|$sizePx|$timeMs"
        cached(key)?.let { return it }
        val bitmap = withContext(dispatcher) {
            decode(path, category, sizePx, timeMs)?.toImageBitmap()
        }
        if (bitmap != null) remember(key, bitmap)
        return bitmap
    }

    override suspend fun loadLarge(path: String, category: FileCategory, maxSide: Int): ImageBitmap? =
        withContext(dispatcher) { decode(path, category, maxSide, -1L)?.toImageBitmap() }

    override suspend fun fromJpeg(key: String, bytes: ByteArray): ImageBitmap? {
        val k = "jpeg|$key"
        cached(k)?.let { return it }
        val bitmap = withContext(dispatcher) { decoder.decodeJpeg(bytes)?.toImageBitmap() }
        if (bitmap != null) remember(k, bitmap)
        return bitmap
    }

    private fun decode(path: String, category: FileCategory, size: Int, timeMs: Long): DecodedImage? = when {
        category == FileCategory.PHOTO -> decoder.decodeImage(path, size)
        category == FileCategory.VIDEO -> decoder.videoFrame(path, if (timeMs >= 0) timeMs else 1000L, size)
        category == FileCategory.DOCUMENT && path.endsWith(".pdf", ignoreCase = true) -> decoder.renderPdfPage(path, 0, size)
        else -> null
    }
}
