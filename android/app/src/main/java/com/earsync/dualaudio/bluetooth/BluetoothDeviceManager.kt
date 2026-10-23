package com.earsync.dualaudio.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log

data class ConnectedAudioDevice(
    val id: Int,
    val name: String,
    val address: String?,
    val profileType: String,
    val isSystemActive: Boolean,
    val audioDeviceInfo: AudioDeviceInfo?,
    val bluetoothDevice: BluetoothDevice?,
    var isSelected: Boolean = false,
    var volumeLevel: Float = 1.0f
)

class BluetoothDeviceManager(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val TAG = "EarSync_BTManager"

    private var a2dpProfile: BluetoothA2dp? = null
    private var headsetProfile: BluetoothHeadset? = null
    private var leAudioProfile: BluetoothProfile? = null

    interface DeviceListListener {
        fun onDevicesUpdated(devices: List<ConnectedAudioDevice>)
    }

    private var listener: DeviceListListener? = null

    fun setListener(l: DeviceListListener) {
        this.listener = l
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_ACL_DISCONNECTED,
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED,
                "android.bluetooth.a2dp.profile.action.ACTIVE_DEVICE_CHANGED" -> {
                    Log.i(TAG, "Bluetooth state broadcast received: ${intent.action}")
                    notifyDevicesChanged()
                }
            }
        }
    }

    init {
        initBluetoothProxies()
        registerReceiver()
    }

    private fun registerReceiver() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED)
            addAction("android.bluetooth.a2dp.profile.action.ACTIVE_DEVICE_CHANGED")
        }
        context.registerReceiver(bluetoothReceiver, filter)
    }

    @SuppressLint("MissingPermission")
    private fun initBluetoothProxies() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) return

        // 1. A2DP Proxy (High-quality stereo media audio)
        bluetoothAdapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.A2DP) {
                    a2dpProfile = proxy as BluetoothA2dp
                    Log.i(TAG, "A2DP profile connected. Found ${a2dpProfile?.connectedDevices?.size} connected A2DP devices.")
                    notifyDevicesChanged()
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.A2DP) {
                    a2dpProfile = null
                }
            }
        }, BluetoothProfile.A2DP)

        // 2. Headset Proxy (HFP/HSP)
        bluetoothAdapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.HEADSET) {
                    headsetProfile = proxy as BluetoothHeadset
                    notifyDevicesChanged()
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.HEADSET) {
                    headsetProfile = null
                }
            }
        }, BluetoothProfile.HEADSET)

        // 3. LE Audio Proxy (Profile 22 in Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val LE_AUDIO_PROFILE = 22
            bluetoothAdapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == LE_AUDIO_PROFILE) {
                        leAudioProfile = proxy
                        notifyDevicesChanged()
                    }
                }

                override fun onServiceDisconnected(profile: Int) {
                    if (profile == LE_AUDIO_PROFILE) {
                        leAudioProfile = null
                    }
                }
            }, LE_AUDIO_PROFILE)
        }
    }

    /**
     * Enumerates ALL connected Bluetooth audio devices (both active AND connected standby).
     * Unlike AudioManager.getDevices() which only returns the active sink, this queries
     * the Bluetooth A2DP & LE Audio stack directly!
     */
    @SuppressLint("MissingPermission")
    fun getConnectedAudioDevices(): List<ConnectedAudioDevice> {
        val result = mutableListOf<ConnectedAudioDevice>()
        val seenAddresses = mutableSetOf<String>()

        // 1. Get OS Active Audio Output Devices
        val outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val activeAudioMap = mutableMapOf<String, AudioDeviceInfo>()

        for (dev in outputDevices) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dev.address?.let { activeAudioMap[it] = dev }
            }
        }

        // 2. Query A2DP connected devices (Returns ALL connected Bluetooth earphones!)
        try {
            a2dpProfile?.connectedDevices?.forEach { btDev ->
                val addr = btDev.address ?: ""
                val name = btDev.name ?: "Bluetooth Earbuds"
                val isActive = activeAudioMap.containsKey(addr) || (outputDevices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP })
                seenAddresses.add(addr)

                result.add(
                    ConnectedAudioDevice(
                        id = btDev.hashCode(),
                        name = name,
                        address = addr,
                        profileType = "Bluetooth A2DP",
                        isSystemActive = isActive,
                        audioDeviceInfo = activeAudioMap[addr] ?: outputDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP },
                        bluetoothDevice = btDev
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying A2DP devices", e)
        }

        // 3. Query LE Audio connected devices (Android 13+)
        try {
            leAudioProfile?.let { leProxy ->
                val leMethod = leProxy.javaClass.getMethod("getConnectedDevices")
                @Suppress("UNCHECKED_CAST")
                val leDevices = leMethod.invoke(leProxy) as? List<BluetoothDevice>
                leDevices?.forEach { btDev ->
                    val addr = btDev.address ?: ""
                    if (!seenAddresses.contains(addr)) {
                        seenAddresses.add(addr)
                        result.add(
                            ConnectedAudioDevice(
                                id = btDev.hashCode(),
                                name = btDev.name ?: "LE Audio Earbuds",
                                address = addr,
                                profileType = "LE Audio",
                                isSystemActive = activeAudioMap.containsKey(addr),
                                audioDeviceInfo = activeAudioMap[addr],
                                bluetoothDevice = btDev
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "LE Audio query reflection notice: ${e.message}")
        }

        // 4. Query Headset (HFP) devices fallback
        try {
            headsetProfile?.connectedDevices?.forEach { btDev ->
                val addr = btDev.address ?: ""
                if (!seenAddresses.contains(addr)) {
                    seenAddresses.add(addr)
                    result.add(
                        ConnectedAudioDevice(
                            id = btDev.hashCode(),
                            name = btDev.name ?: "Bluetooth Headset",
                            address = addr,
                            profileType = "Bluetooth HFP",
                            isSystemActive = false,
                            audioDeviceInfo = activeAudioMap[addr],
                            bluetoothDevice = btDev
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying Headset devices", e)
        }

        // 5. Fallback if proxies haven't finished binding yet: read AudioManager outputs
        if (result.isEmpty()) {
            for (device in outputDevices) {
                when (device.type) {
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLE_HEADSET,
                    AudioDeviceInfo.TYPE_BLE_SPEAKER,
                    AudioDeviceInfo.TYPE_BLE_BROADCAST -> {
                        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            device.productName.toString()
                        } else "Bluetooth Device"

                        result.add(
                            ConnectedAudioDevice(
                                id = device.id,
                                name = name,
                                address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) device.address else null,
                                profileType = "A2DP (Active)",
                                isSystemActive = true,
                                audioDeviceInfo = device,
                                bluetoothDevice = null
                            )
                        )
                    }
                }
            }
        }

        return result
    }

    fun notifyDevicesChanged() {
        val devices = getConnectedAudioDevices()
        listener?.onDevicesUpdated(devices)
    }

    /**
     * Attempts to activate a Bluetooth device via reflection on BluetoothA2dp or BluetoothAdapter.
     */
    @SuppressLint("MissingPermission")
    fun requestSetActiveDevice(btDevice: BluetoothDevice): Boolean {
        try {
            // Try BluetoothA2dp.setActiveDevice(BluetoothDevice)
            a2dpProfile?.let { a2dp ->
                val method = a2dp.javaClass.getMethod("setActiveDevice", BluetoothDevice::class.java)
                val success = method.invoke(a2dp, btDevice) as? Boolean
                if (success == true) {
                    Log.i(TAG, "Successfully invoked setActiveDevice on A2DP for ${btDevice.name}")
                    return true
                }
            }

            // Try BluetoothAdapter.setActiveDevice(BluetoothDevice, int profiles)
            bluetoothAdapter?.let { adapter ->
                val method = adapter.javaClass.getMethod("setActiveDevice", BluetoothDevice::class.java, Int::class.javaPrimitiveType)
                val success = method.invoke(adapter, btDevice, 0) as? Boolean
                if (success == true) {
                    Log.i(TAG, "Successfully invoked setActiveDevice on adapter for ${btDevice.name}")
                    return true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "setActiveDevice reflection failed: ${e.message}")
        }
        return false
    }

    /**
     * Check if the device is a Samsung phone running One UI.
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
        return bluetoothAdapter?.isLeAudioBroadcastSourceSupported == 10 ||
               bluetoothAdapter?.isLeAudioSupported == 10
    }

    fun unregister() {
        try {
            context.unregisterReceiver(bluetoothReceiver)
        } catch (e: Exception) {
            // Ignored
        }
    }
}
