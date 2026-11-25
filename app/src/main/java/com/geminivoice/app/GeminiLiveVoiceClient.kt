package com.geminivoice.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class GeminiLiveVoiceClient(
    private val context: Context,
    private val workerHost: String,
    private val proxySecret: String = "",
    private val geminiApiKey: String = "",
    private val voiceName: String = "Aoede",
    private val systemInstruction: String = "تو یک هم‌صحبت فارسی‌زبان، خوش‌برخورد و تیزهوش هستی. جواب‌هات رو کوتاه، طبیعی و محاوره‌ای بگو.",
    private val model: String = "models/gemini-2.5-flash-native-audio-preview-09-2025",
    private val onStatusChange: (String) -> Unit = {},
    private val onError: (String) -> Unit = {},
    private val onTranscript: (isUser: Boolean, textChunk: String) -> Unit = { _, _ -> },
    private val onTurnEnd: (interrupted: Boolean) -> Unit = {}
) {
    companion object {
        private const val TAG = "GeminiLiveVoice"
        private const val INPUT_SAMPLE_RATE = 16000   // میکروفون -> جمینای
        private const val OUTPUT_SAMPLE_RATE = 24000  // جمینای -> اسپیکر
        private const val MIC_CHUNK_BYTES = 3200      // 100ms (16kHz * 2 bytes * 0.1s)
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var scope: CoroutineScope? = null
    private var webSocket: WebSocket? = null
    private var recordJob: Job? = null
    private var playbackJob: Job? = null

    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var audioTrack: AudioTrack? = null

    private var playbackChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private val isRunning = AtomicBoolean(false)
    private val isSetupComplete = AtomicBoolean(false)

    fun start(useSpeakerphone: Boolean = true) {
        if (!isRunning.compareAndSet(false, true)) return

        isSetupComplete.set(false)
        playbackChannel = Channel(Channel.UNLIMITED)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        configureAudioMode(enable = true, useSpeakerphone = useSpeakerphone)
        onStatusChange("در حال اتصال به ورکر...")

        val request = Request.Builder().url(buildWsUrl()).build()
        webSocket = okHttpClient.newWebSocket(request, createWebSocketListener())
    }

    fun stop() {
        if (!isRunning.compareAndSet(true, false)) return

        isSetupComplete.set(false)
        playbackChannel.close()

        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioTrack?.stop() } catch (_: Exception) {}

        recordJob?.cancel()
        playbackJob?.cancel()
        webSocket?.close(1000, "User stopped")
        webSocket = null

        scope?.cancel()
        scope = null

        configureAudioMode(enable = false, useSpeakerphone = false)
        onStatusChange("مکالمه قطع شد.")
    }

    private fun buildWsUrl(): String {
        val path = "/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        val params = buildList {
            if (proxySecret.isNotBlank()) add("proxy_secret=$proxySecret")
            if (geminiApiKey.isNotBlank()) add("key=$geminiApiKey")
        }.joinToString("&")
        return if (params.isNotEmpty()) {
            "wss://$workerHost$path?$params"
        } else {
            "wss://$workerHost$path"
        }
    }

    private fun createWebSocketListener() = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            onStatusChange("متصل شد، در حال ارسال Setup...")
            sendSetupMessage(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleServerJson(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            handleServerJson(bytes.utf8())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closing: $code / $reason")
            if (code != 1000 && isRunning.get()) {
                onError("اتصال بسته شد (کد $code): ${reason.ifBlank { "بررسی دامنه یا نام مدل" }}")
            }
            stop()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket failure", t)
            val httpInfo = response?.let { " (HTTP ${it.code})" }.orEmpty()
            onError("خطا در اتصال$httpInfo: ${t.localizedMessage ?: "نامشخص"}")
            stop()
        }
    }

    private fun sendSetupMessage(ws: WebSocket) {
        val setup = JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", model)
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().put("AUDIO"))
                    put("speechConfig", JSONObject().apply {
                        put("voiceConfig", JSONObject().apply {
                            put("prebuiltVoiceConfig", JSONObject().apply {
                                put("voiceName", voiceName)
                            })
                        })
                    })
                })
                if (systemInstruction.isNotBlank()) {
                    put("systemInstruction", JSONObject().apply {
                        put("parts", JSONArray().put(JSONObject().put("text", systemInstruction)))
                    })
                }
                put("inputAudioTranscription", JSONObject())
                put("outputAudioTranscription", JSONObject())
            })
        }
        ws.send(setup.toString())
    }

    private fun handleServerJson(rawJson: String) {
        try {
            val data = JSONObject(rawJson)

            if (data.has("setupComplete") && isSetupComplete.compareAndSet(false, true)) {
                onStatusChange("آماده مکالمه! صحبت کن...")
                startPlaybackLoop()
                startRecordingLoop()
                return
            }

            val serverContent = data.optJSONObject("serverContent") ?: return

            if (serverContent.optBoolean("interrupted", false)) {
                flushAudioTrack()
                onTurnEnd(true)
                onStatusChange("در حال گوش دادن...")
                return
            }

            serverContent.optJSONObject("inputTranscription")?.optString("text")?.let { text ->
                if (text.isNotEmpty()) {
                    onTranscript(true, text)
                }
            }

            serverContent.optJSONObject("outputTranscription")?.optString("text")?.let { text ->
                if (text.isNotEmpty()) {
                    onTranscript(false, text)
                }
            }

            val parts = serverContent.optJSONObject("modelTurn")?.optJSONArray("parts")
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val inlineData = parts.getJSONObject(i).optJSONObject("inlineData") ?: continue
                    val mimeType = inlineData.optString("mimeType")
                    if (mimeType.startsWith("audio/pcm")) {
                        val pcmBytes = Base64.decode(inlineData.getString("data"), Base64.DEFAULT)
                        playbackChannel.trySend(pcmBytes)
                    }
                }
            }

            if (serverContent.optBoolean("turnComplete", false)) {
                onTurnEnd(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "JSON parse error", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecordingLoop() {
        recordJob = scope?.launch {
            val minBuf = AudioRecord.getMinBufferSize(
                INPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBuf * 2, MIC_CHUNK_BYTES * 2)

            val recorder = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(INPUT_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .build()

            audioRecord = recorder
            var echoCanceler: AcousticEchoCanceler? = null
            var noiseSuppressor: NoiseSuppressor? = null

            try {
                if (AcousticEchoCanceler.isAvailable()) {
                    echoCanceler = AcousticEchoCanceler.create(recorder.audioSessionId)?.apply {
                        enabled = true
                    }
                }
                if (NoiseSuppressor.isAvailable()) {
                    noiseSuppressor = NoiseSuppressor.create(recorder.audioSessionId)?.apply {
                        enabled = true
                    }
                }

                recorder.startRecording()
                val readBuffer = ByteArray(MIC_CHUNK_BYTES)

                while (isActive && isRunning.get()) {
                    val bytesRead = recorder.read(readBuffer, 0, readBuffer.size)
                    if (bytesRead > 0) {
                        val chunk = if (bytesRead == readBuffer.size) {
                            readBuffer
                        } else {
                            readBuffer.copyOf(bytesRead)
                        }
                        sendAudioChunk(chunk)
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "Recording error", e)
                    onError("خطا در ضبط میکروفون: ${e.localizedMessage}")
                }
            } finally {
                echoCanceler?.release()
                noiseSuppressor?.release()
                try { recorder.stop() } catch (_: Exception) {}
                recorder.release()
                audioRecord = null
            }
        }
    }

    private fun sendAudioChunk(pcmBytes: ByteArray) {
        val base64Audio = Base64.encodeToString(pcmBytes, Base64.NO_WRAP)
        val payload = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("audio", JSONObject().apply {
                    put("mimeType", "audio/pcm;rate=$INPUT_SAMPLE_RATE")
                    put("data", base64Audio)
                })
            })
        }
        webSocket?.send(payload.toString())
    }

    private fun startPlaybackLoop() {
        playbackJob = scope?.launch {
            val minBuf = AudioTrack.getMinBufferSize(
                OUTPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBuf * 2, 9600)

            val trackBuilder = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(OUTPUT_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }

            val track = trackBuilder.build()
            audioTrack = track

            try {
                track.play()
                for (pcmBytes in playbackChannel) {
                    if (!isActive || !isRunning.get()) break
                    track.write(pcmBytes, 0, pcmBytes.size)
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "Playback error", e)
                }
            } finally {
                try { track.stop() } catch (_: Exception) {}
                track.release()
                audioTrack = null
            }
        }
    }

    private fun flushAudioTrack() {
        while (playbackChannel.tryReceive().isSuccess) {
            // Drain queued audio chunks
        }
        try {
            audioTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                    track.flush()
                    track.play()
                }
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "AudioTrack flush skipped: ${e.message}")
        }
    }

    private fun configureAudioMode(enable: Boolean, useSpeakerphone: Boolean) {
        if (enable) {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            if (useSpeakerphone) {
                routeToSpeakerIfNoHeadset()
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = false
            }
            audioManager.mode = AudioManager.MODE_NORMAL
        }
    }

    private fun routeToSpeakerIfNoHeadset() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audioManager.availableCommunicationDevices
            val hasHeadset = devices.any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
            if (!hasHeadset) {
                devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { audioManager.setCommunicationDevice(it) }
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = true
        }
    }
}
