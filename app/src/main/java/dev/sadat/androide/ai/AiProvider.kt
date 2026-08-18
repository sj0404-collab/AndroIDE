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
    val authHeader: (String) -> Map<String, String> = { key ->
        if (key.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $key")
    }
)

object Catalog {
    val all: List<ProviderSpec> = listOf(
        ProviderSpec(
            id = "zen",
            label = "Zen (OpenCode, no key)",
            needsKey = false,
            keyHint = "optional zen key from opencode.ai/auth",
            defaultModel = "big-pickle",
            models = listOf(
                "big-pickle",
                "deepseek-v4-flash-free",
                "mimo-v2.5-free",
                "hy3-free",
                "nemotron-3-ultra-free",
                "nemotron-3.5-lightning-free",
                "laguna-s-2.1-free",
                "minimax-m2.5",
                "kimi-k2.7-code",
                "qwen3.6-plus"
            ),
            baseUrl = "https://opencode.ai/zen/v1/chat/completions",
            modelsUrl = "https://opencode.ai/zen/v1/models"
        ),
        ProviderSpec(
            id = "openrouter",
            label = "OpenRouter (free + key)",
            needsKey = true,
            keyHint = "sk-or-v1-… from openrouter.ai/keys",
            defaultModel = "qwen/qwen3-coder:free",
            models = listOf(
                "qwen/qwen3-coder:free",
                "openai/gpt-oss-20b:free",
                "google/gemma-4-31b-it:free",
                "nvidia/nemotron-3-nano-30b-a3b:free",
                "meta-llama/llama-3.3-70b-instruct:free",
                "z-ai/glm-4.5-air:free",
                "deepseek/deepseek-r1-distill:free"
            ),
            baseUrl = "https://openrouter.ai/api/v1/chat/completions",
            modelsUrl = "https://openrouter.ai/api/v1/models"
        ),
        ProviderSpec(
            id = "pollinations",
            label = "Pollinations (works without key)",
            needsKey = false,
            keyHint = "optional pk_/sk_ from enter.pollinations.ai",
            defaultModel = "openai",
            models = listOf("openai", "openai-fast", "qwen-coder", "deepseek", "gemini", "mistral"),
            baseUrl = "https://text.pollinations.ai/openai",
        ),
        ProviderSpec(
            id = "pollinations_gen",
            label = "Pollinations gen (key preferred)",
            needsKey = false,
            keyHint = "sk_/pk_ from enter.pollinations.ai",
            defaultModel = "openai",
            models = listOf("openai", "openai-fast", "qwen-coder"),
            baseUrl = "https://gen.pollinations.ai/v1/chat/completions"
        ),
        ProviderSpec(
            id = "glean",
            label = "Glean / Glens (API key)",
            needsKey = true,
            keyHint = "instance token + host in key as host|token",
            defaultModel = "chat",
            models = listOf("chat"),
            baseUrl = "https://api.glean.com/rest/api/v1/chat"
        ),
        ProviderSpec(
            id = "groq",
            label = "Groq (API key)",
            needsKey = true,
            keyHint = "gsk_… from console.groq.com",
            defaultModel = "llama-3.3-70b-versatile",
            models = listOf(
                "llama-3.3-70b-versatile",
                "llama-3.1-8b-instant",
                "mixtral-8x7b-32768",
                "qwen/qwen3-32b"
            ),
            baseUrl = "https://api.groq.com/openai/v1/chat/completions"
        ),
        ProviderSpec(
            id = "openai",
            label = "OpenAI compatible (key)",
            needsKey = true,
            keyHint = "key; optional custom URL stored as url|key",
            defaultModel = "gpt-4o-mini",
            models = listOf("gpt-4o-mini", "gpt-4.1-mini", "gpt-4o"),
            baseUrl = "https://api.openai.com/v1/chat/completions"
        ),
        ProviderSpec(
            id = "gemini",
            label = "Google Gemini (API key)",
            needsKey = true,
            keyHint = "AIza… from aistudio.google.com",
            defaultModel = "gemini-2.0-flash",
            models = listOf("gemini-2.0-flash", "gemini-2.5-flash", "gemini-1.5-flash"),
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/models"
        )
    )

    fun byId(id: String) = all.firstOrNull { it.id == id } ?: all.first()
}
