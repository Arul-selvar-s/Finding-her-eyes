package app.facefiles.face

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import app.facefiles.media.DecodedImage
import app.facefiles.media.ImageOps
import java.io.InputStream
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

fun interface ModelSource {
    fun open(name: String): InputStream
}

/** Box + 5 landmarks in the pixel coordinates of the image that was searched. */
class DetectedFace(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val score: Float,
    /** right eye, left eye, nose, right mouth corner, left mouth corner as (x, y) pairs. */
    val landmarks: FloatArray,
) {
    val width: Float get() = x2 - x1
    val height: Float get() = y2 - y1
}

/**
 * On-device face detection (YuNet, MIT) and 128-d embeddings (SFace, Apache-2.0) with ONNX Runtime.
 * No network, no cloud: both models ship inside the app.
 */
class FaceEngine(models: ModelSource, intraThreads: Int = 2) : AutoCloseable {

    companion object {
        const val DETECTOR_MODEL = "face_detection_yunet_2023mar.onnx"
        const val RECOGNIZER_MODEL = "face_recognition_sface_2021dec.onnx"
        const val ALIGNED_SIZE = 112

        /** Standard ArcFace/SFace 112x112 landmark template (same order as the detector's landmarks). */
        private val TEMPLATE = floatArrayOf(
            38.2946f, 51.6963f,
            73.5318f, 51.5014f,
            56.0252f, 71.7366f,
            41.5493f, 92.3655f,
            70.7299f, 92.2041f,
        )
        private val STRIDES = intArrayOf(8, 16, 32)
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val detector: OrtSession
    private val recognizer: OrtSession
    private val detectorInput: String
    private val recognizerInput: String
    private val fixedH: Int
    private val fixedW: Int

    init {
        val options = OrtSession.SessionOptions()
        try {
            options.setIntraOpNumThreads(intraThreads)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            detector = env.createSession(models.open(DETECTOR_MODEL).use { it.readBytes() }, options)
            recognizer = env.createSession(models.open(RECOGNIZER_MODEL).use { it.readBytes() }, options)
        } finally {
            options.close()
        }
        detectorInput = detector.inputNames.first()
        recognizerInput = recognizer.inputNames.first()
        val shape = (detector.inputInfo[detectorInput]!!.info as TensorInfo).shape
        fixedH = if (shape.size == 4 && shape[2] > 0 && shape[3] > 0) shape[2].toInt() else -1
        fixedW = if (shape.size == 4 && shape[2] > 0 && shape[3] > 0) shape[3].toInt() else -1
    }

    /** Human-readable model I/O, for logs and tests. */
    fun describe(): String = buildString {
        fun dump(title: String, s: OrtSession) {
            append(title).append(": inputs=")
            s.inputInfo.forEach { (n, i) -> append(n).append(' ').append((i.info as? TensorInfo)?.shape?.toList()).append("; ") }
            append("outputs=")
            s.outputInfo.forEach { (n, i) -> append(n).append(' ').append((i.info as? TensorInfo)?.shape?.toList()).append("; ") }
            append('\n')
        }
        dump("detector", detector)
        dump("recognizer", recognizer)
    }

    // ------------------------------------------------------------------------------------
    // Detection
    // ------------------------------------------------------------------------------------

    fun detect(
        image: DecodedImage,
        maxSide: Int = 960,
        scoreThreshold: Float = 0.7f,
        nmsThreshold: Float = 0.3f,
        maxFaces: Int = 50,
    ): List<DetectedFace> {
        val w = image.width
        val h = image.height
        val targetW: Int
        val targetH: Int
        val scale: Float
        if (fixedW > 0) {
            targetW = fixedW
            targetH = fixedH
            scale = min(targetW.toFloat() / w, targetH.toFloat() / h)
        } else {
            scale = min(1f, maxSide.toFloat() / max(w, h))
            targetW = roundUp32((w * scale).roundToInt())
            targetH = roundUp32((h * scale).roundToInt())
        }
        val rw = max(1, (w * scale).roundToInt()).coerceAtMost(targetW)
        val rh = max(1, (h * scale).roundToInt()).coerceAtMost(targetH)
        val resized = ImageOps.resize(image, rw, rh)

        // BGR, raw 0..255, NCHW, zero padded bottom/right.
        val plane = targetW * targetH
        val data = FloatArray(3 * plane)
        for (y in 0 until rh) {
            for (x in 0 until rw) {
                val p = resized.argb[y * rw + x]
                val i = y * targetW + x
                data[i] = (p and 0xFF).toFloat()
                data[plane + i] = ((p shr 8) and 0xFF).toFloat()
                data[2 * plane + i] = ((p shr 16) and 0xFF).toFloat()
            }
        }

        val candidates = ArrayList<DetectedFace>()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 3, targetH.toLong(), targetW.toLong())).use { input ->
            detector.run(mapOf(detectorInput to input)).use { result ->
                for (stride in STRIDES) {
                    val cls = output(result, "cls", stride)
                    val obj = output(result, "obj", stride)
                    val bbox = output(result, "bbox", stride)
                    val kps = output(result, "kps", stride)
                    val cols = targetW / stride
                    val rows = targetH / stride
                    for (idx in 0 until rows * cols) {
                        val score = sqrt(cls[idx].coerceIn(0f, 1f) * obj[idx].coerceIn(0f, 1f))
                        if (score < scoreThreshold) continue
                        val r = idx / cols
                        val c = idx % cols
                        val cx = (c + bbox[idx * 4]) * stride
                        val cy = (r + bbox[idx * 4 + 1]) * stride
                        val bw = exp(bbox[idx * 4 + 2]) * stride
                        val bh = exp(bbox[idx * 4 + 3]) * stride
                        val lm = FloatArray(10)
                        for (n in 0 until 5) {
                            lm[2 * n] = (kps[idx * 10 + 2 * n] + c) * stride / scale
                            lm[2 * n + 1] = (kps[idx * 10 + 2 * n + 1] + r) * stride / scale
                        }
                        candidates += DetectedFace(
                            x1 = ((cx - bw / 2) / scale).coerceIn(0f, w.toFloat()),
                            y1 = ((cy - bh / 2) / scale).coerceIn(0f, h.toFloat()),
                            x2 = ((cx + bw / 2) / scale).coerceIn(0f, w.toFloat()),
                            y2 = ((cy + bh / 2) / scale).coerceIn(0f, h.toFloat()),
                            score = score,
                            landmarks = lm,
                        )
                    }
                }
            }
        }
        return nonMaxSuppression(candidates, nmsThreshold).take(maxFaces)
    }

    private fun output(result: OrtSession.Result, prefix: String, stride: Int): FloatArray {
        val name = detector.outputNames.firstOrNull {
            val n = it.lowercase()
            n.startsWith(prefix) && n.endsWith("_$stride")
        } ?: error("Detector output '${prefix}_$stride' not found; outputs are ${detector.outputNames}")
        val tensor = result.get(name).get() as OnnxTensor
        val buffer = tensor.floatBuffer
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }

    private fun nonMaxSuppression(faces: List<DetectedFace>, iouThreshold: Float): List<DetectedFace> {
        val kept = ArrayList<DetectedFace>()
        for (f in faces.sortedByDescending { it.score }) {
            if (kept.none { iou(it, f) > iouThreshold }) kept += f
        }
        return kept
    }

    private fun iou(a: DetectedFace, b: DetectedFace): Float {
        val ix = max(0f, min(a.x2, b.x2) - max(a.x1, b.x1))
        val iy = max(0f, min(a.y2, b.y2) - max(a.y1, b.y1))
        val inter = ix * iy
        val union = a.width * a.height + b.width * b.height - inter
        return if (union <= 0f) 0f else inter / union
    }

    private fun roundUp32(v: Int): Int = max(32, ((v + 31) / 32) * 32)

    // ------------------------------------------------------------------------------------
    // Alignment + embedding
    // ------------------------------------------------------------------------------------

    /** 112x112 upright crop: a similarity transform maps the detected landmarks onto the template. */
    fun alignedCrop(image: DecodedImage, face: DetectedFace): DecodedImage {
        val p = face.landmarks
        var pmx = 0f; var pmy = 0f; var qmx = 0f; var qmy = 0f
        for (i in 0 until 5) {
            pmx += p[2 * i]; pmy += p[2 * i + 1]
            qmx += TEMPLATE[2 * i]; qmy += TEMPLATE[2 * i + 1]
        }
        pmx /= 5; pmy /= 5; qmx /= 5; qmy /= 5
        // Least-squares rotation+scale as one complex number z = a + ib.
        var num = 0f; var numI = 0f; var den = 0f
        for (i in 0 until 5) {
            val px = p[2 * i] - pmx; val py = p[2 * i + 1] - pmy
            val qx = TEMPLATE[2 * i] - qmx; val qy = TEMPLATE[2 * i + 1] - qmy
            num += px * qx + py * qy
            numI += px * qy - py * qx
            den += px * px + py * py
        }
        val out = IntArray(ALIGNED_SIZE * ALIGNED_SIZE)
        if (den < 1e-3f) {
            // Degenerate landmarks: plain box crop.
            for (v in 0 until ALIGNED_SIZE) for (u in 0 until ALIGNED_SIZE) {
                val sx = face.x1 + (u + 0.5f) / ALIGNED_SIZE * face.width
                val sy = face.y1 + (v + 0.5f) / ALIGNED_SIZE * face.height
                out[v * ALIGNED_SIZE + u] = ImageOps.sample(image, sx, sy)
            }
            return DecodedImage(ALIGNED_SIZE, ALIGNED_SIZE, out)
        }
        val a = num / den
        val b = numI / den
        val tx = qmx - (a * pmx - b * pmy)
        val ty = qmy - (b * pmx + a * pmy)
        val n = a * a + b * b
        for (v in 0 until ALIGNED_SIZE) {
            for (u in 0 until ALIGNED_SIZE) {
                val dx = u - tx
                val dy = v - ty
                val sx = (a * dx + b * dy) / n
                val sy = (-b * dx + a * dy) / n
                out[v * ALIGNED_SIZE + u] = ImageOps.sample(image, sx, sy)
            }
        }
        return DecodedImage(ALIGNED_SIZE, ALIGNED_SIZE, out)
    }

    /** L2-normalised 128-d embedding of an aligned 112x112 face. */
    fun embed(aligned: DecodedImage): FloatArray {
        require(aligned.width == ALIGNED_SIZE && aligned.height == ALIGNED_SIZE) { "expected a 112x112 aligned face" }
        val plane = ALIGNED_SIZE * ALIGNED_SIZE
        val data = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = aligned.argb[i]
            data[i] = ((p shr 16) and 0xFF).toFloat() // R
            data[plane + i] = ((p shr 8) and 0xFF).toFloat() // G
            data[2 * plane + i] = (p and 0xFF).toFloat() // B
        }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 3, ALIGNED_SIZE.toLong(), ALIGNED_SIZE.toLong())).use { input ->
            recognizer.run(mapOf(recognizerInput to input)).use { result ->
                val tensor = result.get(recognizer.outputNames.first()).get() as OnnxTensor
                val buffer = tensor.floatBuffer
                val vec = FloatArray(buffer.remaining()).also { buffer.get(it) }
                return Embeddings.normalize(vec)
            }
        }
    }

    override fun close() {
        detector.close()
        recognizer.close()
    }
}
