package com.earsync.dualaudio.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log

data class ConnectedAudioDevice(
    val id: Int,
    val name: String,
    val address: String?,
    val type: Int,
    val audioDeviceInfo: AudioDeviceInfo?,
    var isSelected: Boolean = false,
    var volumeLevel: Float = 1.0f // 0.0 to 1.0 (or boost up to 1.5)
)

class BluetoothDeviceManager(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val TAG = "EarSync_BTManager"

    /**
     * Enumerates all currently connected audio output devices (A2DP & LE Audio).
     */
    @SuppressLint("MissingPermission")
    fun getConnectedAudioDevices(): List<ConnectedAudioDevice> {
        val result = mutableListOf<ConnectedAudioDevice>()
        val outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        for (device in outputDevices) {
            when (device.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_SPEAKER,
                AudioDeviceInfo.TYPE_BLE_BROADCAST -> {
                    val deviceName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        device.productName.toString()
                    } else {
                        "Bluetooth Device (${device.id})"
                    }

                    val address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        device.address
                    } else null

                    result.add(
                        ConnectedAudioDevice(
                            id = device.id,
                            name = deviceName,
                            address = address,
                            type = device.type,
                            audioDeviceInfo = device
                        )
                    )
                }
            }
        }
        return result
    }

    /**
     * Check if the device is a Samsung phone running One UI.
     * Samsung includes proprietary multi-A2DP HAL support ("Dual Audio").
     */
    fun isSamsungDevice(): Boolean {
        return Build.MANUFACTURER.equals("samsung", ignoreCase = true)
    }

    /**
     * Attempts to open Samsung's native One UI Media Output / Dual Audio settings panel.
     */
    fun openSamsungMediaOutputPanel(): Boolean {
        if (!isSamsungDevice()) return false
        val intents = listOf(
            Intent("com.samsung.android.mdx.quickboard.ACTION_MEDIA_OUTPUT"),
            Intent("com.samsung.android.bluetooth.action.DUAL_AUDIO_SETTINGS"),
            Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
        )

        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) != null) {
                try {
                    context.startActivity(intent)
                    return true
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to launch intent: ${intent.action}", e)
                }
            }
        }
        return false
    }

    /**
     * Check if Android 13+ LE Audio (Auracast) broadcasting is supported on this phone.
     */
    fun isLeAudioBroadcastSupported(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return bluetoothAdapter?.isLeAudioBroadcastSourceSupported == BluetoothStatusCodes.FEATURE_SUPPORTED ||
               bluetoothAdapter?.isLeAudioSupported == BluetoothStatusCodes.FEATURE_SUPPORTED
    }
}

// Compatibility helper for BluetoothStatusCodes
object BluetoothStatusCodes {
    const val FEATURE_SUPPORTED = 10
    const val FEATURE_NOT_SUPPORTED = 11
}
