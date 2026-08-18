package dev.sadat.androide.net

import dev.sadat.androide.ai.AiClient
import java.net.URLEncoder

object ImageGen {
    private val ai = AiClient()

    fun generate(prompt: String): ByteArray {
        val q = URLEncoder.encode(prompt.take(300), "UTF-8")
        val urls = listOf(
            "https://image.pollinations.ai/prompt/$q?width=768&height=768&nologo=true",
            "https://gen.pollinations.ai/image/$q"
        )
        var last: Exception? = null
        for (u in urls) {
            try {
                val (code, bytes) = ai.get(u)
                if (code in 200..299 && bytes.size > 200) return bytes
                last = RuntimeException("image HTTP $code")
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: RuntimeException("image failed")
    }
}
