package dev.sadat.androide.ai

data class ProviderSpec(
    val id: String,
    val label: String,
    val needsKey: Boolean,
    val keyHint: String,
    val defaultModel: String,
    val models: List<String>,
    val baseUrl: String,
    val modelsUrl: String? = null,
    val reasoningModels: Set<String> = emptySet()
)

object Catalog {
    val reasoningHints = setOf(
        "big-pickle", "r1", "reason", "thinking", "kimi-k2", "deepseek-r",
        "o1", "o3", "o4", "qwq", "nemotron", "hy3"
    )

    val fallbacks = listOf(
        "zen" to "big-pickle",
        "zen" to "deepseek-v4-flash-free",
        "zen" to "mimo-v2.5-free",
        "zen" to "nemotron-3.5-lightning-free",
        "pollinations" to "openai-fast",
        "pollinations" to "openai",
        "openrouter" to "openai/gpt-oss-20b:free",
        "openrouter" to "qwen/qwen3-coder:free",
        "local" to "llama3.2"
    )

    val all: List<ProviderSpec> = listOf(
        ProviderSpec(
            id = "zen",
            label = "Zen (no key)",
            needsKey = false,
            keyHint = "optional zen key",
            defaultModel = "big-pickle",
            models = listOf(
                "big-pickle", "deepseek-v4-flash-free", "mimo-v2.5-free", "hy3-free",
                "nemotron-3-ultra-free", "nemotron-3.5-lightning-free", "laguna-s-2.1-free",
                "minimax-m2.5", "kimi-k2.7-code", "qwen3.6-plus"
            ),
            baseUrl = "https://opencode.ai/zen/v1/chat/completions",
            modelsUrl = "https://opencode.ai/zen/v1/models",
            reasoningModels = setOf("big-pickle", "hy3-free", "kimi-k2.7-code")
        ),
        ProviderSpec(
            id = "openrouter",
            label = "OpenRouter",
            needsKey = true,
            keyHint = "sk-or-v1-…",
            defaultModel = "qwen/qwen3-coder:free",
            models = listOf(
                "qwen/qwen3-coder:free",
                "openai/gpt-oss-20b:free",
                "google/gemma-4-31b-it:free",
                "nvidia/nemotron-3-nano-30b-a3b:free",
                "deepseek/deepseek-r1-distill:free"
            ),
            baseUrl = "https://openrouter.ai/api/v1/chat/completions",
            modelsUrl = "https://openrouter.ai/api/v1/models"
        ),
        ProviderSpec(
            id = "pollinations",
            label = "Pollinations",
            needsKey = false,
            keyHint = "optional pk_/sk_",
            defaultModel = "openai",
            models = listOf("openai", "openai-fast", "qwen-coder", "deepseek", "gemini"),
            baseUrl = "https://text.pollinations.ai/openai"
        ),
        ProviderSpec(
            id = "pollinations_gen",
            label = "Pollinations gen",
            needsKey = false,
            keyHint = "sk_/pk_",
            defaultModel = "openai",
            models = listOf("openai", "openai-fast"),
            baseUrl = "https://gen.pollinations.ai/v1/chat/completions"
        ),
        ProviderSpec(
            id = "local",
            label = "Local (Ollama / llama.cpp / LM Studio)",
            needsKey = false,
            keyHint = "optional local token",
            defaultModel = "llama3.2",
            models = listOf("llama3.2", "qwen2.5-coder", "deepseek-r1", "phi3", "gemma2"),
            baseUrl = "http://127.0.0.1:11434/v1/chat/completions",
            modelsUrl = "http://127.0.0.1:11434/v1/models"
        ),
        ProviderSpec(
            id = "glean",
            label = "Glean / Glens",
            needsKey = true,
            keyHint = "host|token",
            defaultModel = "chat",
            models = listOf("chat"),
            baseUrl = "https://api.glean.com/rest/api/v1/chat"
        ),
        ProviderSpec(
            id = "groq",
            label = "Groq",
            needsKey = true,
            keyHint = "gsk_…",
            defaultModel = "llama-3.3-70b-versatile",
            models = listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant"),
            baseUrl = "https://api.groq.com/openai/v1/chat/completions"
        ),
        ProviderSpec(
            id = "openai",
            label = "OpenAI compatible",
            needsKey = true,
            keyHint = "url|key or key",
            defaultModel = "gpt-4o-mini",
            models = listOf("gpt-4o-mini", "gpt-4.1-mini", "o4-mini"),
            baseUrl = "https://api.openai.com/v1/chat/completions"
        ),
        ProviderSpec(
            id = "gemini",
            label = "Gemini",
            needsKey = true,
            keyHint = "AIza…",
            defaultModel = "gemini-2.0-flash",
            models = listOf("gemini-2.0-flash", "gemini-2.5-flash"),
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/models"
        )
    )

    fun byId(id: String) = all.firstOrNull { it.id == id } ?: all.first()

    fun isReasoning(model: String): Boolean {
        val m = model.lowercase()
        return reasoningHints.any { m.contains(it) }
    }
}
