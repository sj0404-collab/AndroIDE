package dev.sadat.androide.agent

import dev.sadat.androide.AndroApp
import dev.sadat.androide.ai.ChatMessage
import dev.sadat.androide.ai.ModelRouter
import dev.sadat.androide.github.GitHubClient
import dev.sadat.androide.local.LocalModels
import dev.sadat.androide.net.ImageGen
import dev.sadat.androide.net.WebFetch
import dev.sadat.androide.plugin.PluginHost
import dev.sadat.androide.project.Templates
import dev.sadat.androide.session.SessionStore

data class AgentEvent(val kind: String, val text: String)

class AgentEngine(
    private val sessions: SessionStore,
    private val router: ModelRouter = ModelRouter(),
    private val gh: GitHubClient = GitHubClient(AndroApp.instance.workspace),
    private val plugins: PluginHost = PluginHost()
) {
    init {
        plugins.installExample()
    }

    fun systemPrompt(): String {
        val ws = AndroApp.instance.workspace
        val keys = AndroApp.instance.keys
        return """
You are AndroIDE Agent. You create complete games and programs yourself.
User only reviews, edits, and presses Run.
Stack freedom: Kotlin, React (CDN in index.html), Phaser 2D, Three.js 3D, Canvas, vanilla JS.
Write COMPLETE runnable files. Prefer index.html for instant Run tab.

Project: ${ws.currentProject.name}
Files:
${ws.tree()}
Account=${keys.account} provider=${keys.provider} model=${keys.model} maxRounds=${keys.maxRounds}
${plugins.extraSystem()}

Fences:
```write path
contents
```
```delete path```
```read path```
```move from -> to```
```template react|3d|2d|kotlin|canvas```
```image assets/icon.png | prompt here```
```fetch https://url```
```github list``` / ```github clone owner/repo``` / ```github bind owner/repo```
```github commit message``` / ```github release v1 Title
notes``` / ```github workflow android.yml```
```local pull llama3.2```
```plugin name args```

Start with a short plan. Reasoning models: think first, then act.
        """.trimIndent()
    }

    fun run(userText: String, onEvent: (AgentEvent) -> Unit): String {
        val keys = AndroApp.instance.keys
        val s = sessions.current
        if (s.rounds >= keys.maxRounds) {
            return "Round limit ${keys.maxRounds} reached. Raise max rounds or start a new session."
        }
        if (s.messages.none { it.role == "system" }) {
            s.messages.add(ChatMessage("system", systemPrompt()))
        }
        s.messages.add(ChatMessage("user", userText))
        s.rounds += 1
        if (s.title == "New session") s.title = userText.take(40)
        onEvent(AgentEvent("round", "round ${s.rounds}/${keys.maxRounds}"))
        val result = router.complete(s.messages) { att ->
            onEvent(AgentEvent("route", "${att.provider}/${att.model} (${att.note})"))
        }
        if (result.reasoning.isNotBlank()) {
            onEvent(AgentEvent("think", result.reasoning))
        }
        s.messages.add(ChatMessage("assistant", result.text, result.reasoning))
        val applied = applyActions(result.text, onEvent)
        sessions.save(s)
        return if (applied.isBlank()) result.text else "${result.text}\n\n--- applied ---\n$applied"
    }

    private fun applyActions(reply: String, onEvent: (AgentEvent) -> Unit): String {
        val ws = AndroApp.instance.workspace
        val s = sessions.current
        val log = StringBuilder()
        fun note(k: String, t: String) {
            log.appendLine(t)
            onEvent(AgentEvent(k, t))
        }
        Regex("```write\\s+([^\\n]+)\\n([\\s\\S]*?)```").findAll(reply).forEach { m ->
            ws.write(m.groupValues[1].trim(), m.groupValues[2])
            note("write", "WRITE ${m.groupValues[1].trim()}")
        }
        Regex("```delete\\s+([^\\n`]+)").findAll(reply).forEach { m ->
            ws.delete(m.groupValues[1].trim())
            note("delete", "DELETE ${m.groupValues[1].trim()}")
        }
        Regex("```read\\s+([^\\n`]+)").findAll(reply).forEach { m ->
            val path = m.groupValues[1].trim()
            val content = ws.read(path)
            s.messages.add(ChatMessage("user", "FILE $path:\n${content.take(8000)}"))
            note("read", "READ $path")
        }
        Regex("```move\\s+(.+?)\\s*->\\s*([^\\n`]+)").findAll(reply).forEach { m ->
            val ok = ws.move(m.groupValues[1].trim(), m.groupValues[2].trim())
            note("move", "MOVE ${m.groupValues[1].trim()} → ${m.groupValues[2].trim()} ($ok)")
        }
        Regex("```template\\s+([^\\n`]+)").findAll(reply).forEach { m ->
            note("tmpl", Templates.apply(m.groupValues[1].trim()))
        }
        Regex("```image\\s+([^|\\n]+)\\|\\s*([^\\n`]+)").findAll(reply).forEach { m ->
            val path = m.groupValues[1].trim()
            val prompt = m.groupValues[2].trim()
            val bytes = ImageGen.generate(prompt)
            ws.writeBytes(path, bytes)
            note("art", "IMAGE $path ${bytes.size}b")
        }
        Regex("```fetch\\s+(https?://[^\\s`]+)").findAll(reply).forEach { m ->
            val url = m.groupValues[1].trim()
            val text = WebFetch.pageText(url)
            s.messages.add(ChatMessage("user", "WEB $url:\n$text"))
            note("web", "FETCH $url (${text.length})")
        }
        Regex("```github\\s+list\\s*```").findAll(reply).forEach {
            val repos = gh.listRepos().joinToString("\n") { r -> r.fullName }
            s.messages.add(ChatMessage("user", "REPOS:\n$repos"))
            note("github", repos.take(600))
        }
        Regex("```github\\s+clone\\s+([^\\n`]+)").findAll(reply).forEach { m ->
            gh.cloneRepo(m.groupValues[1].trim())
            note("github", "CLONE ${m.groupValues[1].trim()}")
        }
        Regex("```github\\s+bind\\s+([^\\n`]+)").findAll(reply).forEach { m ->
            gh.bindRemote(m.groupValues[1].trim())
            note("github", "BIND ${m.groupValues[1].trim()}")
        }
        Regex("```github\\s+commit\\s+([^\\n`]+)").findAll(reply).forEach { m ->
            note("github", gh.commitAndPush(m.groupValues[1].trim()))
        }
        Regex("```github\\s+release\\s+(\\S+)\\s+([^\\n]+)\\n([\\s\\S]*?)```").findAll(reply).forEach { m ->
            note("github", gh.createRelease(m.groupValues[1].trim(), m.groupValues[2].trim(), m.groupValues[3].trim()))
        }
        Regex("```github\\s+workflow\\s+([^\\n`]+)").findAll(reply).forEach { m ->
            note("github", gh.dispatchWorkflow(m.groupValues[1].trim()))
        }
        Regex("```local\\s+pull\\s+([^\\n`]+)").findAll(reply).forEach { m ->
            val name = m.groupValues[1].trim()
            val msg = try {
                LocalModels.ollamaPull(name)
            } catch (e: Exception) {
                try {
                    LocalModels.download(name) { note("local", it) }
                    "downloaded $name"
                } catch (e2: Exception) {
                    "local fail: ${e.message} / ${e2.message}"
                }
            }
            note("local", msg)
        }
        Regex("```plugin\\s+(\\S+)\\s*([^\\n`]*)").findAll(reply).forEach { m ->
            note("plugin", plugins.run(m.groupValues[1], m.groupValues[2].trim()))
        }
        return log.toString().trim()
    }
}
