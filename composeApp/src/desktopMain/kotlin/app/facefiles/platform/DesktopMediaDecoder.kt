package app.facefiles.platform

import app.facefiles.media.DecodedImage
import app.facefiles.media.ImageOps
import app.facefiles.media.MediaDecoder
import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifIFD0Directory
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.max

class DesktopMediaDecoder : MediaDecoder {

    override fun decodeImage(path: String, maxSide: Int): DecodedImage? = try {
        val file = File(path)
        ImageIO.createImageInputStream(file)?.use { stream ->
            val readers = ImageIO.getImageReaders(stream)
            if (!readers.hasNext()) return@use null
            val reader = readers.next()
            try {
                reader.input = stream
                val w = reader.getWidth(0)
                val h = reader.getHeight(0)
                var sub = 1
                while (max(w, h) / (sub * 2) >= maxSide) sub *= 2
                val param = reader.defaultReadParam
                if (sub > 1) param.setSourceSubsampling(sub, sub, 0, 0)
                var decoded = reader.read(0, param).toDecoded()
                val orientation = readExifOrientation(file)
                if (orientation != 1) decoded = ImageOps.orient(decoded, orientation)
                ImageOps.fitWithin(decoded, maxSide)
            } finally {
                reader.dispose()
            }
        }
    } catch (e: Throwable) {
        null
    }

    // Video frames on desktop are added together with the ffmpeg-based sampler.
    override fun videoDurationMs(path: String): Long = 0L

    override fun videoFrame(path: String, timeMs: Long, maxSide: Int): DecodedImage? = null

    override fun pdfPageCount(path: String): Int = try {
        Loader.loadPDF(File(path)).use { it.numberOfPages }
    } catch (e: Throwable) {
        0
    }

    override fun renderPdfPage(path: String, page: Int, maxSide: Int): DecodedImage? = try {
        Loader.loadPDF(File(path)).use { doc ->
            if (page !in 0 until doc.numberOfPages) {
                null
            } else {
                val box = doc.getPage(page).mediaBox
                val longestPoints = max(box.width, box.height).coerceAtLeast(1f)
                val dpi = (maxSide / (longestPoints / 72f)).coerceIn(40f, 220f)
                PDFRenderer(doc).renderImageWithDPI(page, dpi, ImageType.RGB).toDecoded()
            }
        }
    } catch (e: Throwable) {
        null
    }

    override fun encodeJpeg(image: DecodedImage, quality: Int): ByteArray {
        val rgb = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
        rgb.setRGB(0, 0, image.width, image.height, image.argb, 0, image.width)
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream()
        try {
            ImageIO.createImageOutputStream(out).use { stream ->
                writer.output = stream
                val param = writer.defaultWriteParam
                param.compressionMode = ImageWriteParam.MODE_EXPLICIT
                param.compressionQuality = quality / 100f
                writer.write(null, IIOImage(rgb, null, null), param)
            }
        } finally {
            writer.dispose()
        }
        return out.toByteArray()
    }

    override fun decodeJpeg(bytes: ByteArray): DecodedImage? = try {
        ImageIO.read(ByteArrayInputStream(bytes))?.toDecoded()
    } catch (e: Throwable) {
        null
    }

    private fun BufferedImage.toDecoded(): DecodedImage =
        DecodedImage(width, height, getRGB(0, 0, width, height, null, 0, width))

    private fun readExifOrientation(file: File): Int {
        val ext = file.extension.lowercase()
        if (ext != "jpg" && ext != "jpeg" && ext != "tif" && ext != "tiff") return 1
        return try {
            val dir = ImageMetadataReader.readMetadata(file).getFirstDirectoryOfType(ExifIFD0Directory::class.java)
            if (dir != null && dir.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                dir.getInt(ExifIFD0Directory.TAG_ORIENTATION)
            } else {
                1
            }
        } catch (e: Throwable) {
            1
        }
    }
}
