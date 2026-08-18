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
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    fun complete(
        messages: List<ChatMessage>,
        providerId: String = AndroApp.instance.keys.provider,
        modelName: String = AndroApp.instance.keys.model
    ): CompletionResult {
        val spec = Catalog.byId(providerId)
        val model = modelName.ifBlank { spec.defaultModel }
        val rawKey = AndroApp.instance.keys.getKey(spec.id)
        return when (spec.id) {
            "gemini" -> gemini(spec, model, rawKey, messages)
            "glean" -> CompletionResult(glean(rawKey, messages), provider = spec.id, model = model)
            "local" -> openaiCompat(spec.copy(baseUrl = AndroApp.instance.keys.localBase), model, rawKey, messages)
            else -> openaiCompat(spec, model, rawKey, messages)
        }
    }

    private fun openaiCompat(
        spec: ProviderSpec,
        model: String,
        rawKey: String,
        messages: List<ChatMessage>
    ): CompletionResult {
        var url = spec.baseUrl
        var key = rawKey
        if (rawKey.contains("|") && spec.id == "openai") {
            val parts = rawKey.split("|", limit = 2)
            url = parts[0].ifBlank { url }
            key = parts.getOrElse(1) { "" }
        }
        val body = JSONObject()
            .put("model", model)
            .put("temperature", if (Catalog.isReasoning(model)) 0.4 else 0.2)
            .put("messages", JSONArray().apply {
                messages.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
            })
        if (Catalog.isReasoning(model)) {
            body.put("include_reasoning", true)
        }
        val req = Request.Builder().url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("HTTP-Referer", "https://androide.app")
            .addHeader("X-Title", "AndroIDE")
        if (key.isNotBlank()) req.addHeader("Authorization", "Bearer $key")
        val resp = http.newCall(req.post(body.toString().toRequestBody(jsonType)).build()).execute()
        val text = resp.body?.string().orEmpty()
        if (isLimit(resp.code, text)) throw RateLimitException(resp.code, "${spec.id}/$model HTTP ${resp.code}: ${text.take(240)}")
        if (!resp.isSuccessful) throw RuntimeException("${spec.label} HTTP ${resp.code}: ${text.take(500)}")
        val parsed = parseOpenAi(text)
        return parsed.copy(provider = spec.id, model = model, httpCode = resp.code)
    }

    fun stream(
        messages: List<ChatMessage>,
        providerId: String,
        modelName: String,
        onDelta: (String) -> Unit
    ): CompletionResult {
        val spec = Catalog.byId(providerId)
        if (spec.id == "gemini" || spec.id == "glean") return complete(messages, providerId, modelName)
        var url = if (spec.id == "local") AndroApp.instance.keys.localBase else spec.baseUrl
        var key = AndroApp.instance.keys.getKey(spec.id)
        if (key.contains("|") && spec.id == "openai") {
            val parts = key.split("|", limit = 2)
            url = parts[0].ifBlank { url }
            key = parts.getOrElse(1) { "" }
        }
        val model = modelName.ifBlank { spec.defaultModel }
        val body = JSONObject()
            .put("model", model)
            .put("stream", true)
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
        if (isLimit(resp.code, "")) throw RateLimitException(resp.code, "stream ${resp.code}")
        if (!resp.isSuccessful) {
            val err = resp.body?.string().orEmpty()
            if (resp.code == 400 || err.contains("stream")) return complete(messages, providerId, modelName)
            throw RuntimeException("stream HTTP ${resp.code}: ${err.take(300)}")
        }
        val acc = StringBuilder()
        val reason = StringBuilder()
        resp.body!!.charStream().buffered().use { r ->
            while (true) {
                val line = r.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                try {
                    val o = JSONObject(data)
                    val ch = o.optJSONArray("choices")?.optJSONObject(0) ?: continue
                    val d = ch.optJSONObject("delta") ?: continue
                    val c = d.optString("content")
                    val rs = d.optString("reasoning_content")
                    if (rs.isNotBlank()) reason.append(rs)
                    if (c.isNotBlank()) {
                        acc.append(c)
                        onDelta(c)
                    }
                } catch (_: Exception) { }
            }
        }
        if (acc.isBlank()) return complete(messages, providerId, modelName)
        return CompletionResult(acc.toString(), reason.toString(), model, spec.id, resp.code)
    }

    private fun isLimit(code: Int, body: String): Boolean {
        if (code == 429 || code == 402) return true
        val b = body.lowercase()
        return listOf("rate limit", "quota", "too many requests", "capacity", "overloaded").any { b.contains(it) }
    }

    private fun parseOpenAi(text: String): CompletionResult {
        val obj = JSONObject(text)
        if (!obj.has("choices")) return CompletionResult(obj.optString("text", text))
        val msg = obj.getJSONArray("choices").getJSONObject(0).optJSONObject("message")
            ?: return CompletionResult(text)
        var content = msg.optString("content")
        var reasoning = msg.optString("reasoning_content")
        if (reasoning.isBlank()) reasoning = msg.optString("reasoning")
        val think = Regex("<think>([\\s\\S]*?)</think>", RegexOption.IGNORE_CASE).find(content)
        if (think != null) {
            reasoning = think.groupValues[1].trim()
            content = content.replace(think.value, "").trim()
        }
        if (content.isBlank() && reasoning.isNotBlank()) content = reasoning
        return CompletionResult(content, reasoning)
    }

    private fun gemini(spec: ProviderSpec, model: String, key: String, messages: List<ChatMessage>): CompletionResult {
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
        if (isLimit(resp.code, text)) throw RateLimitException(resp.code, text.take(200))
        if (!resp.isSuccessful) throw RuntimeException("Gemini HTTP ${resp.code}: ${text.take(400)}")
        val parts = JSONObject(text).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
        val out = buildString { for (i in 0 until parts.length()) append(parts.getJSONObject(i).optString("text")) }
        return CompletionResult(out, provider = "gemini", model = model)
    }

    private fun glean(raw: String, messages: List<ChatMessage>): String {
        if (!raw.contains("|")) throw RuntimeException("Glean key: host|token")
        val (host, token) = raw.split("|", limit = 2)
        val url = "https://${host.trim().removePrefix("https://").removeSuffix("/")}/rest/api/v1/chat"
        val msgs = JSONArray()
        messages.forEach {
            msgs.put(
                JSONObject().put("author", if (it.role == "assistant") "GLEAN_AI" else "USER")
                    .put("messageType", "CONTENT")
                    .put("fragments", JSONArray().put(JSONObject().put("text", it.content)))
            )
        }
        val req = Request.Builder().url(url)
            .addHeader("Authorization", "Bearer ${token.trim()}")
            .addHeader("Content-Type", "application/json")
            .post(JSONObject().put("messages", msgs).toString().toRequestBody(jsonType)).build()
        val resp = http.newCall(req).execute()
        val text = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) throw RuntimeException("Glean HTTP ${resp.code}")
        return text
    }

    fun fetchModels(spec: ProviderSpec): List<String> {
        val url = if (spec.id == "local") {
            AndroApp.instance.keys.localBase.replace("/chat/completions", "/models")
        } else spec.modelsUrl ?: return spec.models
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

    fun get(url: String): Pair<Int, ByteArray> {
        val resp = http.newCall(Request.Builder().url(url).get().build()).execute()
        return resp.code to (resp.body?.bytes() ?: ByteArray(0))
    }

    fun getText(url: String): String {
        val resp = http.newCall(
            Request.Builder().url(url).header("User-Agent", "AndroIDE/2.1").build()
        ).execute()
        val t = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code} $url")
        return t
    }
}
