package dev.sadat.androide.ai

data class ChatMessage(
    val role: String,
    val content: String,
    val reasoning: String = "",
    val ts: Long = System.currentTimeMillis(),
    val visible: Boolean = true,
    val kind: String = "text"
)

data class CompletionResult(
    val text: String,
    val reasoning: String = "",
    val model: String = "",
    val provider: String = "",
    val httpCode: Int = 200
)

class RateLimitException(val code: Int, msg: String) : RuntimeException(msg)
