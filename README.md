# 🎧 EarSync: 1-Phone Dual Bluetooth Audio Engine

[![License: MIT](https://img.shields.io/badge/License-MIT-emerald.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-10%2B%20(API%2029%2B)-34D399.svg?logo=android)](android/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF.svg?logo=kotlin)](android/)
[![Bluetooth](https://img.shields.io/badge/Bluetooth-5.2%2B%20LE%20Audio%20%2F%20Auracast-06B6D4.svg?logo=bluetooth)](android/)
[![Web Companion](https://img.shields.io/badge/Web%20Companion-Vite%20%2B%20React-61DAFB.svg?logo=react)](client/)

> **Solve the 1-phone, 2-earbuds problem:** Watch a movie or listen to music together with a friend on **one single phone**, streaming stereo sound to **both pairs of Bluetooth earbuds simultaneously**—with selective device routing, individual volume sliders, and micro-latency sync calibration.

---

## 📌 The Problem

You and a friend want to watch a movie or listen to music together on a plane, bus, or in a library.
- You have **1 phone** and **2 separate pairs of Bluetooth earbuds**.
- Today, you usually have to share a single pair—putting one piece in your ear and one piece in your friend's ear (losing stereo channels and noise isolation).
- Even though modern phones can pair with multiple Bluetooth devices at once, the operating system normally routes media audio to **only one device at a time**.

---

## 🔬 Why Standard Bluetooth Blocks Dual Audio

1. **Digital Packet Framing (Not Raw Bits):**
   Bluetooth audio (A2DP) does not send raw uncompressed bits. It encodes audio into packetized frames (using codecs like SBC, AAC, LDAC, or aptX) with cryptographic handshakes, buffer clocks (50–200ms), and error-checking (CRC). You cannot alternate "1 bit to earbud 1, 1 bit to earbud 2" because switching midway corrupts packet framing and drops the connection.

2. **OS Audio HAL Restrictions:**
   In standard Android and iOS, regular apps are sandboxed. The operating system's Audio Hardware Abstraction Layer (HAL) selects a single active Bluetooth audio sink (`AudioDeviceInfo.TYPE_BLUETOOTH_A2DP`).

---

## 🚀 How EarSync Solves This

EarSync provides a multi-tiered engineering solution that runs directly on **1 phone**:

```
                                  ┌──────────────────────────────┐
                                  │      1 Phone (EarSync)       │
                                  └──────────────┬───────────────┘
                                                 │
                  ┌──────────────────────────────┴──────────────────────────────┐
                  │                                                             │
                  ▼                                                             ▼
     [Approach 1: Dual AudioTrack]                                 [Approach 2: LE Audio Auracast]
  Android 10+ (API 29+) System Engine                           Android 13+ (API 33+) Bluetooth 5.2+
                  │                                                             │
        AudioPlaybackCapture                                            BluetoothLeBroadcast
        (YouTube/Netflix/Local)                                             (Broadcaster)
                  │                                                             │
       ┌──────────┴──────────┐                                                  │
       ▼                     ▼                                                  │
AudioTrack 1          AudioTrack 2                                              │
setPreferredDevice    setPreferredDevice                                        │
  (Earbuds 1)           (Earbuds 2)                                             │
       │                     │                                                  │
       ▼                     ▼                                                  ▼
 🎧 Earbuds 1          🎧 Earbuds 2                                  🎧 Earbuds 1   🎧 Earbuds 2
  (You: 100%)          (Friend: 85%)                                (Tune in to Broadcast Stream)
```

### 1. Dual `AudioTrack` with `setPreferredDevice` (Android 10+)
* EarSync creates two parallel `AudioTrack` instances in the audio engine.
* Using `audioTrack1.setPreferredDevice(earbuds1)` and `audioTrack2.setPreferredDevice(earbuds2)`, the engine explicitly overrides default OS routing.
* Clones incoming PCM audio frames (from local videos or captured system audio) and writes them to both tracks simultaneously.
* Gives you **independent volume sliders** for each pair of earbuds!

### 2. Bluetooth 5.2+ LE Audio (Auracast) Engine (Android 13+)
* Uses the native `android.bluetooth.BluetoothLeBroadcast` API.
* The phone becomes an **Auracast transmitter** (like a private FM radio station).
* Any number of nearby LE Audio earbuds (Earbuds 1, Earbuds 2, etc.) can tune into the broadcast simultaneously.

### 3. Selective Device Routing (Checklist UI)
* Prevents audio from blasting to all connected Bluetooth devices (like smartwatches, car systems, or speakers).
* EarSync scans all connected devices and lets you check **only the exact 2 earbuds** you want to play sound on.

### 4. Micro-Latency Sync Calibration (-150ms to +150ms)
* Different earbud brands have different hardware decoding latencies (e.g., AirPods vs. Sony vs. budget SBC earbuds).
* EarSync includes an audio delay buffer and a **"Play Test Click"** tool so both listeners can dial in latency until the sound is unified without echo.

### 5. System Audio Capture (Stream YouTube, Netflix, Spotify)
* Uses Android's `AudioPlaybackCaptureConfiguration` and `MediaProjection` foreground service to capture any app's audio and duplicate it to both earbuds.

---

## 📱 Hardware & OS Compatibility Matrix

| Phone Type | Supported Feature | How to Use |
| :--- | :--- | :--- |
| **Samsung Galaxy** (One UI, BT 5.0+) | **Native Dual Audio** | Built into One UI. Swipe down -> **Media Output** -> Check both earbuds. EarSync provides a 1-tap shortcut. |
| **Android 13/14+** (Bluetooth 5.2+) | **Auracast Broadcast** | Use EarSync's Auracast tab to broadcast audio to both LE Audio earbuds. |
| **Stock Android / Pixel / OnePlus / Xiaomi** | **Dual AudioTrack Engine** | 1. Enable Developer Options.<br>2. Set *Maximum connected Bluetooth audio devices* to **2 or 5**.<br>3. Open EarSync and check both earbuds. |
| **Any Mobile Browser / Laptop** | **Web Companion & Media Player** | Load local video files into the EarSync Web Player with dual Web Audio routing. |

---

## 🛠️ Developer Options Setup (For Non-Samsung Android)

If your phone disconnects Earbuds 1 when you connect Earbuds 2:
1. Open **Settings** > **About Phone**.
2. Tap **Build Number** 7 times to enable Developer Options.
3. Go to **Settings** > **System** > **Developer Options**.
4. Scroll to **Maximum connected Bluetooth audio devices** (or *Bluetooth Audio Dual Channel*).
5. Change the value from `1` to **`2`** (or `5`).
6. Reconnect both earbuds!

---

## 📂 Project Architecture

```
earsync/
├── android/                                 # Native Android App (Kotlin + Compose)
│   ├── app/
│   │   ├── build.gradle.kts
│   │   └── src/main/
│   │       ├── AndroidManifest.xml          # Bluetooth & Audio Capture permissions
│   │       └── java/com/earsync/dualaudio/
│   │           ├── MainActivity.kt          # UI with device checklist, dual volume & latency sliders
│   │           ├── audio/
│   │           │   ├── DualAudioEngine.kt   # Dual AudioTrack setPreferredDevice router
│   │           │   └── SystemAudioCaptureService.kt # Android 10+ system audio recorder
│   │           └── bluetooth/
│   │               ├── BluetoothDeviceManager.kt    # Connected audio sink enumerator
│   │               └── AuracastManager.kt           # Android 13+ LE Audio broadcaster
│   ├── build.gradle.kts
│   └── settings.gradle.kts
│
├── client/                                  # Web / PWA Companion Player
│   ├── src/
│   │   ├── App.jsx                          # Dual Web Audio player, visualizer, sync clicks
│   │   └── index.css                        # Modern dark glassmorphic styling
│   └── package.json
│
├── server/                                  # Lightweight Local Relay Server (Optional)
│   ├── index.js                             # Local Wi-Fi WebSocket & QR signaling
│   └── package.json
│
├── LICENSE                                  # MIT License
└── README.md
```

---

## 🚀 Getting Started

### Option 1: Run the Web Companion & In-App Dual Player
```bash
# Clone the repository
git clone https://github.com/Coder-0X/earsync.git
cd earsync

# Install and start the web client
cd client
npm install
npm run dev
```
Open `http://localhost:5173` on your phone or laptop.

### Option 2: Build the Android App
1. Open the `/android` directory in **Android Studio**.
2. Ensure you have the **Android 14 (API 34) SDK** installed.
3. Build and deploy to your phone:
   ```bash
   cd android
   ./gradlew assembleDebug
   ```
4. Grant Bluetooth and Audio capture permissions when prompted.
5. Connect both earbuds, check their names in the list, and press **Start Dual Audio**!

---

## 📜 License

Distributed under the **MIT License**. See [`LICENSE`](LICENSE) for details.
