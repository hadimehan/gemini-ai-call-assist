package com.geminivoice.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class TranscriptMessage(
    val id: Long = System.nanoTime(),
    val isUser: Boolean,
    val text: String,
    val isFinal: Boolean = false
)

data class VoiceServiceUiState(
    val isActive: Boolean = false,
    val statusText: String = "آماده شروع مکالمه",
    val errorMessage: String? = null,
    val transcripts: List<TranscriptMessage> = emptyList()
)

class GeminiVoiceService : Service() {

    companion object {
        private const val CHANNEL_ID = "gemini_live_voice_channel"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "action_start_gemini_voice"
        private const val ACTION_STOP = "action_stop_gemini_voice"
        private const val EXTRA_SPEAKERPHONE = "extra_speakerphone"

        private val _state = MutableStateFlow(VoiceServiceUiState())
        val state: StateFlow<VoiceServiceUiState> = _state.asStateFlow()

        fun start(context: Context, useSpeakerphone: Boolean) {
            val intent = Intent(context, GeminiVoiceService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SPEAKERPHONE, useSpeakerphone)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, GeminiVoiceService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun setError(message: String) {
            _state.update { it.copy(errorMessage = message) }
        }

        fun clearError() {
            _state.update { it.copy(errorMessage = null) }
        }

        fun clearTranscripts() {
            _state.update { it.copy(transcripts = emptyList()) }
        }
    }

    private var voiceClient: GeminiLiveVoiceClient? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val useSpeaker = intent.getBooleanExtra(EXTRA_SPEAKERPHONE, true)
                startSession(useSpeaker)
            }
            ACTION_STOP -> {
                stopSession()
            }
        }
        return START_NOT_STICKY
    }

    private fun startSession(useSpeakerphone: Boolean) {
        if (voiceClient != null) return

        val settings = VoiceSettingsStore(applicationContext).load()
        if (settings.cleanHost.isBlank()) {
            _state.update {
                it.copy(
                    isActive = false,
                    errorMessage = "آدرس دامنه ورکر تنظیم نشده است."
                )
            }
            stopSelf()
            return
        }

        val initialNotification = buildNotification("در حال اتصال به ورکر...")
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }

        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, initialNotification, fgsType)
        } catch (e: SecurityException) {
            _state.update {
                it.copy(
                    isActive = false,
                    errorMessage = "مجوز میکروفون برای اجرای سرویس فعال نیست."
                )
            }
            stopSelf()
            return
        }

        acquireWakeLock()

        _state.value = VoiceServiceUiState(
            isActive = true,
            statusText = "در حال اتصال به ورکر...",
            errorMessage = null,
            transcripts = emptyList()
        )

        val client = GeminiLiveVoiceClient(
            context = applicationContext,
            workerHost = settings.cleanHost,
            proxySecret = settings.proxySecret,
            voiceName = settings.voiceName,
            model = settings.model,
            systemInstruction = settings.systemInstruction,
            onStatusChange = { status ->
                if (status == "مکالمه قطع شد.") {
                    if (voiceClient != null) {
                        stopSession()
                    }
                } else {
                    _state.update { it.copy(statusText = status) }
                    updateNotification(status)
                }
            },
            onError = { error ->
                _state.update { it.copy(errorMessage = error) }
                stopSession(keepError = true)
            },
            onTranscript = { isUser, chunk ->
                appendTranscript(isUser, chunk)
            },
            onTurnEnd = { interrupted ->
                finalizeCurrentTurn(interrupted)
            }
        )

        voiceClient = client
        client.start(useSpeakerphone = useSpeakerphone)
    }

    private fun stopSession(keepError: Boolean = false) {
        val client = voiceClient
        voiceClient = null
        client?.stop()

        releaseWakeLock()

        _state.update { prev ->
            prev.copy(
                isActive = false,
                statusText = "مکالمه قطع شد.",
                errorMessage = if (keepError) prev.errorMessage else null
            )
        }

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        val client = voiceClient
        voiceClient = null
        client?.stop()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun appendTranscript(isUser: Boolean, chunk: String) {
        if (chunk.isEmpty()) return

        _state.update { current ->
            val list = current.transcripts.toMutableList()
            val last = list.lastOrNull()

            if (last != null && last.isUser == isUser && !last.isFinal) {
                list[list.lastIndex] = last.copy(text = last.text + chunk)
            } else {
                if (last != null && !last.isFinal) {
                    list[list.lastIndex] = last.copy(isFinal = true)
                }
                val cleanedStart = chunk.trimStart()
                if (cleanedStart.isNotEmpty()) {
                    list.add(
                        TranscriptMessage(
                            isUser = isUser,
                            text = cleanedStart,
                            isFinal = false
                        )
                    )
                }
            }

            val trimmed = if (list.size > 200) list.takeLast(200) else list
            current.copy(transcripts = trimmed)
        }
    }

    private fun finalizeCurrentTurn(interrupted: Boolean) {
        _state.update { current ->
            val list = current.transcripts.toMutableList()
            val last = list.lastOrNull()
            if (last != null && !last.isFinal) {
                val finalText = if (interrupted && !last.isUser) {
                    last.text.trimEnd() + " …"
                } else {
                    last.text
                }
                list[list.lastIndex] = last.copy(text = finalText, isFinal = true)
            }
            current.copy(transcripts = list)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "GeminiVoice::LiveAudioWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(2 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "مکالمه صوتی جمینای",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "نمایش وضعیت مکالمه فعال جمینای در پس‌زمینه"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, GeminiVoiceService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("جمینای لایو فعاله")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(openAppPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "پایان مکالمه",
                stopPendingIntent
            )
            .build()
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(statusText))
    }
}
