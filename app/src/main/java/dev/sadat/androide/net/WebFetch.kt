package dev.sadat.androide.net

import dev.sadat.androide.ai.AiClient

object WebFetch {
    private val ai = AiClient()

    fun pageText(url: String, max: Int = 12000): String {
        val html = ai.getText(url)
        val noScript = html.replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
            .replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
            .replace(Regex("(?is)<[^>]+>"), " ")
            .replace(Regex("&nbsp;"), " ")
            .replace(Regex("&amp;"), "&")
            .replace(Regex("&lt;"), "<")
            .replace(Regex("&gt;"), ">")
            .replace(Regex("\\s+"), " ")
            .trim()
        return noScript.take(max)
    }
}
