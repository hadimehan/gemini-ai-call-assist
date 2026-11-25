package com.geminivoice.app

import android.content.Context

data class VoiceSettings(
    val workerHost: String = "",
    val proxySecret: String = "",
    val voiceName: String = "Aoede",
    val model: String = "models/gemini-2.5-flash-native-audio-preview-09-2025",
    val systemInstruction: String = "تو یک هم‌صحبت فارسی‌زبان، خوش‌برخورد و تیزهوش هستی. جواب‌هات رو کوتاه، طبیعی و محاوره‌ای بگو."
) {
    val cleanHost: String
        get() = workerHost
            .trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("wss://")
            .removePrefix("ws://")
            .substringBefore("/")
}

class VoiceSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("gemini_voice_prefs", Context.MODE_PRIVATE)

    companion object {
        val AVAILABLE_VOICES = listOf("Aoede", "Puck", "Charon", "Kore", "Fenrir")
        private const val KEY_WORKER_HOST = "worker_host"
        private const val KEY_PROXY_SECRET = "proxy_secret"
        private const val KEY_VOICE_NAME = "voice_name"
        private const val KEY_MODEL = "model_name"
        private const val KEY_SYSTEM_PROMPT = "system_instruction"
    }

    fun load(): VoiceSettings {
        val defaults = VoiceSettings()
        return VoiceSettings(
            workerHost = prefs.getString(KEY_WORKER_HOST, defaults.workerHost).orEmpty(),
            proxySecret = prefs.getString(KEY_PROXY_SECRET, defaults.proxySecret).orEmpty(),
            voiceName = prefs.getString(KEY_VOICE_NAME, defaults.voiceName) ?: defaults.voiceName,
            model = prefs.getString(KEY_MODEL, defaults.model)?.ifBlank { defaults.model }
                ?: defaults.model,
            systemInstruction = prefs.getString(KEY_SYSTEM_PROMPT, defaults.systemInstruction)
                ?: defaults.systemInstruction
        )
    }

    fun save(settings: VoiceSettings) {
        val defaults = VoiceSettings()
        prefs.edit()
            .putString(KEY_WORKER_HOST, settings.cleanHost)
            .putString(KEY_PROXY_SECRET, settings.proxySecret.trim())
            .putString(KEY_VOICE_NAME, settings.voiceName)
            .putString(KEY_MODEL, settings.model.trim().ifBlank { defaults.model })
            .putString(KEY_SYSTEM_PROMPT, settings.systemInstruction.trim())
            .apply()
    }
}
