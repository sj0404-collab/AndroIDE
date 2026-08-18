package dev.sadat.androide.ai

import dev.sadat.androide.AndroApp
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class AiClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    fun complete(messages: List<ChatMessage>): String {
        val keys = AndroApp.instance.keys
        val spec = Catalog.byId(keys.provider)
        val model = keys.model.ifBlank { spec.defaultModel }
        val rawKey = keys.getKey(spec.id)
        return when (spec.id) {
            "gemini" -> gemini(spec, model, rawKey, messages)
            "glean" -> glean(rawKey, messages)
            else -> openaiCompat(spec, model, rawKey, messages)
        }
    }

    private fun openaiCompat(
        spec: ProviderSpec,
        model: String,
        rawKey: String,
        messages: List<ChatMessage>
    ): String {
        var url = spec.baseUrl
        var key = rawKey
        if (rawKey.contains("|") && (spec.id == "openai")) {
            val parts = rawKey.split("|", limit = 2)
            url = parts[0].ifBlank { url }
            key = parts.getOrElse(1) { "" }
        }
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0.2)
            .put("messages", JSONArray().apply {
                messages.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
            })
        val req = Request.Builder().url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("HTTP-Referer", "https://androide.app")
            .addHeader("X-Title", "AndroIDE")
        if (key.isNotBlank()) req.addHeader("Authorization", "Bearer $key")
        val resp = http.newCall(req.post(body.toString().toRequestBody(jsonType)).build()).execute()
        val text = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) throw RuntimeException("${spec.label} HTTP ${resp.code}: ${text.take(600)}")
        return parseOpenAi(text)
    }

    private fun parseOpenAi(text: String): String {
        val obj = JSONObject(text)
        if (obj.has("choices")) {
            val msg = obj.getJSONArray("choices").getJSONObject(0).optJSONObject("message")
                ?: return obj.toString()
            val content = msg.optString("content")
            val reasoning = msg.optString("reasoning_content")
            return when {
                content.isNotBlank() -> content
                reasoning.isNotBlank() -> reasoning
                else -> text
            }
        }
        return obj.optString("text", text)
    }

    private fun gemini(spec: ProviderSpec, model: String, key: String, messages: List<ChatMessage>): String {
        if (key.isBlank()) throw RuntimeException("Gemini needs an API key")
        val url = "${spec.baseUrl}/$model:generateContent?key=$key"
        val contents = JSONArray()
        messages.filter { it.role != "system" }.forEach {
            contents.put(
                JSONObject().put("role", if (it.role == "assistant") "model" else "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", it.content)))
            )
        }
        val sys = messages.filter { it.role == "system" }.joinToString("\n") { it.content }
        val body = JSONObject().put("contents", contents)
        if (sys.isNotBlank()) {
            body.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sys))))
        }
        val req = Request.Builder().url(url).post(body.toString().toRequestBody(jsonType))
            .addHeader("Content-Type", "application/json").build()
        val resp = http.newCall(req).execute()
        val text = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) throw RuntimeException("Gemini HTTP ${resp.code}: ${text.take(500)}")
        val parts = JSONObject(text).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
        return buildString { for (i in 0 until parts.length()) append(parts.getJSONObject(i).optString("text")) }
    }

    private fun glean(raw: String, messages: List<ChatMessage>): String {
        if (!raw.contains("|")) throw RuntimeException("Glean/Glens key format: instanceHost|token")
        val (host, token) = raw.split("|", limit = 2)
        val url = "https://${host.trim().removePrefix("https://").removeSuffix("/")}/rest/api/v1/chat"
        val msgs = JSONArray()
        messages.forEach {
            msgs.put(JSONObject().put("author", if (it.role == "assistant") "GLEAN_AI" else "USER")
                .put("messageType", "CONTENT").put("fragments", JSONArray().put(JSONObject().put("text", it.content))))
        }
        val body = JSONObject().put("messages", msgs)
        val req = Request.Builder().url(url)
            .addHeader("Authorization", "Bearer ${token.trim()}")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonType)).build()
        val resp = http.newCall(req).execute()
        val text = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) throw RuntimeException("Glean HTTP ${resp.code}: ${text.take(500)}")
        val obj = JSONObject(text)
        val chat = obj.optJSONObject("chatMessage") ?: obj.optJSONArray("messages")?.optJSONObject(0)
        val frags = chat?.optJSONArray("fragments")
        if (frags != null) {
            return buildString {
                for (i in 0 until frags.length()) append(frags.getJSONObject(i).optString("text"))
            }
        }
        return text
    }

    fun fetchModels(spec: ProviderSpec): List<String> {
        val url = spec.modelsUrl ?: return spec.models
        val key = AndroApp.instance.keys.getKey(spec.id)
        val req = Request.Builder().url(url)
        if (key.isNotBlank()) req.addHeader("Authorization", "Bearer $key")
        return try {
            val resp = http.newCall(req.build()).execute()
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) return spec.models
            val data = JSONObject(text).optJSONArray("data") ?: return spec.models
            val ids = mutableListOf<String>()
            for (i in 0 until data.length()) ids.add(data.getJSONObject(i).optString("id"))
            if (ids.isEmpty()) spec.models else ids
        } catch (_: Exception) {
            spec.models
        }
    }
}
