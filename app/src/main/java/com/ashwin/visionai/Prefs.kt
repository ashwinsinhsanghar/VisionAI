package com.ashwin.visionai

import android.content.Context
import java.util.UUID

/**
 * Plain SharedPreferences. Not encrypted at rest - acceptable tradeoff for
 * a single-person app, but switch to EncryptedSharedPreferences if this
 * phone or app is ever shared with anyone else.
 */
object Prefs {
    private const val FILE = "visionai_prefs"
    private const val KEY_API = "gemini_api_key"
    private const val KEY_GEMINI_MODEL = "gemini_selected_model"
    private const val KEY_AI_SERVICE = "api_service" // "Google" | "OpenRouter" | "Groq"
    private const val KEY_OPENROUTER_KEY = "openrouter_api_key"
    private const val KEY_OPENROUTER_MODEL = "openrouter_selected_model"
    private const val KEY_GROQ_KEY = "groq_api_key"
    private const val KEY_GROQ_MODEL = "groq_selected_model"
    private const val KEY_TTS_ENGINE = "tts_engine_package"
    private const val KEY_TTS_RATE = "tts_rate"
    private const val KEY_TTS_PITCH = "tts_pitch"
    private const val KEY_EMERGENCY_NUMBER = "emergency_number"
    private const val KEY_SHORT_DESCRIPTION = "short_description"
    private const val KEY_HAS_EVER_PAIRED = "has_ever_paired"
    // The glasses' firmware expects this exact hardcoded password - confirmed
    // from the manufacturer app's Gen5Connection.generateWiFiQRCode, which
    // sets hotspot_password = "smart@123" for every device, every time (not
    // randomly generated). Using anything else here is why pairing failed.
    private const val FIXED_GLASSES_PASSWORD = "smart@123"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getApiKey(context: Context): String? = prefs(context).getString(KEY_API, null)
    fun setApiKey(context: Context, key: String) = prefs(context).edit().putString(KEY_API, key).apply()

    fun getGeminiModel(context: Context): String = prefs(context).getString(KEY_GEMINI_MODEL, "gemini-2.0-flash") ?: "gemini-2.0-flash"
    fun setGeminiModel(context: Context, model: String) = prefs(context).edit().putString(KEY_GEMINI_MODEL, model).apply()

    fun getAiService(context: Context): String = prefs(context).getString(KEY_AI_SERVICE, "Google") ?: "Google"
    fun setAiService(context: Context, service: String) = prefs(context).edit().putString(KEY_AI_SERVICE, service).apply()

    fun getOpenRouterKey(context: Context): String? = prefs(context).getString(KEY_OPENROUTER_KEY, null)
    fun setOpenRouterKey(context: Context, key: String) = prefs(context).edit().putString(KEY_OPENROUTER_KEY, key).apply()
    fun getOpenRouterModel(context: Context): String = prefs(context).getString(KEY_OPENROUTER_MODEL, "google/gemini-2.0-flash-lite-001") ?: "google/gemini-2.0-flash-lite-001"
    fun setOpenRouterModel(context: Context, model: String) = prefs(context).edit().putString(KEY_OPENROUTER_MODEL, model).apply()

    fun getGroqKey(context: Context): String? = prefs(context).getString(KEY_GROQ_KEY, null)
    fun setGroqKey(context: Context, key: String) = prefs(context).edit().putString(KEY_GROQ_KEY, key).apply()
    fun getGroqModel(context: Context): String = prefs(context).getString(KEY_GROQ_MODEL, "") ?: ""
    fun setGroqModel(context: Context, model: String) = prefs(context).edit().putString(KEY_GROQ_MODEL, model).apply()

    /** Null means "use the system default TTS engine". */
    fun getTtsEnginePackage(context: Context): String? = prefs(context).getString(KEY_TTS_ENGINE, null)
    fun setTtsEnginePackage(context: Context, pkg: String?) = prefs(context).edit().putString(KEY_TTS_ENGINE, pkg).apply()

    fun getTtsRate(context: Context): Float = prefs(context).getFloat(KEY_TTS_RATE, 1.0f)
    fun setTtsRate(context: Context, rate: Float) = prefs(context).edit().putFloat(KEY_TTS_RATE, rate).apply()

    fun getTtsPitch(context: Context): Float = prefs(context).getFloat(KEY_TTS_PITCH, 1.0f)
    fun setTtsPitch(context: Context, pitch: Float) = prefs(context).edit().putFloat(KEY_TTS_PITCH, pitch).apply()

    fun getEmergencyNumber(context: Context): String? = prefs(context).getString(KEY_EMERGENCY_NUMBER, null)
    fun setEmergencyNumber(context: Context, number: String) = prefs(context).edit().putString(KEY_EMERGENCY_NUMBER, number).apply()

    /** true = short, point-to-point answers (default); false = detailed. */
    fun getShortDescription(context: Context): Boolean = prefs(context).getBoolean(KEY_SHORT_DESCRIPTION, true)
    fun setShortDescription(context: Context, short: Boolean) = prefs(context).edit().putBoolean(KEY_SHORT_DESCRIPTION, short).apply()

    /** True once the glasses have connected here at least once - lets the
     *  app wait quietly for an automatic reconnect instead of immediately
     *  showing the QR code again on every launch. */
    fun hasEverPaired(context: Context): Boolean = prefs(context).getBoolean(KEY_HAS_EVER_PAIRED, false)
    fun setHasEverPaired(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_HAS_EVER_PAIRED, value).apply()

    /** Fixed Wi-Fi Direct network name + password, matching exactly what the
     *  manufacturer app generates (Gen5Connection.generateWiFiQRCode):
     *  SSID = "DIRECT-hs-" + this phone's ANDROID_ID (so it's stable across
     *  restarts without needing to store anything ourselves), password is
     *  the glasses firmware's fixed "smart@123". Same values every launch
     *  is what lets the glasses auto-rejoin after the first pairing. */
    fun getOrCreateFixedWifiCredentials(context: Context): Pair<String, String> {
        val androidId = android.provider.Settings.Secure.getString(
            context.contentResolver, android.provider.Settings.Secure.ANDROID_ID
        ) ?: UUID.randomUUID().toString().replace("-", "").substring(0, 16)
        val ssid = "DIRECT-hs-$androidId"
        return ssid to FIXED_GLASSES_PASSWORD
    }
}
