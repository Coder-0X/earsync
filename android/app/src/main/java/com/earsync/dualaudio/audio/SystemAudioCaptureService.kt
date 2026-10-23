package com.earsync.dualaudio.audio

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground Service that captures phone system audio (YouTube, Netflix, Spotify)
 * using Android 10+ AudioPlaybackCaptureConfiguration and routes it to both earbuds.
 */
class SystemAudioCaptureService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START_CAPTURE"
        const val ACTION_STOP = "ACTION_STOP_CAPTURE"
        const val NOTIFICATION_ID = 4021
        const val CHANNEL_ID = "earsync_audio_capture"

        var mediaProjection: MediaProjection? = null
        var dualAudioEngine: DualAudioEngine? = null
    }

    private val TAG = "EarSync_CaptureService"
    private val isCapturing = AtomicBoolean(false)
    private var captureThread: Thread? = null
    private var audioRecord: AudioRecord? = null

    private val sampleRate = 48000
    private val channelConfig = AudioFormat.CHANNEL_IN_STEREO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, createNotification())
                startAudioCapture()
            }
            ACTION_STOP -> {
                stopAudioCapture()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startAudioCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.e(TAG, "AudioPlaybackCapture requires Android 10 (API 29)+")
            return
        }

        val projection = mediaProjection
        if (projection == null) {
            Log.e(TAG, "MediaProjection is null. Permission must be granted in UI.")
            return
        }

        val engine = dualAudioEngine
        if (engine == null) {
            Log.e(TAG, "DualAudioEngine is not initialized.")
            return
        }

        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(audioFormat)
            .setSampleRate(sampleRate)
            .setChannelMask(channelConfig)
            .build()

        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2

        try {
            audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .build()

            engine.startPlayback()
            audioRecord?.startRecording()
            isCapturing.set(true)

            captureThread = Thread {
                val buffer = ByteArray(bufferSize)
                Log.i(TAG, "System audio capture loop running...")

                while (isCapturing.get()) {
                    val readBytes = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readBytes > 0) {
                        engine.writePcmData(buffer, 0, readBytes)
                    }
                }
                Log.i(TAG, "System audio capture loop ended.")
            }.apply { start() }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioRecord capture", e)
        }
    }

    private fun stopAudioCapture() {
        isCapturing.set(false)
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            captureThread?.interrupt()
            captureThread = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio capture", e)
        }
    }

    private fun createNotification(): Notification {
        createNotificationChannel()

        val stopIntent = Intent(this, SystemAudioCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("EarSync Dual Audio Active")
            .setContentText("Streaming phone audio to 2 pairs of earbuds...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Streaming", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "EarSync Dual Audio Streamer",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows status while streaming to 2 Bluetooth earbuds"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopAudioCapture()
        super.onDestroy()
    }
}
