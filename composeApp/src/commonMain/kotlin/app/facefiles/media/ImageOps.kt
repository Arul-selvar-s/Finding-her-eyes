package app.facefiles.media

import kotlin.math.max
import kotlin.math.min

/** Small, dependency-free pixel helpers (ARGB ints) used by both platforms and the face pipeline. */
object ImageOps {

    /** Scales down so the longest side is <= [maxSide]; returns [src] untouched when already small. */
    fun fitWithin(src: DecodedImage, maxSide: Int): DecodedImage {
        val longest = max(src.width, src.height)
        if (longest <= maxSide) return src
        val scale = maxSide.toFloat() / longest
        return resize(src, max(1, (src.width * scale).toInt()), max(1, (src.height * scale).toInt()))
    }

    fun resize(src: DecodedImage, nw: Int, nh: Int): DecodedImage {
        if (nw == src.width && nh == src.height) return src
        return if (nw <= src.width && nh <= src.height) areaAverage(src, nw, nh) else bilinear(src, nw, nh)
    }

    private fun areaAverage(src: DecodedImage, nw: Int, nh: Int): DecodedImage {
        val w = src.width
        val h = src.height
        val out = IntArray(nw * nh)
        for (dy in 0 until nh) {
            val sy0 = (dy.toLong() * h / nh).toInt()
            val sy1 = max(sy0 + 1, ((dy + 1).toLong() * h / nh).toInt())
            for (dx in 0 until nw) {
                val sx0 = (dx.toLong() * w / nw).toInt()
                val sx1 = max(sx0 + 1, ((dx + 1).toLong() * w / nw).toInt())
                var a = 0; var r = 0; var g = 0; var b = 0
                var n = 0
                for (sy in sy0 until min(sy1, h)) {
                    val row = sy * w
                    for (sx in sx0 until min(sx1, w)) {
                        val p = src.argb[row + sx]
                        a += p ushr 24; r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
                        n++
                    }
                }
                if (n == 0) n = 1
                out[dy * nw + dx] = ((a / n) shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
        return DecodedImage(nw, nh, out)
    }

    private fun bilinear(src: DecodedImage, nw: Int, nh: Int): DecodedImage {
        val out = IntArray(nw * nh)
        for (dy in 0 until nh) {
            val fy = (dy + 0.5f) * src.height / nh - 0.5f
            for (dx in 0 until nw) {
                val fx = (dx + 0.5f) * src.width / nw - 0.5f
                out[dy * nw + dx] = sample(src, fx, fy)
            }
        }
        return DecodedImage(nw, nh, out)
    }

    /** Bilinear sample at (x, y); coordinates outside the image are clamped to the edge. */
    fun sample(src: DecodedImage, x: Float, y: Float): Int {
        val cx = x.coerceIn(0f, (src.width - 1).toFloat())
        val cy = y.coerceIn(0f, (src.height - 1).toFloat())
        val x0 = cx.toInt()
        val y0 = cy.toInt()
        val x1 = min(x0 + 1, src.width - 1)
        val y1 = min(y0 + 1, src.height - 1)
        val tx = cx - x0
        val ty = cy - y0
        val p00 = src.argb[y0 * src.width + x0]
        val p10 = src.argb[y0 * src.width + x1]
        val p01 = src.argb[y1 * src.width + x0]
        val p11 = src.argb[y1 * src.width + x1]
        fun ch(shift: Int): Int {
            val top = ((p00 shr shift) and 0xFF) * (1 - tx) + ((p10 shr shift) and 0xFF) * tx
            val bottom = ((p01 shr shift) and 0xFF) * (1 - tx) + ((p11 shr shift) and 0xFF) * tx
            return (top * (1 - ty) + bottom * ty + 0.5f).toInt().coerceIn(0, 255)
        }
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Applies an EXIF orientation tag (1..8) so the result is upright. */
    fun orient(src: DecodedImage, exifOrientation: Int): DecodedImage {
        if (exifOrientation !in 2..8) return src
        val w = src.width
        val h = src.height
        val swap = exifOrientation >= 5
        val nw = if (swap) h else w
        val nh = if (swap) w else h
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val dx: Int
                val dy: Int
                when (exifOrientation) {
                    2 -> { dx = w - 1 - x; dy = y }
                    3 -> { dx = w - 1 - x; dy = h - 1 - y }
                    4 -> { dx = x; dy = h - 1 - y }
                    5 -> { dx = y; dy = x }
                    6 -> { dx = h - 1 - y; dy = x }
                    7 -> { dx = h - 1 - y; dy = w - 1 - x }
                    else -> { dx = y; dy = w - 1 - x }
                }
                out[dy * nw + dx] = src.argb[y * w + x]
            }
        }
        return DecodedImage(nw, nh, out)
    }
}
