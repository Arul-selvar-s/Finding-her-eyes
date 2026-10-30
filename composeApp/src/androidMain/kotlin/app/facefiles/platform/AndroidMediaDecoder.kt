package app.facefiles.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import app.facefiles.media.DecodedImage
import app.facefiles.media.MediaDecoder
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

class AndroidMediaDecoder : MediaDecoder {

    override fun decodeImage(path: String, maxSide: Int): DecodedImage? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeFile(path, options)
            if (decoded == null) null else toDecoded(scaleDown(rotateByExif(path, decoded), maxSide))
        }
    } catch (e: Throwable) {
        null
    }

    override fun videoDurationMs(path: String): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Throwable) {
            0L
        } finally {
            try { retriever.release() } catch (e: Throwable) { /* ignore */ }
        }
    }

    override fun videoFrame(path: String, timeMs: Long, maxSide: Int): DecodedImage? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val at = if (duration > 0) timeMs.coerceIn(0L, max(0L, duration - 1)) else timeMs.coerceAtLeast(0L)
            val frame = retriever.getFrameAtTime(at * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            if (frame == null) null else toDecoded(scaleDown(frame, maxSide))
        } catch (e: Throwable) {
            null
        } finally {
            try { retriever.release() } catch (e: Throwable) { /* ignore */ }
        }
    }

    override fun pdfPageCount(path: String): Int = try {
        ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            val renderer = PdfRenderer(fd)
            try { renderer.pageCount } finally { renderer.close() }
        }
    } catch (e: Throwable) {
        0
    }

    override fun renderPdfPage(path: String, page: Int, maxSide: Int): DecodedImage? = try {
        ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            val renderer = PdfRenderer(fd)
            try {
                if (page !in 0 until renderer.pageCount) {
                    null
                } else {
                    val p = renderer.openPage(page)
                    try {
                        val scale = maxSide.toFloat() / max(p.width, p.height)
                        val w = max(1, (p.width * scale).toInt())
                        val h = max(1, (p.height * scale).toInt())
                        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        p.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        toDecoded(bitmap)
                    } finally {
                        p.close()
                    }
                }
            } finally {
                renderer.close()
            }
        }
    } catch (e: Throwable) {
        null
    }

    override fun encodeJpeg(image: DecodedImage, quality: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(image.argb, image.width, image.height, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    override fun decodeJpeg(bytes: ByteArray): DecodedImage? = try {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { toDecoded(it) }
    } catch (e: Throwable) {
        null
    }

    // ---- helpers ----

    private fun toDecoded(bitmap: Bitmap): DecodedImage {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return DecodedImage(bitmap.width, bitmap.height, pixels)
    }

    private fun scaleDown(bitmap: Bitmap, maxSide: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxSide) return bitmap
        val scale = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            max(1, (bitmap.width * scale).toInt()),
            max(1, (bitmap.height * scale).toInt()),
            true,
        )
    }

    private fun rotateByExif(path: String, bitmap: Bitmap): Bitmap {
        val orientation = try {
            ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Throwable) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return bitmap
        }
        return try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        } catch (e: Throwable) {
            bitmap
        }
    }
}
