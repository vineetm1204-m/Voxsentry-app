package com.vineetm1204m.voxsentrymobile

import android.content.Context
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi

object CallSessionManager {

    interface CallStateListener {
        fun onCallStateChanged(isActive: Boolean)
    }

    private var isNativeCallActive = false
    private var isVoipCallActive = false
    private var listener: CallStateListener? = null

    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null
    private var telephonyCallback: TelephonyCallback? = null

    val isCallActive: Boolean
        get() = isNativeCallActive || isVoipCallActive

    fun setListener(newListener: CallStateListener?) {
        listener = newListener
        // Trigger initial state
        listener?.onCallStateChanged(isCallActive)
    }

    fun startNativeMonitoring(context: Context) {
        telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            registerTelephonyCallback(context)
        } else {
            registerPhoneStateListener()
        }
    }

    fun stopNativeMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let {
                telephonyManager?.unregisterTelephonyCallback(it)
            }
        } else {
            phoneStateListener?.let {
                telephonyManager?.listen(it, PhoneStateListener.LISTEN_NONE)
            }
        }
    }

    fun setVoipCallActive(isActive: Boolean) {
        if (isVoipCallActive != isActive) {
            isVoipCallActive = isActive
            listener?.onCallStateChanged(isCallActive)
        }
    }

    private fun updateNativeCallState(isActive: Boolean) {
        if (isNativeCallActive != isActive) {
            isNativeCallActive = isActive
            listener?.onCallStateChanged(isCallActive)
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun registerTelephonyCallback(context: Context) {
        telephonyCallback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                handleCallState(state)
            }
        }
        telephonyManager?.registerTelephonyCallback(context.mainExecutor, telephonyCallback!!)
    }

    private fun registerPhoneStateListener() {
        phoneStateListener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                handleCallState(state)
            }
        }
        telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
    }

    private fun handleCallState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                updateNativeCallState(true)
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                updateNativeCallState(false)
            }
            TelephonyManager.CALL_STATE_RINGING -> {
                updateNativeCallState(false)
            }
        }
    }
}
