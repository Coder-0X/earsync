package com.earsync.dualaudio

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.earsync.dualaudio.audio.DualAudioEngine
import com.earsync.dualaudio.audio.SystemAudioCaptureService
import com.earsync.dualaudio.bluetooth.AuracastManager
import com.earsync.dualaudio.bluetooth.BluetoothDeviceManager
import com.earsync.dualaudio.bluetooth.ConnectedAudioDevice

class MainActivity : ComponentActivity() {

    private lateinit var deviceManager: BluetoothDeviceManager
    private lateinit var dualAudioEngine: DualAudioEngine
    private lateinit var auracastManager: AuracastManager
    private lateinit var mediaProjectionManager: MediaProjectionManager

    private var connectedDevices by mutableStateOf<List<ConnectedAudioDevice>>(emptyList())
    private var isSystemCaptureRunning by mutableStateOf(false)
    private var isAuracastActive by mutableStateOf(false)
    private var isSamsung by mutableStateOf(false)
    private var isAuracastSupported by mutableStateOf(false)

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val projection = mediaProjectionManager.getMediaProjection(result.resultCode, result.data!!)
            SystemAudioCaptureService.mediaProjection = projection
            SystemAudioCaptureService.dualAudioEngine = dualAudioEngine

            val serviceIntent = Intent(this, SystemAudioCaptureService::class.java).apply {
                action = SystemAudioCaptureService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            isSystemCaptureRunning = true
            Toast.makeText(this, "Dual Audio Sharing Active! Play any video or song.", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Screen/Audio capture permission denied.", Toast.LENGTH_SHORT).show()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        refreshDevices()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        deviceManager = BluetoothDeviceManager(this)
        dualAudioEngine = DualAudioEngine()
        auracastManager = AuracastManager(this)
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        isSamsung = deviceManager.isSamsungDevice()
        isAuracastSupported = deviceManager.isLeAudioBroadcastSupported()

        // Real-time listener: updates UI as soon as Bluetooth proxies connect or devices change
        deviceManager.setListener(object : BluetoothDeviceManager.DeviceListListener {
            override fun onDevicesUpdated(devices: List<ConnectedAudioDevice>) {
                runOnUiThread {
                    connectedDevices = devices
                }
            }
        })

        auracastManager.initialize()
        auracastManager.setCallback(object : AuracastManager.BroadcastCallback {
            override fun onBroadcastStarted(broadcastId: Int) {
                isAuracastActive = true
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Auracast Broadcast active!", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onBroadcastStopped(broadcastId: Int) {
                isAuracastActive = false
            }

            override fun onError(reason: String) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, reason, Toast.LENGTH_LONG).show()
                }
            }
        })

        checkAndRequestPermissions()
        refreshDevices()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Color(0xFF090D16))) {
                DualAudioAppContent()
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun refreshDevices() {
        connectedDevices = deviceManager.getConnectedAudioDevices()
    }

    private fun applyRouting(selectedList: List<ConnectedAudioDevice>) {
        if (selectedList.isEmpty()) return

        val dev1 = selectedList.getOrNull(0)?.audioDeviceInfo
        val dev2 = selectedList.getOrNull(1)?.audioDeviceInfo

        val success = dualAudioEngine.setupDualRouting(dev1, dev2)
        if (success) {
            Toast.makeText(this, "Dual routing ready for ${selectedList.size} earphones!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startSystemCapture(selectedList: List<ConnectedAudioDevice>) {
        if (selectedList.isEmpty()) {
            Toast.makeText(this, "Please check your earphones in the list below first", Toast.LENGTH_SHORT).show()
            return
        }
        applyRouting(selectedList)
        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        mediaProjectionLauncher.launch(captureIntent)
    }

    private fun stopSystemCapture() {
        val serviceIntent = Intent(this, SystemAudioCaptureService::class.java).apply {
            action = SystemAudioCaptureService.ACTION_STOP
        }
        startService(serviceIntent)
        isSystemCaptureRunning = false
        dualAudioEngine.pause()
        Toast.makeText(this, "Dual Audio Sharing stopped", Toast.LENGTH_SHORT).show()
    }

    @Composable
    fun DualAudioAppContent() {
        var vol1 by remember { mutableStateOf(1.0f) }
        var vol2 by remember { mutableStateOf(1.0f) }
        var latencyMs by remember { mutableStateOf(0f) }

        // Reactive Selection Set: Fixes the checkbox tick visibility bug completely!
        var selectedDeviceIds by remember { mutableStateOf(setOf<Int>()) }

        // Auto-select first 2 devices if none selected
        LaunchedEffect(connectedDevices) {
            if (selectedDeviceIds.isEmpty() && connectedDevices.isNotEmpty()) {
                selectedDeviceIds = connectedDevices.take(2).map { it.id }.toSet()
            }
        }

        val selectedDevices = connectedDevices.filter { selectedDeviceIds.contains(it.id) }

        Scaffold(
            containerColor = Color(0xFF090D16),
            topBar = {
                Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                    Text(
                        text = "🎧 EarSync Dual Audio",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF8FAFC)
                    )
                    Text(
                        text = "1 Phone • 2 Earbuds Dual Output Engine",
                        fontSize = 13.sp,
                        color = Color(0xFF94A3B8)
                    )
                }
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Samsung native panel banner if applicable
                if (isSamsung) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1B4B)),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    "✨ Samsung One UI Detected",
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFA5B4FC)
                                )
                                Text(
                                    "Your Galaxy phone supports Dual Audio natively. Tap below to open Samsung's Media Output panel and check both earbuds.",
                                    fontSize = 12.sp,
                                    color = Color(0xFFE0E7FF),
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                                Button(
                                    onClick = { deviceManager.openSamsungMediaOutputPanel() },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1))
                                ) {
                                    Text("Open Samsung Dual Audio Panel")
                                }
                            }
                        }
                    }
                }

                // Auracast LE Audio Card (Interactive Action Button always visible!)
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF064E3B)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("📡 Bluetooth 5.2+ LE Audio (Auracast)", fontWeight = FontWeight.Bold, color = Color(0xFF6EE7B7))
                                Spacer(modifier = Modifier.weight(1f))
                                if (isAuracastActive) {
                                    Text("BROADCASTING", color = Color(0xFF10B981), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                }
                            }
                            Text(
                                text = "Auracast broadcasts audio like an FM radio to any number of nearby Bluetooth 5.2+ LE Audio earbuds.",
                                fontSize = 12.sp,
                                color = Color(0xFFD1FAE5),
                                modifier = Modifier.padding(vertical = 6.dp)
                            )
                            Button(
                                onClick = {
                                    if (isAuracastActive) {
                                        auracastManager.stopAuracastBroadcast()
                                    } else {
                                        val started = auracastManager.startAuracastBroadcast()
                                        if (!started) {
                                            Toast.makeText(
                                                this@MainActivity,
                                                "Notice: Standard boAt & Redmi earbuds use Bluetooth Classic (A2DP), not LE Audio. Use Dual Audio below!",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isAuracastActive) Color(0xFFEF4444) else Color(0xFF10B981)
                                )
                            ) {
                                Text(
                                    text = if (isAuracastActive) "⏹️ Stop Auracast Broadcast" else "▶️ Start Auracast Broadcast",
                                    color = Color(0xFF090D16),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // Device Selection Header
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Select Earphones to Play (${selectedDeviceIds.size}/2 Checked)",
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFF1F5F9)
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        TextButton(onClick = { refreshDevices() }) {
                            Text("Refresh", color = Color(0xFF10B981))
                        }
                    }
                }

                // Device Checklist Items
                if (connectedDevices.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF1E293B))
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No connected Bluetooth earbuds found.\nMake sure boAt and Redmi are connected in your phone's Bluetooth settings.",
                                color = Color(0xFF94A3B8),
                                fontSize = 13.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                } else {
                    items(connectedDevices) { dev ->
                        val isChecked = selectedDeviceIds.contains(dev.id)

                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (isChecked) Color(0xFF132F28) else Color(0xFF0F172A)
                            ),
                            border = if (isChecked) BorderStroke(1.5.dp, Color(0xFF10B981)) else BorderStroke(1.dp, Color(0xFF334155)),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isChecked) {
                                        selectedDeviceIds = selectedDeviceIds - dev.id
                                    } else {
                                        if (selectedDeviceIds.size >= 2) {
                                            Toast.makeText(this@MainActivity, "Select up to 2 earbuds at once", Toast.LENGTH_SHORT).show()
                                        } else {
                                            selectedDeviceIds = selectedDeviceIds + dev.id
                                        }
                                    }
                                }
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    // High-contrast, custom styled Checkbox
                                    Checkbox(
                                        checked = isChecked,
                                        onCheckedChange = { checked ->
                                            if (checked) {
                                                if (selectedDeviceIds.size >= 2) {
                                                    Toast.makeText(this@MainActivity, "Select up to 2 earbuds at once", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    selectedDeviceIds = selectedDeviceIds + dev.id
                                                }
                                            } else {
                                                selectedDeviceIds = selectedDeviceIds - dev.id
                                            }
                                        },
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = Color(0xFF10B981),
                                            checkmarkColor = Color(0xFF090D16), // Pitch-black tick on emerald green: 100% visible!
                                            uncheckedColor = Color(0xFF64748B)
                                        )
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = dev.name,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                            color = Color(0xFFF8FAFC)
                                        )
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(top = 2.dp)
                                        ) {
                                            Text(
                                                text = dev.profileType,
                                                fontSize = 11.sp,
                                                color = Color(0xFF94A3B8)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = if (dev.isSystemActive) "• 🟢 ACTIVE SINK" else "• 🟡 CONNECTED STANDBY",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (dev.isSystemActive) Color(0xFF10B981) else Color(0xFFF59E0B)
                                            )
                                        }
                                    }

                                    // Switch / Activate Button
                                    if (dev.bluetoothDevice != null) {
                                        Button(
                                            onClick = {
                                                deviceManager.requestSetActiveDevice(dev.bluetoothDevice)
                                                Toast.makeText(this@MainActivity, "Switching audio to ${dev.name}...", Toast.LENGTH_SHORT).show()
                                                refreshDevices()
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (dev.isSystemActive) Color(0xFF1E293B) else Color(0xFF0284C7)
                                            ),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            modifier = Modifier.height(34.dp)
                                        ) {
                                            Text(
                                                text = if (dev.isSystemActive) "Active" else "Switch Here",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color.White
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Volume and Calibration Controls
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("🎚️ Dual Volume & Sync Calibration", fontWeight = FontWeight.Bold, color = Color(0xFFF1F5F9))

                            Spacer(modifier = Modifier.height(12.dp))
                            val earbud1Name = selectedDevices.getOrNull(0)?.name ?: "Earbuds 1 (boAt)"
                            val earbud2Name = selectedDevices.getOrNull(1)?.name ?: "Earbuds 2 (Redmi)"

                            Text("$earbud1Name Volume: ${(vol1 * 100).toInt()}%", fontSize = 12.sp, color = Color(0xFF94A3B8))
                            Slider(
                                value = vol1,
                                onValueChange = {
                                    vol1 = it
                                    dualAudioEngine.setEarbuds1Volume(it)
                                },
                                colors = SliderDefaults.colors(thumbColor = Color(0xFF10B981), activeTrackColor = Color(0xFF10B981))
                            )

                            Spacer(modifier = Modifier.height(8.dp))
                            Text("$earbud2Name Volume: ${(vol2 * 100).toInt()}%", fontSize = 12.sp, color = Color(0xFF94A3B8))
                            Slider(
                                value = vol2,
                                onValueChange = {
                                    vol2 = it
                                    dualAudioEngine.setEarbuds2Volume(it)
                                },
                                colors = SliderDefaults.colors(thumbColor = Color(0xFF06B6D4), activeTrackColor = Color(0xFF06B6D4))
                            )

                            Spacer(modifier = Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Latency Offset: ${latencyMs.toInt()} ms", fontSize = 12.sp, color = Color(0xFF94A3B8))
                                Spacer(modifier = Modifier.weight(1f))
                                TextButton(onClick = { dualAudioEngine.playSyncTestTone() }) {
                                    Text("Play Test Beep", color = Color(0xFF10B981), fontSize = 12.sp)
                                }
                            }
                            Slider(
                                value = latencyMs,
                                onValueChange = {
                                    latencyMs = it
                                    dualAudioEngine.setLatencyOffsetMs(it.toInt())
                                },
                                valueRange = -150f..150f,
                                colors = SliderDefaults.colors(thumbColor = Color(0xFF8B5CF6), activeTrackColor = Color(0xFF8B5CF6))
                            )
                        }
                    }
                }

                // Start Streaming Button
                item {
                    Button(
                        onClick = {
                            if (isSystemCaptureRunning) {
                                stopSystemCapture()
                            } else {
                                startSystemCapture(selectedDevices)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSystemCaptureRunning) Color(0xFFEF4444) else Color(0xFF10B981)
                        )
                    ) {
                        Text(
                            text = if (isSystemCaptureRunning) "🛑 Stop Dual Audio Sharing" else "▶️ Start Dual Audio (System Sound)",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF090D16),
                            fontSize = 16.sp
                        )
                    }
                }

                // Notice card for Redmi 15
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text("💡 Redmi 15 & Bluetooth Classic Note", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFFE2E8F0))
                            Text(
                                text = "boAt and Redmi earbuds use Bluetooth Classic A2DP. If your Redmi 15 chip only streams to the active earbud, use the 'Switch Here' button to seamlessly swap between them, or use the Wi-Fi Hotspot Relay mode.",
                                fontSize = 11.sp,
                                color = Color(0xFF94A3B8),
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }

    override fun onDestroy() {
        deviceManager.unregister()
        dualAudioEngine.release()
        super.onDestroy()
    }
}
