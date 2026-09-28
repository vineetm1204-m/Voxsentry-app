package com.vineetm1204m.voxsentrymobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.app.NotificationCompat
import android.content.SharedPreferences

class ProtectionService : Service(), CallSessionManager.CallStateListener, AudioCaptureManager.AudioCaptureListener, LiveTranslationManager.TranslationListener {

    private lateinit var windowManager: WindowManager
    private var containerView: FrameLayout? = null
    private var collapsedView: View? = null
    private var expandedView: View? = null

    private var statusText: TextView? = null
    private var resultText: TextView? = null
    private var subText: TextView? = null
    private var progressBar: ProgressBar? = null
    private var shieldIcon: ImageView? = null
    private var closeButton: ImageView? = null

    private val CHANNEL_ID = "VoxSentryProtectionChannel"

    private lateinit var audioManager: AudioManager
    private lateinit var vibrator: Vibrator

    private val audioCaptureManager = AudioCaptureManager()
    private lateinit var detectionEngine: DetectionEngine

    private lateinit var translationManager: LiveTranslationManager
    
    private var translationContainer: LinearLayout? = null
    private var translationToggleBtn: TextView? = null
    private var translationLangBtn: TextView? = null
    private var translationOriginalText: TextView? = null
    private var translationTranslatedText: TextView? = null
    
    private var isTranslationActive = false
    private val supportedLangs = listOf("EN", "HI", "BN", "MR", "TA", "TE", "GU", "KN", "ML")
    private var sourceLangIndex = 0
    private var targetLangIndex = 1
    
    private lateinit var prefs: SharedPreferences

    private var isCallCurrentlyActive = false
    private var isSpeakerOn = false
    private var isExpanded = false

    private lateinit var historyStore: HistoryStore
    private var callStartTime: Long = 0
    private var peakScore: Float = 0f
    private var peakVerdict: String = DetectionConfig.Verdict.UNCERTAIN
    private var lastResult: DetectionResult? = null
    private var currentCallType: String = "native"

    enum class OverlayState { WAITING, ANALYZING, REAL, SUSPICIOUS, CLONED, UNCERTAIN, UNAVAILABLE }

    private var currentState = OverlayState.WAITING

