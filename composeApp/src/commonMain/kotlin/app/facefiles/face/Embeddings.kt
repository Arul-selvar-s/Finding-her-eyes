package app.facefiles.face

import kotlin.math.sqrt

object Embeddings {
    const val DIM = 128

    fun toBytes(v: FloatArray): ByteArray {
        val out = ByteArray(v.size * 4)
        for (i in v.indices) {
            val bits = v[i].toRawBits()
            out[i * 4] = bits.toByte()
            out[i * 4 + 1] = (bits shr 8).toByte()
            out[i * 4 + 2] = (bits shr 16).toByte()
            out[i * 4 + 3] = (bits shr 24).toByte()
        }
        return out
    }

    fun fromBytes(b: ByteArray): FloatArray {
        val out = FloatArray(b.size / 4)
        for (i in out.indices) {
            val bits = (b[i * 4].toInt() and 0xFF) or
                ((b[i * 4 + 1].toInt() and 0xFF) shl 8) or
                ((b[i * 4 + 2].toInt() and 0xFF) shl 16) or
                ((b[i * 4 + 3].toInt() and 0xFF) shl 24)
            out[i] = Float.fromBits(bits)
        }
        return out
    }

    fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    /** In place; returns [v]. */
    fun normalize(v: FloatArray): FloatArray {
        var n = 0f
        for (x in v) n += x * x
        val inv = if (n > 0f) 1f / sqrt(n) else 0f
        for (i in v.indices) v[i] *= inv
        return v
    }
}
