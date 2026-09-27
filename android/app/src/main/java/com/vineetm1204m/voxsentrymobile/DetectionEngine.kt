package com.vineetm1204m.voxsentrymobile

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

data class DetectionResult(
    val verdict: String,
    val spoofScore: Float?,
    val evidence: Float,
    val audioQuality: String,
    val speechRatio: Float,
    val chunksAnalyzed: Int,
    val chunksSeen: Int,
    val reason: String?
) {
    fun toJson(): String {
        val scoreStr = if (spoofScore != null) spoofScore.toString() else "null"
        val reasonStr = if (reason != null) "\"${reason.replace("\"", "\\\"")}\"" else "null"
        return "{\"verdict\":\"$verdict\",\"score\":$scoreStr,\"evidence\":${evidence}," +
            "\"audio_quality\":\"$audioQuality\",\"speech_ratio\":$speechRatio," +
            "\"chunks_analyzed\":$chunksAnalyzed,\"chunks_seen\":$chunksSeen,\"reason\":$reasonStr}"
    }
}

interface DetectionEngine {
    val isReady: Boolean
    fun setListener(listener: ((DetectionResult) -> Unit)?)
    fun processAudioWindow(audio: ShortArray)
    fun reset()
    fun close()
}

class OnDeviceDetectionEngine(private val context: Context) : DetectionEngine {

    private val audioProcessor = AudioProcessor()
    private val audioAnalyzer = AudioAnalyzer()
    private var interpreter: Interpreter? = null
    private var listener: ((DetectionResult) -> Unit)? = null

    private val ringBuffer = ArrayDeque<Float>()
    private var chunksSeen = 0

    override val isReady: Boolean get() = interpreter != null

    init {
        try {
            val buffer = loadModelFile(context, DetectionConfig.MODEL_ASSET)
            val options = Interpreter.Options().setNumThreads(4)
            interpreter = Interpreter(buffer, options)
            Log.i(TAG, "TFLite model loaded.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load TFLite model", e)
            interpreter = null
        }
    }

    override fun setListener(listener: ((DetectionResult) -> Unit)?) {
        this.listener = listener
    }

    override fun processAudioWindow(audio: ShortArray) {
        chunksSeen++
        val quality = audioAnalyzer.analyze(audio)

        if (!quality.isAcceptable) {
            val result = currentResult(
                verdict = DetectionConfig.Verdict.UNCERTAIN,
                spoofScore = aggregatedMedian(),
                audioQuality = quality.quality,
                speechRatio = quality.speechRatio,
                reason = quality.rejectionReason
            )
            if (DetectionConfig.ENABLE_DEBUG_LOG) {
                Log.d(TAG, "REJECT chunk #$chunksSeen rms=${quality.rms} dbfs=${quality.dbfs} " +
                    "speech=${quality.speechRatio} clip=${quality.clippingRatio} -> ${result.verdict} (${result.reason})")
            }
            listener?.invoke(result)
            return
        }

        val interp = interpreter
        if (interp == null) {
            val result = currentResult(
                verdict = DetectionConfig.Verdict.UNAVAILABLE,
                spoofScore = null,
                audioQuality = quality.quality,
                speechRatio = quality.speechRatio,
                reason = "inference engine unavailable"
            )
            listener?.invoke(result)
            return
        }

        val mel = audioProcessor.extractMelSpectrogram(audio)
        val inputBuffer = ByteBuffer.allocateDirect(1 * 64 * 188 * 1 * 4)
        inputBuffer.order(ByteOrder.nativeOrder())
        for (m in 0 until 64) {
            for (f in 0 until 188) {
                inputBuffer.putFloat(mel[m][f])
            }
        }
        inputBuffer.rewind()

        val output = Array(1) { FloatArray(1) }
        val rawScore: Float
        try {
            interp.run(inputBuffer, output)
            rawScore = output[0][0]
        } catch (e: Exception) {
            Log.e(TAG, "Inference failed", e)
            val result = currentResult(
                verdict = DetectionConfig.Verdict.UNAVAILABLE,
                spoofScore = null,
                audioQuality = quality.quality,
                speechRatio = quality.speechRatio,
                reason = "inference error: ${e.message}"
            )
            listener?.invoke(result)
            return
        }

        // Convert to probability if it looks like a logit (i.e. outside [0, 1] range)
        val prob = if (rawScore < -0.01f || rawScore > 1.01f) {
            1f / (1f + kotlin.math.exp(-rawScore.toDouble())).toFloat()
        } else {
            rawScore.coerceIn(0f, 1f)
        }
        
        // Invert label mapping: the model likely uses 1=bonafide and 0=spoof,
        // so a high score means real. We need spoofScore where high means spoof.
        val spoofScore = 1f - prob
        ringBuffer.addLast(spoofScore)
        while (ringBuffer.size > DetectionConfig.RING_BUFFER_SIZE) {
            ringBuffer.removeFirst()
        }

        val (verdict, reason) = decide()
        val result = currentResult(
            verdict = verdict,
            spoofScore = aggregatedMedian(),
            audioQuality = quality.quality,
            speechRatio = quality.speechRatio,
            reason = reason
        )

        if (DetectionConfig.ENABLE_DEBUG_LOG) {
            Log.d(TAG, "chunk #$chunksSeen raw=${"%.5f".format(rawScore)} spoof=${"%.5f".format(spoofScore)} " +
                "dbfs=${"%.1f".format(quality.dbfs)} speech=${"%.2f".format(quality.speechRatio)} " +
                "zcr=${"%.4f".format(quality.zeroCrossingRate)} clip=${"%.3f".format(quality.clippingRatio)} " +
                "nValid=${ringBuffer.size} median=${"%.4f".format(aggregatedMedian() ?: -1f)} " +
                "var=${"%.2e".format(variance())} -> ${result.verdict}")
        }

        listener?.invoke(result)
    }

