package com.vineetm1204m.voxsentrymobile

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.LinkedList

class InferenceEngine(private val context: Context) {

    interface InferenceListener {
        fun onDetectionResult(isThreat: Boolean, confidence: Float)
    }

    private var listener: InferenceListener? = null
    private var interpreter: Interpreter? = null
    private val audioProcessor = AudioProcessor()

    private val resultBuffer = LinkedList<Boolean>()
    private val bufferSize = 5
    private val THREAT_THRESHOLD = 0.5f

    init {
        try {
            val modelBuffer = loadModelFile(context, "voice_clone_detector_hindi.tflite")
            val options = Interpreter.Options()
            options.setNumThreads(4)
            interpreter = Interpreter(modelBuffer, options)
            Log.i("InferenceEngine", "TFLite model loaded successfully.")
        } catch (e: Exception) {
            Log.e("InferenceEngine", "Error loading TFLite model", e)
        }
    }

    private fun loadModelFile(context: Context, modelName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    fun setListener(newListener: InferenceListener?) {
        listener = newListener
    }

    fun processAudioWindow(audioData: ShortArray) {
        val interp = interpreter ?: return

        // 1. Extract Mel Spectrogram [64, 188]
        val melData = audioProcessor.extractMelSpectrogram(audioData)
        
        // 2. Prepare Input ByteBuffer [1, 64, 188, 1] * Float32 (4 bytes)
        val inputBuffer = ByteBuffer.allocateDirect(1 * 64 * 188 * 1 * 4)
        inputBuffer.order(ByteOrder.nativeOrder())
        
        for (m in 0 until 64) {
            for (f in 0 until 188) {
                inputBuffer.putFloat(melData[m][f])
            }
        }
        inputBuffer.rewind()

        // 3. Run Inference
        val output = Array(1) { FloatArray(1) }
        try {
            interp.run(inputBuffer, output)
        } catch (e: Exception) {
            Log.e("InferenceEngine", "Inference failed", e)
            return
        }

        val rawConfidence = output[0][0]
        
        // Model-specific interpretation:
        // Usually, 0.5 is the midpoint. If it consistently gives 0.99 for everything,
        // it might mean the model thinks everything is Cloned (if 1.0 = Cloned)
        // or it might mean we are passing the data in the wrong range.
        
        val isThreat = rawConfidence > THREAT_THRESHOLD

        Log.d("InferenceEngine", "Model raw output: $rawConfidence")

        // 4. Smoothing
        resultBuffer.addLast(isThreat)
        if (resultBuffer.size > bufferSize) {
            resultBuffer.removeFirst()
        }

        val threatCount = resultBuffer.count { it }
        val smoothedIsThreat = threatCount > (resultBuffer.size / 2)

        listener?.onDetectionResult(smoothedIsThreat, rawConfidence)
    }

    fun reset() {
        resultBuffer.clear()
    }
    
    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
