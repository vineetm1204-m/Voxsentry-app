package com.vineetm1204m.voxsentrymobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.modules.core.DeviceEventManagerModule

class CallDetectionModule(private val reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {

    private val detectionEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.anonymous.voxsentrymobile.DETECTION_EVENT") {
                val eventName = intent.getStringExtra("event")
                val payload = intent.getStringExtra("payload")

                val map = Arguments.createMap()
                map.putString("event", eventName)
                if (payload != null) {
                    map.putString("payload", payload)
                }

                reactContext
                    .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                    .emit("onDetectionUpdate", map)
            }
        }
    }

    init {
        val filter = IntentFilter("com.anonymous.voxsentrymobile.DETECTION_EVENT")
        reactContext.registerReceiver(detectionEventReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    override fun getName(): String {
        return "CallDetectionModule"
    }

    @ReactMethod
    fun startProtection(promise: Promise) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(reactContext)) {
                    promise.reject("PERMISSION_DENIED", "SYSTEM_ALERT_WINDOW permission not granted")
                    return
                }
            }
            
            val intent = Intent(reactContext, ProtectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                reactContext.startForegroundService(intent)
            } else {
                reactContext.startService(intent)
            }
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("START_ERROR", e.message)
        }
    }

    @ReactMethod
    fun stopProtection(promise: Promise) {
        try {
            val intent = Intent(reactContext, ProtectionService::class.java)
            reactContext.stopService(intent)
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("STOP_ERROR", e.message)
        }
    }

    @ReactMethod
    fun checkOverlayPermission(promise: Promise) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            promise.resolve(Settings.canDrawOverlays(reactContext))
        } else {
            promise.resolve(true)
        }
    }

    @ReactMethod
    fun requestOverlayPermission(promise: Promise) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(reactContext)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + reactContext.packageName)
                )
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                reactContext.startActivity(intent)
                promise.resolve(false)
            } else {
                promise.resolve(true)
            }
        } else {
            promise.resolve(true)
        }
    }

    @ReactMethod
    fun checkNotificationPermission(promise: Promise) {
        val enabledListeners = NotificationManagerCompat.getEnabledListenerPackages(reactContext)
        promise.resolve(enabledListeners.contains(reactContext.packageName))
    }

    @ReactMethod
    fun requestNotificationPermission(promise: Promise) {
        val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        reactContext.startActivity(intent)
        promise.resolve(true)
    }

    @ReactMethod
    fun checkBatteryOptimizationExemption(promise: Promise) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = reactContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            promise.resolve(powerManager.isIgnoringBatteryOptimizations(reactContext.packageName))
        } else {
            promise.resolve(true)
        }
    }

    @ReactMethod
    fun requestBatteryOptimizationExemption(promise: Promise) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:" + reactContext.packageName)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            reactContext.startActivity(intent)
            promise.resolve(true)
        } else {
            promise.resolve(true)
        }
    }

    @ReactMethod
    fun getHistory(promise: Promise) {
        try {
            val store = HistoryStore(reactContext)
            promise.resolve(store.getHistory())
        } catch (e: Exception) {
            promise.reject("HISTORY_ERROR", e.message)
        }
    }

    @ReactMethod
    fun clearHistory(promise: Promise) {
        try {
            val store = HistoryStore(reactContext)
            store.clearHistory()
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("HISTORY_ERROR", e.message)
        }
    }

    @ReactMethod
    fun addListener(eventName: String) {
        // Required for RN built-in Event Emitter Calls.
    }

    @ReactMethod
    fun removeListeners(count: Int) {
        // Required for RN built-in Event Emitter Calls.
    }
}