    private fun decide(): Pair<String, String?> {
        val nValid = ringBuffer.size
        val median = aggregatedMedian() ?: return DetectionConfig.Verdict.UNCERTAIN to "no clean speech captured yet"
        val agreeHigh = fraction { it >= DetectionConfig.CLONED_MIN_SCORE }
        val agreeLow = fraction { it < DetectionConfig.REAL_MAX_SCORE }
        val scoreVariance = variance()

        if (nValid < DetectionConfig.MIN_CHUNKS_FOR_VERDICT) {
            return DetectionConfig.Verdict.UNCERTAIN to "collecting evidence ($nValid/${DetectionConfig.MIN_CHUNKS_FOR_VERDICT})"
        }
        if (median < DetectionConfig.REAL_MAX_SCORE &&
            agreeLow >= DetectionConfig.AGREEMENT_RATIO &&
            nValid >= DetectionConfig.MIN_CHUNKS_FOR_REAL) {
            return DetectionConfig.Verdict.REAL to null
        }
        if (median >= DetectionConfig.CLONED_MIN_SCORE &&
            nValid >= DetectionConfig.MIN_CHUNKS_FOR_CLONED &&
            agreeHigh >= DetectionConfig.AGREEMENT_RATIO) {
            if (scoreVariance <= DetectionConfig.SATURATION_VARIANCE_FLOOR) {
                return DetectionConfig.Verdict.SUSPICIOUS to "model output saturated; cannot confirm clone without discrimination"
            }
            return DetectionConfig.Verdict.CLONED to null
        }
        if (median >= DetectionConfig.SUSPICIOUS_MIN_SCORE) {
            return DetectionConfig.Verdict.SUSPICIOUS to "partial spoof evidence (score ${"%.2f".format(median)})"
        }
        return DetectionConfig.Verdict.UNCERTAIN to "conflicting evidence across chunks"
    }

    private fun currentResult(
        verdict: String,
        spoofScore: Float?,
        audioQuality: String,
        speechRatio: Float,
        reason: String?
    ): DetectionResult {
        val nValid = ringBuffer.size
        val fill = (nValid.toFloat() / DetectionConfig.RING_BUFFER_SIZE).coerceIn(0f, 1f)
        val evidence: Float = when (verdict) {
            DetectionConfig.Verdict.REAL -> fill * fraction { it < DetectionConfig.REAL_MAX_SCORE }
            DetectionConfig.Verdict.CLONED -> fill * fraction { it >= DetectionConfig.CLONED_MIN_SCORE }
            DetectionConfig.Verdict.SUSPICIOUS -> fill * 0.5f
            else -> fill * 0.25f
        }
        return DetectionResult(
            verdict = verdict,
            spoofScore = spoofScore,
            evidence = evidence.coerceIn(0f, 1f),
            audioQuality = audioQuality,
            speechRatio = speechRatio,
            chunksAnalyzed = nValid,
            chunksSeen = chunksSeen,
            reason = reason
        )
    }

    private fun aggregatedMedian(): Float? {
        if (ringBuffer.isEmpty()) return null
        val arr = FloatArray(ringBuffer.size)
        var i = 0
        for (v in ringBuffer) arr[i++] = v
        arr.sort()
        val mid = arr.size / 2
        return if (arr.size % 2 == 1) arr[mid] else (arr[mid - 1] + arr[mid]) / 2f
    }

    private fun fraction(predicate: (Float) -> Boolean): Float {
        if (ringBuffer.isEmpty()) return 0f
        val c = ringBuffer.count(predicate)
        return c.toFloat() / ringBuffer.size
    }

    private fun variance(): Float {
        if (ringBuffer.size < 2) return 0f
        var mean = 0f
        for (v in ringBuffer) mean += v
        mean /= ringBuffer.size
        var s = 0f
        for (v in ringBuffer) {
            val d = v - mean
            s += d * d
        }
        return s / ringBuffer.size
    }

    override fun reset() {
        ringBuffer.clear()
        chunksSeen = 0
    }

    override fun close() {
        interpreter?.close()
        interpreter = null
    }

    private fun loadModelFile(context: Context, modelName: String): MappedByteBuffer {
        val fd = context.assets.openFd(modelName)
        FileInputStream(fd.fileDescriptor).use { input ->
            return input.channel.map(
                FileChannel.MapMode.READ_ONLY,
                fd.startOffset,
                fd.declaredLength
            )
        }
    }

    companion object {
        private const val TAG = "OnDeviceDetectionEngine"
    }
}

class FastApiDetectionEngine(private val baseUrl: String = "") : DetectionEngine {
    override val isReady: Boolean get() = false
    override fun setListener(listener: ((DetectionResult) -> Unit)?) {}
    override fun processAudioWindow(audio: ShortArray) {}
    override fun reset() {}
    override fun close() {}
}
