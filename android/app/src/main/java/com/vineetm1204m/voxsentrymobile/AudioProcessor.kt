package com.vineetm1204m.voxsentrymobile

import kotlin.math.*

class AudioProcessor(
    private val sampleRate: Int = 16000,
    private val nFFT: Int = 512,
    private val hopLength: Int = 212,
    private val nMels: Int = 64
) {

    private val melBasis: Array<FloatArray> = createMelFilterbank()

    fun extractMelSpectrogram(audioData: ShortArray): Array<FloatArray> {
        val numFrames = 188
        val spectrogram = Array(nMels) { FloatArray(numFrames) }

        // Normalize
        val floatAudio = FloatArray(audioData.size) { audioData[it] / 32768.0f }

        for (i in 0 until numFrames) {
            val start = i * hopLength
            if (start + nFFT > floatAudio.size) break

            val frame = floatAudio.sliceArray(start until (start + nFFT))
            
            // 1. Hanning Window
            for (j in frame.indices) {
                frame[j] *= (0.5 * (1 - cos(2.0 * PI * j / (nFFT - 1)))).toFloat()
            }

            // 2. FFT
            val fftRe = FloatArray(nFFT)
            val fftIm = FloatArray(nFFT)
            System.arraycopy(frame, 0, fftRe, 0, nFFT)
            
            fft(fftRe, fftIm)

            // 3. Power Spectrum
            val powerSpectrum = FloatArray(nFFT / 2 + 1)
            for (j in 0 until (nFFT / 2 + 1)) {
                powerSpectrum[j] = (fftRe[j] * fftRe[j] + fftIm[j] * fftIm[j]) / nFFT
            }

            // 4. Mel Filterbank
            for (m in 0 until nMels) {
                var melValue = 0f
                for (k in 0 until (nFFT / 2 + 1)) {
                    melValue += powerSpectrum[k] * melBasis[m][k]
                }
                // 5. Log scaling
                spectrogram[m][i] = 10 * log10(max(1e-10f, melValue))
            }
        }

        return spectrogram
    }

    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                val tempRe = re[i]
                re[i] = re[j]
                re[j] = tempRe
                val tempIm = im[i]
                im[i] = im[j]
                im[j] = tempIm
            }
            var m = n shr 1
            while (m >= 1 && j >= m) {
                j -= m
                m = m shr 1
            }
            j += m
        }

        var length = 2
        while (length <= n) {
            val angle = -2.0 * PI / length
            val wRe = cos(angle).toFloat()
            val wIm = sin(angle).toFloat()
            for (i in 0 until n step length) {
                var vRe = 1.0f
                var vIm = 0.0f
                for (k in 0 until length / 2) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val tRe = vRe * re[i + k + length / 2] - vIm * im[i + k + length / 2]
                    val tIm = vRe * im[i + k + length / 2] + vIm * re[i + k + length / 2]
                    re[i + k] = uRe + tRe
                    im[i + k] = uIm + tIm
                    re[i + k + length / 2] = uRe - tRe
                    im[i + k + length / 2] = uIm - tIm
                    val nextVRe = vRe * wRe - vIm * wIm
                    vIm = vRe * wIm + vIm * wRe
                    vRe = nextVRe
                }
            }
            length *= 2
        }
    }

    private fun createMelFilterbank(): Array<FloatArray> {
        val melBasis = Array(nMels) { FloatArray(nFFT / 2 + 1) }
        val minHz = 0f
        val maxHz = sampleRate / 2f
        
        val minMel = 2595f * log10(1f + minHz / 700f)
        val maxMel = 2595f * log10(1f + maxHz / 700f)
        
        val melPoints = FloatArray(nMels + 2) { i ->
            minMel + i * (maxMel - minMel) / (nMels + 1)
        }
        
        val hzPoints = FloatArray(nMels + 2) { i ->
            700f * (10.0.pow(melPoints[i] / 2595.0) - 1).toFloat()
        }
        
        val binPoints = IntArray(nMels + 2) { i ->
            ((nFFT + 1) * hzPoints[i] / sampleRate).toInt()
        }
        
        for (m in 1..nMels) {
            for (k in binPoints[m - 1] until binPoints[m]) {
                if (k < melBasis[m - 1].size) {
                    melBasis[m - 1][k] = (k - binPoints[m - 1]).toFloat() / (binPoints[m] - binPoints[m - 1])
                }
            }
            for (k in binPoints[m] until binPoints[m + 1]) {
                if (k < melBasis[m - 1].size) {
                    melBasis[m - 1][k] = (binPoints[m + 1] - k).toFloat() / (binPoints[m + 1] - binPoints[m])
                }
            }
        }
        return melBasis
    }
}
