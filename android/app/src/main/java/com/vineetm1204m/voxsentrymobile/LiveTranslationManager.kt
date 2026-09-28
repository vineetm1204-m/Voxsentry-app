package com.vineetm1204m.voxsentrymobile

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

class LiveTranslationManager(private val context: Context) {

    enum class State { OFF, STARTING, LISTENING, TRANSCRIBING, TRANSLATING, PAUSED, ERROR, UNAVAILABLE }

    interface TranslationListener {
        fun onStateChanged(state: State)
        fun onPartialTranscript(sourceText: String, targetText: String?)
        fun onFinalTranscript(sourceText: String, targetText: String?)
        fun onError(error: String)
    }

    private var state = State.OFF
    private var listener: TranslationListener? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var translator: Translator? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private var isTranslationEnabled = false
    private var sourceLangCode = "en"
    private var targetLangCode = "hi"
    
    private var lastStableSource = ""
    
    fun setListener(newListener: TranslationListener?) {
        listener = newListener
    }

    fun setLanguages(source: String, target: String) {
        val mappedSource = mapLanguageCode(source)
        val mappedTarget = mapLanguageCode(target)
        if (sourceLangCode != mappedSource || targetLangCode != mappedTarget) {
            sourceLangCode = mappedSource
            targetLangCode = mappedTarget
            if (isTranslationEnabled) {
                // Restart to apply new languages
                stopTranslation()
                startTranslation()
            }
        }
    }

    private fun mapLanguageCode(code: String): String {
        return when (code.lowercase()) {
            "english", "en" -> TranslateLanguage.ENGLISH
            "hindi", "hi" -> TranslateLanguage.HINDI
            "bengali", "bn" -> TranslateLanguage.BENGALI
            "marathi", "mr" -> TranslateLanguage.MARATHI
            "tamil", "ta" -> TranslateLanguage.TAMIL
            "telugu", "te" -> TranslateLanguage.TELUGU
            "gujarati", "gu" -> TranslateLanguage.GUJARATI
            "kannada", "kn" -> TranslateLanguage.KANNADA
            "malayalam", "ml" -> TranslateLanguage.MALAYALAM
            else -> TranslateLanguage.ENGLISH // default fallback
        }
    }
    
    private fun getSpeechLocale(mlKitLang: String): String {
        return when (mlKitLang) {
            TranslateLanguage.ENGLISH -> "en-US"
            TranslateLanguage.HINDI -> "hi-IN"
            TranslateLanguage.BENGALI -> "bn-IN"
            TranslateLanguage.MARATHI -> "mr-IN"
            TranslateLanguage.TAMIL -> "ta-IN"
            TranslateLanguage.TELUGU -> "te-IN"
            TranslateLanguage.GUJARATI -> "gu-IN"
            TranslateLanguage.KANNADA -> "kn-IN"
            TranslateLanguage.MALAYALAM -> "ml-IN"
            else -> "en-US"
        }
    }

    fun startTranslation() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            updateState(State.UNAVAILABLE)
            listener?.onError("Speech recognition unavailable on this device.")
            return
        }

        isTranslationEnabled = true
        updateState(State.STARTING)
        lastStableSource = ""

        // Setup ML Kit Translator
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceLangCode)
            .setTargetLanguage(targetLangCode)
            .build()

        translator?.close()
        translator = Translation.getClient(options)
        
        val conditions = DownloadConditions.Builder().build()
        translator?.downloadModelIfNeeded(conditions)
            ?.addOnSuccessListener {
                if (isTranslationEnabled) {
                    startSpeechRecognizer()
                }
            }
            ?.addOnFailureListener { e ->
                updateState(State.ERROR)
                listener?.onError("Failed to load offline translation model: ${e.message}")
            }
    }

    private fun startSpeechRecognizer() {
        mainHandler.post {
            try {
                if (speechRecognizer != null) {
                    speechRecognizer?.destroy()
                }
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        updateState(State.LISTENING)
                    }

                    override fun onBeginningOfSpeech() {
                        updateState(State.TRANSCRIBING)
                    }

                    override fun onRmsChanged(rmsdB: Float) {}
                    
                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        // Will restart after results if still enabled
                        updateState(State.PAUSED)
                    }

                    override fun onError(error: Int) {
                        Log.e("LiveTranslationManager", "Speech recognition error: $error")
                        // If network or timeout, just restart
                        if (isTranslationEnabled) {
                            mainHandler.postDelayed({
                                if (isTranslationEnabled) startSpeechRecognizer()
                            }, 500)
                        } else {
                            updateState(State.ERROR)
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            val finalSpeech = matches[0]
                            translateText(finalSpeech, true)
                        }
                        // Continue listening if still enabled
                        if (isTranslationEnabled) {
                            mainHandler.postDelayed({
                                if (isTranslationEnabled) startSpeechRecognizer()
                            }, 100)
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            val partialSpeech = matches[0]
                            translateText(partialSpeech, false)
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, getSpeechLocale(sourceLangCode))
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    // Prefer offline recognition if available (API 23+)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    }
                }
                speechRecognizer?.startListening(intent)
            } catch (e: Exception) {
                Log.e("LiveTranslationManager", "Error starting speech recognizer", e)
                updateState(State.ERROR)
                listener?.onError("Failed to start listening: ${e.message}")
            }
        }
    }

    private fun translateText(text: String, isFinal: Boolean) {
        if (text.isBlank()) return
        
        translator?.translate(text)
            ?.addOnSuccessListener { translatedText ->
                if (isFinal) {
                    listener?.onFinalTranscript(text, translatedText)
                } else {
                    listener?.onPartialTranscript(text, translatedText)
                }
            }
            ?.addOnFailureListener {
                // Fallback to original text if translation fails
                if (isFinal) {
                    listener?.onFinalTranscript(text, null)
                } else {
                    listener?.onPartialTranscript(text, null)
                }
            }
    }

    fun stopTranslation() {
        isTranslationEnabled = false
        updateState(State.OFF)
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.destroy()
                speechRecognizer = null
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    fun release() {
        stopTranslation()
        translator?.close()
        translator = null
    }

    private fun updateState(newState: State) {
        if (state != newState) {
            state = newState
            listener?.onStateChanged(newState)
        }
    }
}
