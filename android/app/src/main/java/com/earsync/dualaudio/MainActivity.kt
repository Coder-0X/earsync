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

    private fun applyRouting() {
        val selected = connectedDevices.filter { it.isSelected }
        if (selected.isEmpty()) {
            Toast.makeText(this, "Select at least 1 or 2 earbuds from the list", Toast.LENGTH_SHORT).show()
            return
        }

        val dev1 = selected.getOrNull(0)?.audioDeviceInfo
        val dev2 = selected.getOrNull(1)?.audioDeviceInfo

        val success = dualAudioEngine.setupDualRouting(dev1, dev2)
        if (success) {
            Toast.makeText(this, "Audio routing ready for ${selected.size} devices!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startSystemCapture() {
        val selected = connectedDevices.filter { it.isSelected }
        if (selected.size < 2) {
            Toast.makeText(this, "Please check 2 connected earphones below", Toast.LENGTH_LONG).show()
        }
        applyRouting()
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
                        text = "Play same audio to 2 pairs of earbuds from 1 phone",
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
                                    "Your phone has Samsung Dual Audio built into the hardware. You can open Samsung's Media Output panel directly.",
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

                // Auracast LE Audio Card
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
                                    Text("LIVE", color = Color(0xFF10B981), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                            Text(
                                if (isAuracastSupported)
                                    "Your phone supports Auracast! Broadcast audio to unlimited nearby LE Audio earbuds."
                                else
                                    "Auracast requires Android 13+ and a phone with Bluetooth 5.2 LE Audio chipset.",
                                fontSize = 12.sp,
                                color = Color(0xFFD1FAE5),
                                modifier = Modifier.padding(vertical = 6.dp)
                            )
                            if (isAuracastSupported) {
                                Button(
                                    onClick = {
                                        if (isAuracastActive) {
                                            auracastManager.stopAuracastBroadcast()
                                        } else {
                                            auracastManager.startAuracastBroadcast()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isAuracastActive) Color(0xFFEF4444) else Color(0xFF10B981)
                                    )
                                ) {
                                    Text(if (isAuracastActive) "Stop Auracast" else "Start Auracast Broadcast")
                                }
                            }
                        }
                    }
                }

                // Device Selection Header
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Select Earphones to Play (${connectedDevices.count { it.isSelected }}/2)",
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
                                "No Bluetooth audio devices connected.\nPair and connect your 2 earbuds in Bluetooth settings first.",
                                color = Color(0xFF94A3B8),
                                fontSize = 13.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                } else {
                    items(connectedDevices) { dev ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (dev.isSelected) Color(0xFF132F28) else Color(0xFF0F172A)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val currentSelected = connectedDevices.count { it.isSelected }
                                    if (!dev.isSelected && currentSelected >= 2) {
                                        Toast.makeText(this@MainActivity, "Maximum 2 earbuds can be selected at once", Toast.LENGTH_SHORT).show()
                                    } else {
                                        dev.isSelected = !dev.isSelected
                                        connectedDevices = ArrayList(connectedDevices)
                                        applyRouting()
                                    }
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = dev.isSelected,
                                    onCheckedChange = { checked ->
                                        val currentSelected = connectedDevices.count { it.isSelected }
                                        if (checked && currentSelected >= 2) {
                                            Toast.makeText(this@MainActivity, "Max 2 devices can be selected", Toast.LENGTH_SHORT).show()
                                        } else {
                                            dev.isSelected = checked
                                            connectedDevices = ArrayList(connectedDevices)
                                            applyRouting()
                                        }
                                    },
                                    colors = CheckboxDefaults.colors(checkedColor = Color(0xFF10B981))
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(dev.name, fontWeight = FontWeight.Bold, color = Color(0xFFF8FAFC))
                                    Text(
                                        "ID: ${dev.id} • ${if (dev.type == 8) "Bluetooth A2DP" else "LE Audio"}",
                                        fontSize = 11.sp,
                                        color = Color(0xFF94A3B8)
                                    )
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
                            Text("Earbuds 1 Volume: ${(vol1 * 100).toInt()}%", fontSize = 12.sp, color = Color(0xFF94A3B8))
                            Slider(
                                value = vol1,
                                onValueChange = {
                                    vol1 = it
                                    dualAudioEngine.setEarbuds1Volume(it)
                                },
                                colors = SliderDefaults.colors(thumbColor = Color(0xFF10B981), activeTrackColor = Color(0xFF10B981))
                            )

                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Earbuds 2 Volume: ${(vol2 * 100).toInt()}%", fontSize = 12.sp, color = Color(0xFF94A3B8))
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
                                    Text("Test Sync Click", color = Color(0xFF10B981), fontSize = 12.sp)
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
                                startSystemCapture()
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
                            fontSize = 16.sp
                        )
                    }
                }

                // Developer Options Helper
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text("💡 Developer Options Tip (Non-Samsung Phones)", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFFE2E8F0))
                            Text(
                                "If your phone disconnects Earbuds 1 when connecting Earbuds 2, go to Developer Options > 'Maximum connected Bluetooth audio devices' and set it to 2 or 5.",
                                fontSize = 11.sp,
                                color = Color(0xFF94A3B8),
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                            Button(
                                onClick = { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155))
                            ) {
                                Text("Open Developer Options", fontSize = 12.sp)
                            }
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }

    override fun onDestroy() {
        dualAudioEngine.release()
        super.onDestroy()
    }
}
