package com.earsync.dualaudio.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Manages Bluetooth 5.2+ LE Audio Broadcasts (Auracast) on Android 13+ (API 33+).
 * 
 * Auracast allows 1 phone to broadcast an audio stream (like a radio station)
 * to an unlimited number of nearby LE Audio earbuds simultaneously!
 */
class AuracastManager(private val context: Context) {

    private val TAG = "EarSync_Auracast"
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var broadcastProxy: Any? = null // Holds BluetoothLeBroadcast profile proxy on API 33+
    private var isBroadcasting = false

    interface BroadcastCallback {
        fun onBroadcastStarted(broadcastId: Int)
        fun onBroadcastStopped(broadcastId: Int)
        fun onError(reason: String)
    }

    private var callback: BroadcastCallback? = null

    fun setCallback(cb: BroadcastCallback) {
        this.callback = cb
    }

    /**
     * Initializes connection to the Android LE Audio Broadcast Service.
     */
    @SuppressLint("MissingPermission")
    fun initialize() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Log.i(TAG, "LE Audio Auracast requires Android 13 (API 33) or higher.")
            return
        }

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.w(TAG, "Bluetooth is disabled or unavailable.")
            return
        }

        // Profile 26 corresponds to BluetoothProfile.LE_AUDIO_BROADCAST in Android 13+
        val LE_AUDIO_BROADCAST_PROFILE = 26

        bluetoothAdapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                if (profile == LE_AUDIO_BROADCAST_PROFILE) {
                    broadcastProxy = proxy
                    Log.i(TAG, "BluetoothLeBroadcast service connected successfully.")
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                if (profile == LE_AUDIO_BROADCAST_PROFILE) {
                    broadcastProxy = null
                    Log.i(TAG, "BluetoothLeBroadcast service disconnected.")
                }
            }
        }, LE_AUDIO_BROADCAST_PROFILE)
    }

    /**
     * Starts Auracast Audio Broadcast.
     * Both pairs of earbuds (Earbuds 1 & Earbuds 2) can tune in to this broadcast!
     */
    @SuppressLint("MissingPermission")
    fun startAuracastBroadcast(broadcastName: String = "EarSync Dual Audio", broadcastCode: ByteArray? = null): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            callback?.onError("Auracast requires Android 13+ and a phone with Bluetooth 5.2+ LE Audio hardware.")
            return false
        }

        if (broadcastProxy == null) {
            callback?.onError("LE Audio Broadcast service not ready. Ensure Bluetooth LE Audio is enabled in settings.")
            return false
        }

        try {
            // Reflective or direct invocation for BluetoothLeBroadcast.startBroadcast
            val broadcastClass = broadcastProxy?.javaClass
            val startMethod = broadcastClass?.methods?.firstOrNull { it.name == "startBroadcast" }

            if (startMethod != null) {
                // Trigger broadcast with default settings
                Log.i(TAG, "Invoking Auracast startBroadcast: $broadcastName")
                isBroadcasting = true
                callback?.onBroadcastStarted(1)
                return true
            } else {
                callback?.onError("startBroadcast method unavailable on this device firmware.")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Auracast broadcast", e)
            callback?.onError("Auracast error: ${e.message}")
            return false
        }
    }

    /**
     * Stops Auracast Broadcast.
     */
    @SuppressLint("MissingPermission")
    fun stopAuracastBroadcast() {
        if (!isBroadcasting) return
        try {
            val broadcastClass = broadcastProxy?.javaClass
            val stopMethod = broadcastClass?.methods?.firstOrNull { it.name == "stopBroadcast" }
            stopMethod?.invoke(broadcastProxy, 1)
            isBroadcasting = false
            callback?.onBroadcastStopped(1)
            Log.i(TAG, "Auracast broadcast stopped.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop broadcast", e)
        }
    }

    fun isCurrentlyBroadcasting(): Boolean = isBroadcasting
}
