package com.example.ride

import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.PrettyNightsApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

class MusicService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var udpSocket: DatagramSocket? = null

    // AGC & Peak history state
    private var agcGain = 1.0
    private var sampleAvg = 0.0f
    private val avgHistory = FloatArray(50) { 0f }
    private var historyIndex = 0

    override fun onCreate() {
        super.onCreate()
        _isServiceRunning.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_MUSIC) {
            stopMusicSession()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            Log.e(TAG, "MediaProjection result code or data missing")
            stopMusicSession()
            return START_NOT_STICKY
        }

        // Start Foreground Notification
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Initialize Audio & UDP Loop
        serviceScope.launch {
            try {
                // Initialize MediaProjection
                val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

                if (mediaProjection == null) {
                    Log.e(TAG, "Failed to obtain MediaProjection")
                    stopSelf()
                    return@launch
                }

                // Setup AudioRecord
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build()

                    val sampleRate = 44100
                    val audioFormat = AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()

                    val minBufferSize = AudioRecord.getMinBufferSize(
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    )

                    audioRecord = AudioRecord.Builder()
                        .setAudioFormat(audioFormat)
                        .setBufferSizeInBytes(minBufferSize * 2)
                        .setAudioPlaybackCaptureConfig(captureConfig)
                        .build()

                    if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                        Log.e(TAG, "AudioRecord failed to initialize")
                        stopSelf()
                        return@launch
                    }

                    audioRecord?.startRecording()
                    udpSocket = DatagramSocket()

                    // Start Capture & Stream Loop
                    runStreamLoop(sampleRate)
                } else {
                    Log.e(TAG, "AudioPlaybackCapture is only supported on Android 10+")
                    stopSelf()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting music service stream", e)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private suspend fun runStreamLoop(sampleRate: Int) {
        val fftSize = 1024
        val readSize = 882 // 20ms of 44100Hz audio
        val readBuffer = ShortArray(readSize)
        val historyBuffer = ShortArray(fftSize)

        val config = PrettyNightsApp.instance.configManager.getConfigSnapshot()
        val port = config.audioSyncPort
        val multicastAddress = InetAddress.getByName("239.0.0.1")

        // 16 Frequency bands logarithmically spaced boundaries approx
        val bands = listOf(
            20.0..60.0,
            60.0..100.0,
            100.0..160.0,
            160.0..250.0,
            250.0..400.0,
            400.0..630.0,
            630.0..1000.0,
            1000.0..1600.0,
            1600.0..2500.0,
            2500.0..4000.0,
            4000.0..6300.0,
            6300.0..10000.0,
            10000.0..14000.0,
            14000.0..16000.0,
            16000.0..18000.0,
            18000.0..20000.0
        )

        var lastSendTime = System.currentTimeMillis()

        while (serviceScope.isActive) {
            val record = audioRecord ?: break
            val socket = udpSocket ?: break

            // Read raw PCM shorts
            val numRead = record.read(readBuffer, 0, readSize)
            if (numRead > 0) {
                // Shift historyBuffer left by numRead
                System.arraycopy(historyBuffer, numRead, historyBuffer, 0, fftSize - numRead)
                // Copy new samples to the end
                System.arraycopy(readBuffer, 0, historyBuffer, fftSize - numRead, numRead)

                // 1. Compute Raw Volume (Peak amplitude of current frame, 0..255)
                var peakShort = 0
                for (i in 0 until numRead) {
                    val absVal = Math.abs(readBuffer[i].toInt())
                    if (absVal > peakShort) {
                        peakShort = absVal
                    }
                }
                val sampleRaw = (peakShort * 255 / 32768).coerceIn(0, 255)

                // 2. Running average volume (0.0 .. 255.0)
                sampleAvg = sampleAvg + 0.1f * (sampleRaw.toFloat() - sampleAvg)

                // 3. Beat/Peak Detection
                avgHistory[historyIndex] = sampleAvg
                historyIndex = (historyIndex + 1) % 50
                val rollingAvg = avgHistory.sum() / 50.0f
                val samplePeak = if (sampleAvg > 1.5f * rollingAvg && sampleAvg > 5.0f) 1 else 0

                // 4. Software AGC
                if (sampleRaw > 0) {
                    val targetGain = 128.0 / sampleRaw
                    agcGain = agcGain + 0.01 * (targetGain - agcGain)
                } else {
                    agcGain = agcGain + 0.01 * (1.0 - agcGain)
                }
                agcGain = agcGain.coerceIn(0.1, 10.0)
                val sampleAgc = (sampleRaw * agcGain).toInt().coerceIn(0, 255)

                // 5. FFT computation
                val real = DoubleArray(fftSize)
                val imag = DoubleArray(fftSize)
                for (i in 0 until fftSize) {
                    val sampleNormalized = historyBuffer[i] / 32768.0
                    val hann = 0.5 * (1.0 - cos(2.0 * PI * i / (fftSize - 1)))
                    real[i] = sampleNormalized * hann
                    imag[i] = 0.0
                }

                fftInPlace(real, imag)

                val magnitudes = DoubleArray(fftSize / 2)
                for (i in 0 until fftSize / 2) {
                    magnitudes[i] = Math.sqrt(real[i] * real[i] + imag[i] * imag[i])
                }

                // Map to 16 bands
                val fftResult = ByteArray(16)
                val bandsFloat = FloatArray(16)
                for (bandIdx in 0 until 16) {
                    val range = bands[bandIdx]
                    var sumSq = 0.0
                    var count = 0
                    for (i in 1 until fftSize / 2) {
                        val freq = i * (sampleRate.toDouble() / fftSize)
                        val mag = magnitudes[i]
                        if (freq in range) {
                            sumSq += mag * mag
                            count++
                        }
                    }
                    val rms = if (count > 0) Math.sqrt(sumSq / count) else 0.0
                    // Apply a boost to make the visual display responsive
                    val normalized = (rms * 4.0 * 255.0).toInt().coerceIn(0, 255)
                    fftResult[bandIdx] = normalized.toByte()
                    bandsFloat[bandIdx] = normalized / 255f
                }
                _fftBands.value = bandsFloat

                // FFT MajorPeak & Magnitude
                var maxMag = -1.0
                var maxMagBin = -1
                var sumAllMags = 0.0
                for (i in 1 until fftSize / 2) {
                    val mag = magnitudes[i]
                    sumAllMags += mag
                    if (mag > maxMag) {
                        maxMag = mag
                        maxMagBin = i
                    }
                }
                val fftMajorPeak = if (maxMagBin != -1) maxMagBin * (sampleRate.toDouble() / fftSize) else 0.0
                val fftMagnitude = sumAllMags

                // Extract last 32 samples for myVals
                val myVals = ByteArray(32)
                for (i in 0 until 32) {
                    val sampleIdx = fftSize - 32 + i
                    val sample = historyBuffer[sampleIdx]
                    val mapped = ((sample + 32768) * 255 / 65535).coerceIn(0, 255)
                    myVals[i] = mapped.toByte()
                }

                // 6. Pack V2 Packet
                val bytes = packV2Packet(
                    myVals = myVals,
                    sampleAgc = sampleAgc,
                    sampleRaw = sampleRaw,
                    sampleAvg = sampleAvg,
                    samplePeak = samplePeak,
                    fftResult = fftResult,
                    fftMagnitude = fftMagnitude,
                    fftMajorPeak = fftMajorPeak
                )
                
                // Enforce 20ms pacing (50 Hz max rate)
                val elapsedSinceLast = System.currentTimeMillis() - lastSendTime
                if (elapsedSinceLast < 20) {
                    delay(20 - elapsedSinceLast)
                }

                try {
                    val packet = DatagramPacket(bytes, bytes.size, multicastAddress, port)
                    socket.send(packet)
                } catch (e: Exception) {
                    // Log error but do not crash (e.g. transient network/unreachable error)
                    Log.w(TAG, "Multicast UDP send failed: ${e.message}")
                }

                lastSendTime = System.currentTimeMillis()
            } else {
                delay(10)
            }
        }
    }

    private fun stopMusicSession() {
        cleanup()
        _isServiceRunning.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanup() {
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            // ignore
        }
        try {
            audioRecord?.release()
        } catch (e: Exception) {
            // ignore
        }
        audioRecord = null

        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
            // ignore
        }
        mediaProjection = null

        try {
            udpSocket?.close()
        } catch (e: Exception) {
            // ignore
        }
        udpSocket = null

        _fftBands.value = FloatArray(16) { 0f }
        serviceScope.cancel()
    }

    override fun onDestroy() {
        cleanup()
        _isServiceRunning.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, MusicService::class.java).apply {
            action = ACTION_STOP_MUSIC
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 2, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, PrettyNightsApp.CHANNEL_ID)
            .setContentTitle("Pretty Nights — Music Mode")
            .setContentText("Streaming audio sync packets via UDP multicast...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop Music",
                stopPendingIntent
            )
            .build()
    }

    companion object {
        private const val TAG = "MusicService"
        const val NOTIFICATION_ID = 250102
        const val ACTION_STOP_MUSIC = "com.example.ride.STOP_MUSIC"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

        private val _fftBands = MutableStateFlow(FloatArray(16) { 0f })
        val fftBands: StateFlow<FloatArray> = _fftBands.asStateFlow()

        internal fun packV2Packet(
            myVals: ByteArray,
            sampleAgc: Int,
            sampleRaw: Int,
            sampleAvg: Float,
            samplePeak: Int,
            fftResult: ByteArray,
            fftMagnitude: Double,
            fftMajorPeak: Double
        ): ByteArray {
            val packetBuffer = ByteBuffer.allocate(84).apply {
                order(ByteOrder.LITTLE_ENDIAN)
                put(byteArrayOf(0x30, 0x30, 0x30, 0x30, 0x32, 0x00)) // "00002" + null
                put(myVals, 0, 32)
                putInt(sampleAgc)
                putInt(sampleRaw)
                putFloat(sampleAvg)
                put(samplePeak.toByte())
                put(0.toByte()) // reserved padding byte
                put(fftResult, 0, 16)
                putDouble(fftMagnitude)
                putDouble(fftMajorPeak)
            }
            return packetBuffer.array()
        }

        internal fun fftInPlace(real: DoubleArray, imag: DoubleArray) {
            val n = real.size
            var j = 0
            for (i in 0 until n) {
                if (i < j) {
                    val tempR = real[i]
                    real[i] = real[j]
                    real[j] = tempR
                    val tempI = imag[i]
                    imag[i] = imag[j]
                    imag[j] = tempI
                }
                var m = n shr 1
                while (m >= 1 && j >= m) {
                    j -= m
                    m = m shr 1
                }
                j += m
            }
            var size = 2
            while (size <= n) {
                val halfSize = size shr 1
                for (i in 0 until n step size) {
                    for (k in 0 until halfSize) {
                        val angle = -2.0 * PI * k / size
                        val wr = cos(angle)
                        val wi = sin(angle)
                        val pr = real[i + k + halfSize] * wr - imag[i + k + halfSize] * wi
                        val pi = real[i + k + halfSize] * wi + imag[i + k + halfSize] * wr
                        real[i + k + halfSize] = real[i + k] - pr
                        imag[i + k + halfSize] = imag[i + k] - pi
                        real[i + k] += pr
                        imag[i + k] += pi
                    }
                }
                size = size shl 1
            }
        }
    }
}
