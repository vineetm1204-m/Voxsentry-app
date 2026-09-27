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

class ProtectionService : Service(), CallSessionManager.CallStateListener, AudioCaptureManager.AudioCaptureListener, InferenceEngine.InferenceListener {

    private lateinit var windowManager: WindowManager
    private var containerView: FrameLayout? = null
    private var collapsedView: View? = null
    private var expandedView: View? = null
    
    // Expanded View components
    private var statusText: TextView? = null
    private var resultText: TextView? = null
    private var progressBar: ProgressBar? = null
    private var shieldIcon: ImageView? = null
    private var closeButton: ImageView? = null
    
    private val CHANNEL_ID = "VoxSentryProtectionChannel"

    private lateinit var audioManager: AudioManager
    private lateinit var vibrator: Vibrator
    
    private val audioCaptureManager = AudioCaptureManager()
    private lateinit var inferenceEngine: InferenceEngine

    private var isCallCurrentlyActive = false
    private var isSpeakerOn = false
    private var isExpanded = false

    private lateinit var historyStore: HistoryStore
    private var callStartTime: Long = 0
    private var maxConfidence: Float = 0f
    private var currentCallType: String = "native"

    enum class OverlayState {
        WAITING, ANALYZING, SAFE, THREAT
    }

    private var currentState = OverlayState.WAITING

    private val audioRouteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_HEADSET_PLUG || intent?.action == AudioManager.ACTION_SPEAKERPHONE_STATE_CHANGED) {
                checkSpeakerState()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        historyStore = HistoryStore(this)

        inferenceEngine = InferenceEngine(this)
        inferenceEngine.setListener(this)
        audioCaptureManager.setListener(this)

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
            maxConfidence = 0f
            showOverlay()
            checkSpeakerState()
        } else {
            if (callStartTime > 0) {
                val duration = System.currentTimeMillis() - callStartTime
                val finalStatus = when (currentState) {
                    OverlayState.THREAT -> "threat"
                    OverlayState.SAFE -> "safe"
                    else -> "unknown"
                }
                historyStore.addRecord(
                    CallRecord(
                        id = java.util.UUID.randomUUID().toString(),
                        timestamp = callStartTime,
                        duration = duration,
                        finalStatus = finalStatus,
                        maxConfidence = maxConfidence,
                        callType = currentCallType
                    )
                )
            }
            hideOverlay()
            audioCaptureManager.stopCapture()
            inferenceEngine.reset()
            callStartTime = 0
        }
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
        inferenceEngine.processAudioWindow(audioData)
    }

    override fun onCaptureError(error: String) {
        updateOverlayState(OverlayState.WAITING)
        broadcastEvent("onCaptureStopped", error)
    }

    override fun onDetectionResult(isThreat: Boolean, confidence: Float) {
        if (confidence > maxConfidence) {
            maxConfidence = confidence
        }

        val newState = if (isThreat) OverlayState.THREAT else OverlayState.SAFE
        updateOverlayState(newState)
        
        val result = "${if (isThreat) "threat" else "safe"}:$confidence"
        broadcastEvent("onDetectionUpdate", result)
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

    private fun updateOverlayState(state: OverlayState) {
        if (containerView == null) return

        currentState = state

        // Main thread UI update
        containerView?.post {
            when (state) {
                OverlayState.WAITING -> {
                    statusText?.text = "Waiting for speaker..."
                    resultText?.text = "Enable speaker to analyze"
                    resultText?.setTextColor(Color.WHITE)
                    progressBar?.visibility = View.GONE
                    shieldIcon?.setColorFilter(Color.GRAY)
                }
                OverlayState.ANALYZING -> {
                    statusText?.text = "Analyzing voice"
                    resultText?.text = "Listening for patterns..."
                    resultText?.setTextColor(Color.parseColor("#2DD4E8"))
                    progressBar?.visibility = View.VISIBLE
                    shieldIcon?.setColorFilter(Color.parseColor("#2DD4E8"))
                }
                OverlayState.SAFE -> {
                    statusText?.text = "Analysis Complete"
                    resultText?.text = "Safe: Human Voice"
                    resultText?.setTextColor(Color.parseColor("#10B981"))
                    progressBar?.visibility = View.GONE
                    shieldIcon?.setColorFilter(Color.parseColor("#10B981"))
                }
                OverlayState.THREAT -> {
                    val percentage = (maxConfidence * 100).toInt()
                    statusText?.text = "THREAT DETECTED"
                    resultText?.text = "$percentage% Cloned Voice"
                    resultText?.setTextColor(Color.parseColor("#EF4444"))
                    progressBar?.visibility = View.GONE
                    shieldIcon?.setColorFilter(Color.parseColor("#EF4444"))

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        vibrator.vibrate(500)
                    }
                }
            }
        }
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
        
        // 1. Create Collapsed View (The Circle)
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
        
        // 2. Create Expanded View (The Rectangle)
        val expanded = LinearLayout(this).apply {
            visibility = View.GONE
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(dpToPx(220), dpToPx(180))
            background = createRectangularBackground(Color.parseColor("#151B2B"), 20)
            elevation = dpToPx(10).toFloat()
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
            
            // Header Row
            val header = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                
                val title = TextView(context).apply {
                    statusText = this
                    text = "Analyzing voice"
                    setTextColor(Color.WHITE)
                    textSize = 16f
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
            
            // Icon / Result Area
            val centerIcon = ImageView(context).apply {
                shieldIcon = this
                setImageResource(R.mipmap.ic_launcher)
                layoutParams = LinearLayout.LayoutParams(dpToPx(50), dpToPx(50)).apply {
                    topMargin = dpToPx(10)
                }
            }
            addView(centerIcon)
            
            val result = TextView(context).apply {
                resultText = this
                text = "Listening..."
                setTextColor(Color.parseColor("#2DD4E8"))
                textSize = 18f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, dpToPx(8), 0, dpToPx(8))
            }
            addView(result)
            
            val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                progressBar = this
                isIndeterminate = true
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(4))
            }
            addView(progress)
            
            val footer = TextView(context).apply {
                text = "AI-generated voice detection"
                setTextColor(Color.GRAY)
                textSize = 10f
                gravity = Gravity.CENTER
                setPadding(0, dpToPx(4), 0, 0)
            }
            addView(footer)
        }

        containerView?.addView(collapsed)
        containerView?.addView(expanded)
        collapsedView = collapsed
        expandedView = expanded

        // Click listeners
        collapsed.setOnClickListener {
            toggleExpanded(true, params)
        }
        
        closeButton?.setOnClickListener {
            toggleExpanded(false, params)
        }

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
            params.width = dpToPx(220)
            params.height = dpToPx(180)
        } else {
            collapsedView?.visibility = View.VISIBLE
            expandedView?.visibility = View.GONE
            params.width = dpToPx(70)
            params.height = dpToPx(70)
        }
        try {
            windowManager.updateViewLayout(containerView, params)
        } catch (e: Exception) {}
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
                progressBar = null
                shieldIcon = null
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

    override fun onDestroy() {
        super.onDestroy()
        hideOverlay()
        audioCaptureManager.stopCapture()
        CallSessionManager.stopNativeMonitoring()
        CallSessionManager.setListener(null)
        try {
            unregisterReceiver(audioRouteReceiver)
        } catch (e: Exception) {}
    }
}
