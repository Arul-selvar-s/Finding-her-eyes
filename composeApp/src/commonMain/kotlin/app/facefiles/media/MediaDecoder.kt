package app.facefiles.media

import androidx.compose.ui.graphics.ImageBitmap
import app.facefiles.domain.FileCategory

/** Plain pixels: ARGB ints, row-major. Shared by thumbnails and the face pipeline. */
class DecodedImage(val width: Int, val height: Int, val argb: IntArray)

/** Platform decoding (BitmapFactory/MediaMetadataRetriever on Android, ImageIO/PDFBox on desktop). */
interface MediaDecoder {
    /** Decodes with EXIF rotation applied, longest side <= [maxSide]. null if unsupported/corrupt. */
    fun decodeImage(path: String, maxSide: Int): DecodedImage?
    fun videoDurationMs(path: String): Long
    fun videoFrame(path: String, timeMs: Long, maxSide: Int): DecodedImage?
    fun pdfPageCount(path: String): Int
    fun renderPdfPage(path: String, page: Int, maxSide: Int): DecodedImage?
    fun encodeJpeg(image: DecodedImage, quality: Int): ByteArray
    fun decodeJpeg(bytes: ByteArray): DecodedImage?
}

expect fun DecodedImage.toImageBitmap(): ImageBitmap

/** What the UI uses to show previews. */
interface ThumbnailProvider {
    /** Small, cached preview for grids. */
    suspend fun load(path: String, category: FileCategory, sizePx: Int, timeMs: Long = -1L): ImageBitmap?

    /** Big, uncached image for the full-screen viewer. */
    suspend fun loadLarge(path: String, category: FileCategory, maxSide: Int): ImageBitmap?

    /** A stored JPEG (e.g. face crop) -> bitmap, cached by [key]. */
    suspend fun fromJpeg(key: String, bytes: ByteArray): ImageBitmap?
}
