package dev.sadat.androide.ai

import dev.sadat.androide.AndroApp
import dev.sadat.androide.log.LogStore

class ModelRouter(val client: AiClient = AiClient()) {
    data class Attempt(val provider: String, val model: String, val note: String)

    fun complete(
        messages: List<ChatMessage>,
        onTry: (Attempt) -> Unit,
        onDelta: (String) -> Unit = {}
    ): CompletionResult {
        val keys = AndroApp.instance.keys
        val chain = LinkedHashSet<Pair<String, String>>()
        chain.add(keys.provider to keys.model)
        if (keys.autoRotate) Catalog.fallbacks.forEach { chain.add(it) }
        var last: Exception? = null
        for ((prov, model) in chain) {
            onTry(Attempt(prov, model, if (Catalog.isReasoning(model)) "reasoning" else "fast"))
            LogStore.add("route", "$prov/$model")
            try {
                val r = client.complete(messages, prov, model)
                onDelta("")
                keys.provider = prov
                keys.model = model
                return r
            } catch (e: RateLimitException) {
                last = e
                onTry(Attempt(prov, model, "limit ${e.code}, rotate"))
            } catch (e: Exception) {
                last = e
                val msg = e.message.orEmpty().lowercase()
                if (msg.contains("canceled") || msg.contains("cancel")) throw e
                if (keys.autoRotate && (msg.contains("429") || msg.contains("limit") || msg.contains("timeout") || msg.contains("failed to connect"))) {
                    onTry(Attempt(prov, model, "fail->next"))
                    continue
                }
                throw e
            }
        }
        throw last ?: RuntimeException("all models failed")
    }
}