    private val audioRouteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_HEADSET_PLUG || intent?.action == AudioManager.ACTION_SPEAKERPHONE_STATE_CHANGED) {
                checkSpeakerState()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        historyStore = HistoryStore(this)
        
        prefs = getSharedPreferences("voxsentry.translation", Context.MODE_PRIVATE)
        sourceLangIndex = prefs.getInt("sourceLangIndex", 0)
        targetLangIndex = prefs.getInt("targetLangIndex", 1)

        detectionEngine = OnDeviceDetectionEngine(this)
        detectionEngine.setListener { result -> onDetectionResult(result) }
        audioCaptureManager.setListener(this)
        
        translationManager = LiveTranslationManager(this)
        translationManager.setListener(this)
        updateTranslationLangs()

        CallSessionManager.startNativeMonitoring(this)
        CallSessionManager.setListener(this)

        val filter = IntentFilter()
        filter.addAction(AudioManager.ACTION_HEADSET_PLUG)
        filter.addAction(AudioManager.ACTION_SPEAKERPHONE_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(audioRouteReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(audioRouteReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VoxSentry Active")
            .setContentText("Monitoring calls for synthetic voices...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notification)
        }
        return START_STICKY
    }

    override fun onCallStateChanged(isActive: Boolean) {
        isCallCurrentlyActive = isActive
        if (isActive) {
            callStartTime = System.currentTimeMillis()
            peakScore = 0f
            peakVerdict = DetectionConfig.Verdict.UNCERTAIN
            lastResult = null
            showOverlay()
            checkSpeakerState()
        } else {
            if (callStartTime > 0) {
                val duration = System.currentTimeMillis() - callStartTime
                historyStore.addRecord(
                    CallRecord(
                        id = java.util.UUID.randomUUID().toString(),
                        timestamp = callStartTime,
                        duration = duration,
                        finalStatus = peakVerdict,
                        maxConfidence = peakScore,
                        callType = currentCallType,
                        reason = lastResult?.reason
                    )
                )
            }
            hideOverlay()
            audioCaptureManager.stopCapture()
            detectionEngine.reset()
            
            translationManager.stopTranslation()
            isTranslationActive = false
            
            callStartTime = 0
        }
    }

    private fun updateTranslationLangs() {
        translationManager.setLanguages(supportedLangs[sourceLangIndex], supportedLangs[targetLangIndex])
        translationLangBtn?.text = "${supportedLangs[sourceLangIndex]} → ${supportedLangs[targetLangIndex]}"
    }

    private fun checkSpeakerState() {
        if (!isCallCurrentlyActive) return

        isSpeakerOn = audioManager.isSpeakerphoneOn

        if (isSpeakerOn) {
            updateOverlayState(OverlayState.ANALYZING)
            audioCaptureManager.startCapture()
            broadcastEvent("onCaptureStarted", null)
        } else {
            updateOverlayState(OverlayState.WAITING)
            audioCaptureManager.stopCapture()
            broadcastEvent("onSpeakerRequired", null)
        }
    }

    override fun onAudioWindowReady(audioData: ShortArray) {
        detectionEngine.processAudioWindow(audioData)
    }

    override fun onCaptureError(error: String) {
        updateOverlayState(OverlayState.UNAVAILABLE)
        val result = DetectionResult(
            verdict = DetectionConfig.Verdict.UNAVAILABLE,
            spoofScore = null,
            evidence = 0f,
            audioQuality = DetectionConfig.Quality.POOR,
            speechRatio = 0f,
            chunksAnalyzed = 0,
            chunksSeen = 0,
            reason = "capture error: $error"
        )
        lastResult = result
        broadcastEvent("onDetectionUpdate", result.toJson())
    }

    private fun onDetectionResult(result: DetectionResult) {
        lastResult = result
        if (result.spoofScore != null && result.spoofScore > peakScore) {
            peakScore = result.spoofScore
        }
        val severity = mapOf(
            DetectionConfig.Verdict.REAL to 0,
            DetectionConfig.Verdict.UNCERTAIN to 1,
            DetectionConfig.Verdict.SUSPICIOUS to 2,
            DetectionConfig.Verdict.CLONED to 3,
            DetectionConfig.Verdict.UNAVAILABLE to -1
        )
        if ((severity[result.verdict] ?: -1) > (severity[peakVerdict] ?: -1)) {
            peakVerdict = result.verdict
        }

        val newState = when (result.verdict) {
            DetectionConfig.Verdict.REAL -> OverlayState.REAL
            DetectionConfig.Verdict.SUSPICIOUS -> OverlayState.SUSPICIOUS
            DetectionConfig.Verdict.CLONED -> OverlayState.CLONED
            DetectionConfig.Verdict.UNCERTAIN -> OverlayState.UNCERTAIN
            DetectionConfig.Verdict.UNAVAILABLE -> OverlayState.UNAVAILABLE
            else -> OverlayState.UNCERTAIN
        }
        updateOverlayState(newState, result)

        broadcastEvent("onDetectionUpdate", result.toJson())
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics).toInt()
    }

    private fun createCircularBackground(color: Int): GradientDrawable {
        val shape = GradientDrawable()
        shape.shape = GradientDrawable.OVAL
        shape.setColor(color)
        return shape
    }

    private fun createRectangularBackground(color: Int, radiusDp: Int): GradientDrawable {
        val shape = GradientDrawable()
        shape.shape = GradientDrawable.RECTANGLE
        shape.setColor(color)
        shape.cornerRadius = dpToPx(radiusDp).toFloat()
        return shape
    }

    private fun updateOverlayState(state: OverlayState, result: DetectionResult? = null) {
        if (containerView == null) return

        currentState = state
        val res = result ?: lastResult

        containerView?.post {
            when (state) {
                OverlayState.WAITING -> {
                    statusText?.text = "Waiting for speaker..."
                    resultText?.text = "Enable speaker to analyze"
                    resultText?.setTextColor(Color.WHITE)
                    subText?.text = ""
                    progressBar?.visibility = View.GONE
                    shieldIcon?.setColorFilter(Color.GRAY)
                }
                OverlayState.ANALYZING -> {
                    statusText?.text = "Analyzing voice"
                    resultText?.text = "Listening for patterns..."
                    resultText?.setTextColor(Color.parseColor("#2DD4E8"))
                    subText?.text = ""
                    progressBar?.visibility = View.VISIBLE
                    shieldIcon?.setColorFilter(Color.parseColor("#2DD4E8"))
                }
                OverlayState.REAL -> {
                    statusText?.text = "Voice appears genuine"
                    resultText?.text = "No synthetic-voice evidence"
                    resultText?.setTextColor(Color.parseColor("#10B981"))
                    subText?.text = evidenceLine(res, false)
                    progressBar?.visibility = View.GONE
                    shieldIcon?.setColorFilter(Color.parseColor("#10B981"))
                }
                OverlayState.SUSPICIOUS -> {
                    statusText?.text = "Suspicious voice pattern"
                    resultText?.text = "Some synthetic indicators"
                    resultText?.setTextColor(Color.parseColor("#FBBF24"))
                    subText?.text = evidenceLine(res, false)
                    progressBar?.visibility = View.GONE
                    shieldIcon?.setColorFilter(Color.parseColor("#FBBF24"))
                }
                OverlayState.CLONED -> {
                    statusText?.text = "Synthetic voice detected"
                    resultText?.text = detectionScoreLine(res)
                    resultText?.setTextColor(Color.parseColor("#EF4444"))
                    subText?.text = evidenceLine(res, true)
                    progressBar?.visibility = View.GONE
                    shieldIcon?.setColorFilter(Color.parseColor("#EF4444"))
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(400)
                    }
                }
                OverlayState.UNCERTAIN -> {
                    statusText?.text = "Unable to determine reliably"
                    resultText?.text = "Analysis uncertain"
                    resultText?.setTextColor(Color.parseColor("#9BA3B8"))
                    subText?.text = res?.reason ?: "Move to a quieter environment"
                    progressBar?.visibility = View.GONE
                    shieldIcon?.setColorFilter(Color.parseColor("#9BA3B8"))
                }
                OverlayState.UNAVAILABLE -> {
                    statusText?.text = "Analysis unavailable"
                    resultText?.text = "Detection offline"
                    resultText?.setTextColor(Color.parseColor("#9BA3B8"))
                    subText?.text = res?.reason ?: ""
                    progressBar?.visibility = View.GONE
                    shieldIcon?.setColorFilter(Color.GRAY)
                }
            }
        }
    }

    private fun detectionScoreLine(res: DetectionResult?): String {
        val s = res?.spoofScore ?: return ""
        return "Detection score: ${"%.0f".format(s * 100f)}/100"
    }

    private fun evidenceLine(res: DetectionResult?, isCloned: Boolean): String {
        if (res == null) return ""
        val scorePart = if (res.spoofScore != null) "score ${"%.0f".format(res.spoofScore * 100f)}" else "score n/a"
        return "$scorePart · evidence ${"%.0f".format(res.evidence * 100f)}% · ${res.chunksAnalyzed} chunks"
    }

    private fun showOverlay() {
        if (containerView != null) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.END
        params.x = dpToPx(20)
        params.y = dpToPx(150)

        containerView = FrameLayout(this)

        val collapsed = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(dpToPx(70), dpToPx(70))
            background = createCircularBackground(Color.parseColor("#151B2B"))
            elevation = dpToPx(8).toFloat()
            setPadding(dpToPx(10), dpToPx(10), dpToPx(10), dpToPx(10))
            val icon = ImageView(context).apply {
                setImageResource(R.mipmap.ic_launcher)
                layoutParams = LinearLayout.LayoutParams(dpToPx(30), dpToPx(30))
            }
            addView(icon)
            val label = TextView(context).apply {
                text = "VoxSentry"
                setTextColor(Color.WHITE)
                textSize = 8f
                gravity = Gravity.CENTER
            }
            addView(label)
        }

        val expanded = LinearLayout(this).apply {
            visibility = View.GONE
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(dpToPx(240), dpToPx(290))
            background = createRectangularBackground(Color.parseColor("#151B2B"), 20)
            elevation = dpToPx(10).toFloat()
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))

            val header = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                val title = TextView(context).apply {
                    statusText = this
                    text = "Analyzing voice"
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    gravity = Gravity.CENTER
                }
                addView(title)
                val close = ImageView(context).apply {
                    closeButton = this
                    setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                    layoutParams = FrameLayout.LayoutParams(dpToPx(20), dpToPx(20)).apply {
                        gravity = Gravity.END or Gravity.TOP
                    }
                    setColorFilter(Color.GRAY)
                }
                addView(close)
            }
            addView(header)

            val centerIcon = ImageView(context).apply {
                shieldIcon = this
                setImageResource(R.mipmap.ic_launcher)
                layoutParams = LinearLayout.LayoutParams(dpToPx(46), dpToPx(46)).apply {
                    topMargin = dpToPx(8)
                }
            }
            addView(centerIcon)

            val result = TextView(context).apply {
                resultText = this
                text = "Listening..."
                setTextColor(Color.parseColor("#2DD4E8"))
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, dpToPx(6), 0, dpToPx(2))
            }
            addView(result)

            val sub = TextView(context).apply {
                subText = this
                text = ""
                setTextColor(Color.parseColor("#9BA3B8"))
                textSize = 10f
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dpToPx(4))
            }
            addView(sub)

            val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                progressBar = this
                isIndeterminate = true
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(4))
            }
            addView(progress)

            val footer = TextView(context).apply {
                text = "AI voice-clone detection"
                setTextColor(Color.GRAY)
                textSize = 9f
                gravity = Gravity.CENTER
                setPadding(0, dpToPx(4), 0, 0)
            }
            addView(footer)
            
            // Translation UI
            val transDiv = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(1)).apply {
                    topMargin = dpToPx(8)
                    bottomMargin = dpToPx(8)
                }
                setBackgroundColor(Color.parseColor("#333333"))
            }
            addView(transDiv)
            
            val transHeader = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                
                val toggle = TextView(context).apply {
                    translationToggleBtn = this
                    text = "Live Translation OFF"
                    setTextColor(Color.GRAY)
                    textSize = 12f
                    setOnClickListener { toggleLiveTranslation() }
                }
                addView(toggle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                
                val lang = TextView(context).apply {
                    translationLangBtn = this
                    text = "${supportedLangs[sourceLangIndex]} → ${supportedLangs[targetLangIndex]}"
                    setTextColor(Color.parseColor("#2DD4E8"))
                    textSize = 12f
                    gravity = Gravity.END
                    setOnClickListener { cycleTargetLang() }
                    setOnLongClickListener { cycleSourceLang(); true }
                }
                addView(lang, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            addView(transHeader)
            
            translationContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                visibility = View.GONE
                
                translationOriginalText = TextView(context).apply { text = "..."; setTextColor(Color.GRAY); textSize = 11f; setPadding(0, dpToPx(4), 0, dpToPx(2)) }
                addView(translationOriginalText)
                
                translationTranslatedText = TextView(context).apply { text = "..."; setTextColor(Color.WHITE); textSize = 12f; setTypeface(null, Typeface.BOLD) }
                addView(translationTranslatedText)
            }
            addView(translationContainer)
        }

        containerView?.addView(collapsed)
        containerView?.addView(expanded)
        collapsedView = collapsed
        expandedView = expanded

        collapsed.setOnClickListener { toggleExpanded(true, params) }
        closeButton?.setOnClickListener { toggleExpanded(false, params) }

        try {
            windowManager.addView(containerView, params)
        } catch (e: Exception) {
            Log.e("VoxSentry", "Failed to add overlay", e)
        }

        updateOverlayState(OverlayState.WAITING)
    }

    private fun toggleExpanded(expand: Boolean, params: WindowManager.LayoutParams) {
        isExpanded = expand
        if (expand) {
            collapsedView?.visibility = View.GONE
            expandedView?.visibility = View.VISIBLE
            params.width = dpToPx(240)
            params.height = dpToPx(290)
        } else {
            collapsedView?.visibility = View.VISIBLE
            expandedView?.visibility = View.GONE
            params.width = dpToPx(70)
            params.height = dpToPx(70)
        }
        try {
            windowManager.updateViewLayout(containerView, params)
        } catch (e: Exception) {
        }
    }

    private fun hideOverlay() {
        if (containerView != null) {
            try {
                windowManager.removeView(containerView)
                containerView = null
                collapsedView = null
                expandedView = null
                statusText = null
                resultText = null
                subText = null
                progressBar = null
                shieldIcon = null
                translationContainer = null
                translationToggleBtn = null
                translationLangBtn = null
                translationOriginalText = null
                translationTranslatedText = null
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun broadcastEvent(event: String, payload: String?) {
        val intent = Intent("com.anonymous.voxsentrymobile.DETECTION_EVENT")
        intent.putExtra("event", event)
        if (payload != null) {
            intent.putExtra("payload", payload)
        }
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "VoxSentry Protection Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun toggleLiveTranslation() {
        isTranslationActive = !isTranslationActive
        if (isTranslationActive) {
            translationToggleBtn?.text = "Live Translation ON"
            translationToggleBtn?.setTextColor(Color.WHITE)
            translationContainer?.visibility = View.VISIBLE
            translationOriginalText?.text = "Starting..."
            translationTranslatedText?.text = ""
            translationManager.startTranslation()
        } else {
            translationToggleBtn?.text = "Live Translation OFF"
            translationToggleBtn?.setTextColor(Color.GRAY)
            translationContainer?.visibility = View.GONE
            translationManager.stopTranslation()
        }
    }
    
    private fun cycleTargetLang() {
        targetLangIndex = (targetLangIndex + 1) % supportedLangs.size
        if (targetLangIndex == sourceLangIndex) targetLangIndex = (targetLangIndex + 1) % supportedLangs.size
        prefs.edit().putInt("targetLangIndex", targetLangIndex).apply()
        updateTranslationLangs()
    }
    
    private fun cycleSourceLang() {
        sourceLangIndex = (sourceLangIndex + 1) % supportedLangs.size
        if (sourceLangIndex == targetLangIndex) sourceLangIndex = (sourceLangIndex + 1) % supportedLangs.size
        prefs.edit().putInt("sourceLangIndex", sourceLangIndex).apply()
        updateTranslationLangs()
    }

    override fun onStateChanged(state: LiveTranslationManager.State) {}

    override fun onPartialTranscript(sourceText: String, targetText: String?) {
        translationOriginalText?.post {
            translationOriginalText?.text = "\"$sourceText\""
            translationTranslatedText?.text = targetText?.let { "\"$it\"" } ?: "..."
        }
    }

    override fun onFinalTranscript(sourceText: String, targetText: String?) {
        translationOriginalText?.post {
            translationOriginalText?.text = "\"$sourceText\""
            translationTranslatedText?.text = targetText?.let { "\"$it\"" } ?: "[Translation Error]"
        }
    }

    override fun onError(error: String) {
        translationOriginalText?.post {
            translationOriginalText?.text = error
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        hideOverlay()
        audioCaptureManager.stopCapture()
        detectionEngine.close()
        translationManager.release()
        CallSessionManager.stopNativeMonitoring()
        CallSessionManager.setListener(null)
        try {
            unregisterReceiver(audioRouteReceiver)
        } catch (e: Exception) {
        }
    }
}
