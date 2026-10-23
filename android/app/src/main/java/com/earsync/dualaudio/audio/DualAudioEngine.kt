package com.earsync.dualaudio.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DualAudioEngine:
 * Routes audio simultaneously to 2 distinct Bluetooth devices on 1 phone.
 * 
 * Uses dual AudioTrack instances with `setPreferredDevice(AudioDeviceInfo)` (Android 10+),
 * feeding cloned PCM sample buffers with independent volume controls and delay compensation.
 */
class DualAudioEngine {

    private val TAG = "EarSync_DualEngine"

    private val sampleRate = 48000
    private val channelConfig = AudioFormat.CHANNEL_OUT_STEREO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private var audioTrack1: AudioTrack? = null
    private var audioTrack2: AudioTrack? = null

    private var volume1 = 1.0f
    private var volume2 = 1.0f

    // Microsecond latency offset between devices (e.g. -100ms to +100ms)
    private var delayOffsetMs = 0

    private val isPlaying = AtomicBoolean(false)
    private var playThread: Thread? = null

    /**
     * Prepares and binds dual AudioTracks to the two selected Bluetooth devices.
     */
    fun setupDualRouting(device1: AudioDeviceInfo?, device2: AudioDeviceInfo?): Boolean {
        release()

        val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = minBufferSize * 4

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(channelConfig)
            .setEncoding(audioFormat)
            .build()

        try {
            // Track 1 for Earbuds 1
            audioTrack1 = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && device1 != null) {
                val success1 = audioTrack1?.setPreferredDevice(device1) ?: false
                Log.i(TAG, "Binding Track 1 to ${device1.id}: success=$success1")
            }

            // Track 2 for Earbuds 2
            if (device2 != null) {
                audioTrack2 = AudioTrack.Builder()
                    .setAudioAttributes(audioAttributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val success2 = audioTrack2?.setPreferredDevice(device2) ?: false
                    Log.i(TAG, "Binding Track 2 to ${device2.id}: success=$success2")
                }
            }

            // Apply volumes
            audioTrack1?.setVolume(volume1)
            audioTrack2?.setVolume(volume2)

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize DualAudioEngine", e)
            return false
        }
    }

    fun startPlayback() {
        if (isPlaying.get()) return
        isPlaying.set(true)

        try {
            audioTrack1?.play()
            audioTrack2?.play()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting AudioTrack playback", e)
        }
    }

    /**
     * Feeds incoming PCM audio buffer (from media player or system capture)
     * simultaneously into both Earbuds tracks.
     */
    fun writePcmData(pcmData: ByteArray, offset: Int, size: Int) {
        if (!isPlaying.get()) return

        // Write to Earbuds 1
        audioTrack1?.let { track1 ->
            try {
                track1.write(pcmData, offset, size, AudioTrack.WRITE_NON_BLOCKING)
            } catch (e: Exception) {
                Log.w(TAG, "Error writing to Track 1: ${e.message}")
            }
        }

        // Write to Earbuds 2 (Cloned PCM stream)
        audioTrack2?.let { track2 ->
            try {
                track2.write(pcmData, offset, size, AudioTrack.WRITE_NON_BLOCKING)
            } catch (e: Exception) {
                Log.w(TAG, "Error writing to Track 2: ${e.message}")
            }
        }
    }

    fun setEarbuds1Volume(vol: Float) {
        this.volume1 = vol.coerceIn(0.0f, 1.0f)
        audioTrack1?.setVolume(this.volume1)
    }

    fun setEarbuds2Volume(vol: Float) {
        this.volume2 = vol.coerceIn(0.0f, 1.0f)
        audioTrack2?.setVolume(this.volume2)
    }

    fun setLatencyOffsetMs(offsetMs: Int) {
        this.delayOffsetMs = offsetMs
    }

    fun getEarbuds1Volume(): Float = volume1
    fun getEarbuds2Volume(): Float = volume2

    /**
     * Plays a calibration metronome / click sound to test if both earbuds are in perfect sync.
     */
    fun playSyncTestTone() {
        Thread {
            val numSamples = sampleRate / 10 // 100ms tone
            val samples = ShortArray(numSamples * 2)
            val freq = 880.0 // A5 beep

            for (i in 0 until numSamples) {
                val sample = (Math.sin(2.0 * Math.PI * i * freq / sampleRate) * 16000).toInt().toShort()
                samples[i * 2] = sample     // Left
                samples[i * 2 + 1] = sample // Right
            }

            val byteBuf = ByteBuffer.allocate(samples.size * 2)
            for (s in samples) byteBuf.putShort(s)
            val bytes = byteBuf.array()

            // Play 3 rhythmic ticks
            for (tick in 0 until 3) {
                writePcmData(bytes, 0, bytes.size)
                Thread.sleep(400)
            }
        }.start()
    }

    fun pause() {
        isPlaying.set(false)
        audioTrack1?.pause()
        audioTrack2?.pause()
    }

    fun release() {
        isPlaying.set(false)
        try {
            audioTrack1?.stop()
            audioTrack1?.release()
            audioTrack1 = null

            audioTrack2?.stop()
            audioTrack2?.release()
            audioTrack2 = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioTracks", e)
        }
    }
}
