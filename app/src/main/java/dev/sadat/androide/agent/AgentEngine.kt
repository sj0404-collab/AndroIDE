package dev.sadat.androide.agent

import dev.sadat.androide.AndroApp
import dev.sadat.androide.ai.AiClient
import dev.sadat.androide.ai.ChatMessage
import dev.sadat.androide.github.GitHubClient

data class AgentEvent(val kind: String, val text: String)

class AgentEngine(
    private val ai: AiClient = AiClient(),
    private val gh: GitHubClient = GitHubClient(AndroApp.instance.workspace)
) {
    private val history = mutableListOf<ChatMessage>()

    fun systemPrompt(): String {
        val ws = AndroApp.instance.workspace
        return """
You are AndroIDE Agent. You write and edit the user's project yourself.
The user only reviews and runs. Do not ask permission for file writes — just do them.

Current project: ${ws.currentProject.name}
Files:
${ws.tree()}

Reply with a short plan, then actions using EXACT fences:

```write path/relative.ext
file contents
```

```delete path/relative.ext
```

```read path/relative.ext
```

```github list
```

```github clone owner/repo
```

```github bind owner/repo
```

```github commit message here
```

```github release v1.0.0 Title
notes
```

```github workflow android.yml
```

You may emit multiple write blocks. Prefer complete working files.
For Android/Unity-like prototypes use Kotlin/Java or HTML5 playable games in index.html.
        """.trimIndent()
    }

    fun reset() {
        history.clear()
    }

    fun run(userText: String, onEvent: (AgentEvent) -> Unit): String {
        val ws = AndroApp.instance.workspace
        if (history.none { it.role == "system" }) {
            history.add(ChatMessage("system", systemPrompt()))
        }
        history.add(ChatMessage("user", userText))
        onEvent(AgentEvent("status", "Calling ${AndroApp.instance.keys.provider} / ${AndroApp.instance.keys.model}…"))
        val reply = ai.complete(history)
        history.add(ChatMessage("assistant", reply))
        val applied = applyActions(reply, onEvent)
        return if (applied.isBlank()) reply else "$reply\n\n--- applied ---\n$applied"
    }

    private fun applyActions(reply: String, onEvent: (AgentEvent) -> Unit): String {
        val ws = AndroApp.instance.workspace
        val log = StringBuilder()
        val writeRe = Regex("```write\\s+([^\\n]+)\\n([\\s\\S]*?)```")
        writeRe.findAll(reply).forEach { m ->
            val path = m.groupValues[1].trim()
            val body = m.groupValues[2]
            ws.write(path, body)
            val line = "WRITE $path (${body.length} chars)"
            log.appendLine(line)
            onEvent(AgentEvent("write", line))
        }
        Regex("```delete\\s+([^\\n`]+)").findAll(reply).forEach { m ->
            val path = m.groupValues[1].trim()
            ws.delete(path)
            log.appendLine("DELETE $path")
            onEvent(AgentEvent("delete", "DELETE $path"))
        }
        Regex("```read\\s+([^\\n`]+)").findAll(reply).forEach { m ->
            val path = m.groupValues[1].trim()
            val content = ws.read(path)
            history.add(ChatMessage("user", "FILE $path:\n$content"))
            log.appendLine("READ $path")
            onEvent(AgentEvent("read", "READ $path (${content.length})"))
        }
        Regex("```github\\s+list\\s*```").findAll(reply).forEach {
            val repos = gh.listRepos().joinToString("\n") { r -> "${r.fullName}  ${if (r.private) "private" else "public"}" }
            history.add(ChatMessage("user", "REPOS:\n$repos"))
            log.appendLine("LIST ${repos.lineSequence().count()} repos")
            onEvent(AgentEvent("github", repos.take(800)))
        }
        Regex("```github\\s+clone\\s+([^\\n`]+)").findAll(reply).forEach { m ->
            val name = m.groupValues[1].trim()
            gh.cloneRepo(name)
            log.appendLine("CLONE $name")
            onEvent(AgentEvent("github", "cloned $name"))
        }
        Regex("```github\\s+bind\\s+([^\\n`]+)").findAll(reply).forEach { m ->
            gh.bindRemote(m.groupValues[1].trim())
            log.appendLine("BIND ${m.groupValues[1].trim()}")
        }
        Regex("```github\\s+commit\\s+([^\\n`]+)").findAll(reply).forEach { m ->
            val res = gh.commitAndPush(m.groupValues[1].trim())
            log.appendLine(res)
            onEvent(AgentEvent("github", res))
        }
        Regex("```github\\s+release\\s+(\\S+)\\s+([^\\n]+)\\n([\\s\\S]*?)```").findAll(reply).forEach { m ->
            val url = gh.createRelease(m.groupValues[1].trim(), m.groupValues[2].trim(), m.groupValues[3].trim())
            log.appendLine("RELEASE $url")
            onEvent(AgentEvent("github", url))
        }
        Regex("```github\\s+workflow\\s+([^\\n`]+)").findAll(reply).forEach { m ->
            val res = gh.dispatchWorkflow(m.groupValues[1].trim())
            log.appendLine(res)
            onEvent(AgentEvent("github", res))
        }
        return log.toString().trim()
    }
}
