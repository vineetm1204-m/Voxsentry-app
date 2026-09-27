package com.vineetm1204m.voxsentrymobile

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

class AudioCaptureManager {

    interface AudioCaptureListener {
        fun onAudioWindowReady(audioData: ShortArray)
        fun onCaptureError(error: String)
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingThread: Thread? = null
    private var listener: AudioCaptureListener? = null

    // 16kHz mono
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    // 2.5s window, 0.5s stride
    private val windowSizeSeconds = 2.5f
    private val strideSeconds = 0.5f

    private val windowSizeSamples = (sampleRate * windowSizeSeconds).toInt()
    private val strideSamples = (sampleRate * strideSeconds).toInt()

    fun setListener(newListener: AudioCaptureListener?) {
        listener = newListener
    }

    @SuppressLint("MissingPermission")
    fun startCapture() {
        if (isRecording) return

        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            listener?.onCaptureError("AudioRecord unsupported configuration")
            return
        }

        try {
            // Try VOICE_COMMUNICATION first for VoIP/speakerphone echo cancellation, fallback to MIC
            var audioSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION
            audioRecord = AudioRecord(audioSource, sampleRate, channelConfig, audioFormat, Math.max(minBufferSize, windowSizeSamples * 2))
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioSource = MediaRecorder.AudioSource.MIC
                audioRecord = AudioRecord(audioSource, sampleRate, channelConfig, audioFormat, Math.max(minBufferSize, windowSizeSamples * 2))
            }

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                listener?.onCaptureError("Failed to initialize AudioRecord")
                return
            }

            audioRecord?.startRecording()
            isRecording = true

            recordingThread = Thread {
                processAudioStream()
            }
            recordingThread?.start()

        } catch (e: Exception) {
            Log.e("AudioCaptureManager", "Error starting capture", e)
            listener?.onCaptureError("Exception: ${e.message}")
        }
    }

    private fun processAudioStream() {
        val buffer = ShortArray(strideSamples) // Read in chunks of stride
        val rollingWindow = ShortArray(windowSizeSamples)
        var samplesInWindow = 0

        while (isRecording && audioRecord != null) {
            val readResult = audioRecord?.read(buffer, 0, buffer.size) ?: -1
            if (readResult > 0) {
                if (samplesInWindow < windowSizeSamples) {
                    // Fill up the initial window
                    val spaceLeft = windowSizeSamples - samplesInWindow
                    val toCopy = Math.min(spaceLeft, readResult)
                    System.arraycopy(buffer, 0, rollingWindow, samplesInWindow, toCopy)
                    samplesInWindow += toCopy
                } 
                
                if (samplesInWindow == windowSizeSamples) {
                    // Window is full, we can dispatch it
                    val windowCopy = ShortArray(windowSizeSamples)
                    System.arraycopy(rollingWindow, 0, windowCopy, 0, windowSizeSamples)
                    listener?.onAudioWindowReady(windowCopy)

                    // Shift rolling window by stride
                    val remainingSamples = windowSizeSamples - strideSamples
                    System.arraycopy(rollingWindow, strideSamples, rollingWindow, 0, remainingSamples)
                    samplesInWindow = remainingSamples

                    // We still need to add any remainder from the read buffer if we had spaceLeft < readResult
                    // but since read buffer == strideSamples, it aligns perfectly after initial fill.
                    if (readResult == strideSamples) {
                        System.arraycopy(buffer, 0, rollingWindow, remainingSamples, strideSamples)
                        samplesInWindow += strideSamples
                    }
                }
            } else if (readResult < 0) {
                Log.e("AudioCaptureManager", "AudioRecord read error: $readResult")
                listener?.onCaptureError("AudioRecord read error: $readResult")
                break
            }
        }
    }

    fun stopCapture() {
        isRecording = false
        try {
            recordingThread?.join(1000)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
        recordingThread = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        audioRecord = null
    }
}
