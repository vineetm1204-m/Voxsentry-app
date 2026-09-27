package com.vineetm1204m.voxsentrymobile

import kotlin.math.log10
import kotlin.math.sqrt

data class AudioQuality(
    val rms: Float,
    val dbfs: Float,
    val peak: Float,
    val clippingRatio: Float,
    val silenceRatio: Float,
    val speechRatio: Float,
    val zeroCrossingRate: Float,
    val isAcceptable: Boolean,
    val quality: String,
    val rejectionReason: String?
)

class AudioAnalyzer(private val sampleRate: Int = DetectionConfig.SAMPLE_RATE) {

    fun analyze(audio: ShortArray): AudioQuality {
        if (audio.isEmpty()) {
            return AudioQuality(
                0f, Float.NEGATIVE_INFINITY, 0f, 0f, 1f, 0f, 0f,
                false, DetectionConfig.Quality.POOR, "empty audio"
            )
        }

        var sumSq = 0.0
        var peak = 0f
        var clipCount = 0
        for (s in audio) {
            val v = s.toFloat() / 32768.0f
            val a = if (v < 0) -v else v
            if (a > peak) peak = a
            sumSq += (v * v).toDouble()
            if (a >= DetectionConfig.CLIPPING_THRESHOLD) clipCount++
        }
        val rms = sqrt(sumSq / audio.size).toFloat()
        val dbfs = if (rms > 0f) (20f * log10(rms)).toFloat() else Float.NEGATIVE_INFINITY
        val clippingRatio = clipCount.toFloat() / audio.size

        val frameLen = (sampleRate * 0.02f).toInt().coerceAtLeast(1)
        val nFrames = audio.size / frameLen
        val silenceThreshold = fromDbfs(DetectionConfig.SILENCE_DBFS)
        var silentFrames = 0
        var zcTotal = 0
        for (i in 0 until nFrames) {
            val off = i * frameLen
            var frameSumSq = 0.0
            var zc = 0
            var prevSign = 0
            for (j in 0 until frameLen) {
                val v = audio[off + j].toFloat() / 32768.0f
                frameSumSq += (v * v).toDouble()
                val sign = if (v > 0) 1 else if (v < 0) -1 else 0
                if (sign != 0 && prevSign != 0 && sign != prevSign) zc++
                if (sign != 0) prevSign = sign
            }
            val frameRms = sqrt(frameSumSq / frameLen).toFloat()
            if (frameRms < silenceThreshold) silentFrames++
            zcTotal += zc
        }
        val silenceRatio = if (nFrames > 0) silentFrames.toFloat() / nFrames else 1f
        val speechRatio = 1f - silenceRatio
        val zeroCrossingRate = if (nFrames > 0) (zcTotal.toFloat() / (nFrames * frameLen)) else 0f

        val tooShort = audio.size < DetectionConfig.MIN_DURATION_SAMPLES
        val tooQuiet = dbfs < DetectionConfig.DBFS_FLOOR
        val tooLoud = dbfs > DetectionConfig.DBFS_CEILING && clippingRatio > DetectionConfig.MAX_CLIPPING_RATIO
        val tooMuchClipping = clippingRatio > DetectionConfig.MAX_CLIPPING_RATIO
        val tooSilent = speechRatio < DetectionConfig.MIN_SPEECH_RATIO || silenceRatio > DetectionConfig.MAX_SILENCE_RATIO

        val reason = when {
            tooShort -> "audio too short (${audio.size} samples)"
            tooQuiet -> "signal too quiet (${dbfs} dBFS)"
            tooMuchClipping || tooLoud -> "clipping detected (${(clippingRatio * 100).toInt()}%)"
            tooSilent -> "insufficient clean speech (${(speechRatio * 100).toInt()}%)"
            else -> null
        }

        val quality = when {
            reason != null -> DetectionConfig.Quality.POOR
            dbfs < DetectionConfig.DBFS_FLOOR + 10f -> DetectionConfig.Quality.FAIR
            else -> DetectionConfig.Quality.GOOD
        }
        val acceptable = reason == null

        return AudioQuality(
            rms = rms,
            dbfs = dbfs,
            peak = peak,
            clippingRatio = clippingRatio,
            silenceRatio = silenceRatio,
            speechRatio = speechRatio,
            zeroCrossingRate = zeroCrossingRate,
            isAcceptable = acceptable,
            quality = quality,
            rejectionReason = reason
        )
    }

    private fun fromDbfs(dbfs: Float): Float {
        return Math.pow(10.0, dbfs / 20.0).toFloat()
    }
}
