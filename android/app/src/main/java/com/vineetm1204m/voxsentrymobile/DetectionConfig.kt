package com.vineetm1204m.voxsentrymobile

object DetectionConfig {
    const val SAMPLE_RATE = 16000
    const val WINDOW_SECONDS = 2.5f
    const val STRIDE_SECONDS = 0.5f
    const val WINDOW_SAMPLES = (SAMPLE_RATE * WINDOW_SECONDS).toInt()
    const val STRIDE_SAMPLES = (SAMPLE_RATE * STRIDE_SECONDS).toInt()

    const val MIN_DURATION_SECONDS = 1.0f
    val MIN_DURATION_SAMPLES = (SAMPLE_RATE * MIN_DURATION_SECONDS).toInt()

    const val MEL_N_FFT = 512
    const val MEL_HOP = 212
    const val MEL_N_MELS = 64
    const val MEL_N_FRAMES = 188

    const val MODEL_ASSET = "voice_clone_detector_hindi.tflite"

    const val DBFS_FLOOR = -55.0f
    const val DBFS_CEILING = -3.0f
    const val CLIPPING_THRESHOLD = 0.985f
    const val MAX_CLIPPING_RATIO = 0.10f
    const val SILENCE_DBFS = -65.0f
    const val MIN_SPEECH_RATIO = 0.30f
    const val MAX_SILENCE_RATIO = 0.85f

    const val RING_BUFFER_SIZE = 8
    const val MIN_CHUNKS_FOR_VERDICT = 2
    const val MIN_CHUNKS_FOR_CLONED = 4
    const val MIN_CHUNKS_FOR_REAL = 3
    const val AGREEMENT_RATIO = 0.6f

    const val REAL_MAX_SCORE = 0.40f
    const val SUSPICIOUS_MIN_SCORE = 0.45f
    const val CLONED_MIN_SCORE = 0.70f

    const val SATURATION_VARIANCE_FLOOR = 1e-5f

    const val ENABLE_DEBUG_LOG = true

    object Quality {
        const val GOOD = "good"
        const val FAIR = "fair"
        const val POOR = "poor"
    }

    object Verdict {
        const val REAL = "real"
        const val SUSPICIOUS = "suspicious"
        const val CLONED = "cloned"
        const val UNCERTAIN = "uncertain"
        const val UNAVAILABLE = "unavailable"
    }
}
