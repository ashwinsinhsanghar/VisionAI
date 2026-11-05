package com.ashwin.visionai

import android.content.Context
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Talks to whichever AI provider is configured in Settings - Google Gemini,
 * OpenRouter, or Groq - matching the exact request/response shapes from
 * your own "Describe Media with AI" extension (so this is the same logic
 * that already works there, not a guess):
 *   - Google:     POST generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key=...
 *                 body: {"contents":[{"parts":[{"text"},{"inline_data":{mime_type,data}}]}]}
 *   - OpenRouter: POST openrouter.ai/api/v1/chat/completions
 *                 body: {"model","messages":[{"role","content":[{"type":"text"},{"type":"image_url","image_url":{"url":"data:...;base64,..."}}]}]}
 *                 header: Authorization: Bearer <key>
 *   - Groq:       same shape as OpenRouter, api.groq.com/openai/v1/chat/completions
 */
class AIClient(private val context: Context) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private fun service() = Prefs.getAiService(context)

    fun describeImage(jpegBytes: ByteArray, prompt: String): String? {
        val b64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        return when (service()) {
            "OpenRouter" -> openAiStyleCall(
                url = "https://openrouter.ai/api/v1/chat/completions",
                apiKey = Prefs.getOpenRouterKey(context),
                model = Prefs.getOpenRouterModel(context),
                prompt = prompt, imageBase64 = b64
            )
            "Groq" -> openAiStyleCall(
                url = "https://api.groq.com/openai/v1/chat/completions",
                apiKey = Prefs.getGroqKey(context),
                model = Prefs.getGroqModel(context),
                prompt = prompt, imageBase64 = b64
            )
            else -> googleCall(prompt, b64)
        }
    }

    fun askTextOnly(prompt: String): String? {
        return when (service()) {
            "OpenRouter" -> openAiStyleCall(
                url = "https://openrouter.ai/api/v1/chat/completions",
                apiKey = Prefs.getOpenRouterKey(context),
                model = Prefs.getOpenRouterModel(context),
                prompt = prompt, imageBase64 = null
            )
            "Groq" -> openAiStyleCall(
                url = "https://api.groq.com/openai/v1/chat/completions",
                apiKey = Prefs.getGroqKey(context),
                model = Prefs.getGroqModel(context),
                prompt = prompt, imageBase64 = null
            )
            else -> googleCall(prompt, null)
        }
    }

    // ---------------- Google Gemini ----------------
    private fun googleCall(prompt: String, imageBase64: String?): String? {
        val key = Prefs.getApiKey(context)?.takeIf { it.isNotBlank() }
            ?: return "Please set your Google Gemini API key in Settings first"
        val model = Prefs.getGeminiModel(context)
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key"

        val parts = JSONArray().put(JSONObject().put("text", prompt))
        if (imageBase64 != null) {
            parts.put(JSONObject().put(
                "inline_data",
                JSONObject().put("mime_type", "image/jpeg").put("data", imageBase64)
            ))
        }
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", parts)))

        val req = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: return null
                if (!resp.isSuccessful) return "Gemini error (code ${resp.code}): ${extractGoogleError(text)}"
                JSONObject(text).optJSONArray("candidates")
                    ?.optJSONObject(0)?.optJSONObject("content")
                    ?.optJSONArray("parts")?.optJSONObject(0)?.optString("text")
            }
        } catch (e: Exception) { null }
    }

    private fun extractGoogleError(body: String): String =
        try { JSONObject(body).optJSONObject("error")?.optString("message") ?: body } catch (e: Exception) { body }

    // ---------------- OpenRouter / Groq (OpenAI-style chat completions) ----------------
    private fun openAiStyleCall(url: String, apiKey: String?, model: String, prompt: String, imageBase64: String?): String? {
        val key = apiKey?.takeIf { it.isNotBlank() } ?: return "Please set the API key for this AI service in Settings first"
        if (model.isBlank()) return "Please select a model for this AI service in Settings first"

        val content = JSONArray().put(JSONObject().put("type", "text").put("text", prompt))
        if (imageBase64 != null) {
            content.put(JSONObject().put("type", "image_url").put(
                "image_url", JSONObject().put("url", "data:image/jpeg;base64,$imageBase64")
            ))
        }
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", content))
        val body = JSONObject().put("model", model).put("messages", messages)

        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: return null
                if (!resp.isSuccessful) return "AI service error (code ${resp.code}): ${extractOpenAiError(text)}"
                JSONObject(text).optJSONArray("choices")
                    ?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
            }
        } catch (e: Exception) { null }
    }

    private fun extractOpenAiError(body: String): String =
        try { JSONObject(body).optJSONObject("error")?.optString("message") ?: body } catch (e: Exception) { body }

    companion object {
        /** Fetches the live model list for whichever service, for Settings'
         *  model picker. Call off the main thread. */
        fun fetchModels(context: Context, service: String): List<String> {
            val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
            return try {
                when (service) {
                    "Google" -> {
                        val key = Prefs.getApiKey(context)?.takeIf { it.isNotBlank() } ?: return emptyList()
                        val req = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000&key=$key").build()
                        http.newCall(req).execute().use { resp ->
                            val body = resp.body?.string() ?: return emptyList()
                            val arr = JSONObject(body).optJSONArray("models") ?: return emptyList()
                            (0 until arr.length()).mapNotNull { i ->
                                arr.optJSONObject(i)?.optString("name")?.removePrefix("models/")
                            }.distinct()
                        }
                    }
                    "OpenRouter" -> {
                        val req = Request.Builder().url("https://openrouter.ai/api/v1/models").build()
                        http.newCall(req).execute().use { resp ->
                            val body = resp.body?.string() ?: return emptyList()
                            val arr = JSONObject(body).optJSONArray("data") ?: return emptyList()
                            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.optString("id") }
                        }
                    }
                    "Groq" -> {
                        val key = Prefs.getGroqKey(context)?.takeIf { it.isNotBlank() } ?: return emptyList()
                        val req = Request.Builder().url("https://api.groq.com/openai/v1/models")
                            .addHeader("Authorization", "Bearer $key").build()
                        http.newCall(req).execute().use { resp ->
                            val body = resp.body?.string() ?: return emptyList()
                            val arr = JSONObject(body).optJSONArray("data") ?: return emptyList()
                            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.optString("id") }
                        }
                    }
                    else -> emptyList()
                }
            } catch (e: Exception) { emptyList() }
        }
    }
}
