package dev.sadat.androide.agent

import dev.sadat.androide.AndroApp
import dev.sadat.androide.ai.ChatMessage
import dev.sadat.androide.ai.ModelRouter
import dev.sadat.androide.github.GitHubClient
import dev.sadat.androide.local.LocalModels
import dev.sadat.androide.log.LogStore
import dev.sadat.androide.net.ImageGen
import dev.sadat.androide.net.WebFetch
import dev.sadat.androide.plugin.PluginHost
import dev.sadat.androide.project.Templates
import dev.sadat.androide.session.SessionStore
import dev.sadat.androide.shell.Shell
import java.util.concurrent.atomic.AtomicBoolean

data class AgentEvent(val kind: String, val text: String)

class AgentEngine(
    private val sessions: SessionStore,
    private val router: ModelRouter = ModelRouter(),
    private val gh: GitHubClient = GitHubClient(AndroApp.instance.workspace),
    private val plugins: PluginHost = PluginHost()
) {
    val stop = AtomicBoolean(false)

    init {
        plugins.installExample()
    }

    fun systemPrompt(): String {
        val ws = AndroApp.instance.workspace
        val keys = AndroApp.instance.keys
        return """
You are AndroIDE Agent. You BUILD real files. Never invent success. Never write stubs, TODOs-as-code, fake APIs, or placeholder functions.
If a command fails, report the real stderr and fix it.
You may write LONG complete source. Prefer full files over snippets.

User reviews, edits, Run. You keep going for many rounds (budget ${keys.maxRounds}).
After each batch propose concrete improvements and continue unless you emit ```halt```.

Current TODO:
${TodoStore.render()}

Project: ${ws.currentProject.name}
Files:
${ws.tree()}
Account=${keys.account} ${keys.provider}/${keys.model}
${plugins.extraSystem()}

Fences (real actions):
```write path
full file
```
```delete path```
```read path```
```move from -> to```
```bash
command
```
```cmd
dir
```
```todo
- [ ] task
- [x] done
```
```template react|3d|2d|kotlin|canvas```
```image assets/icon.png | prompt```
```fetch https://url```
```github list``` ```github clone o/r``` ```github bind o/r```
```github commit message```
```github release v1 Title
notes```
```github workflow android.yml```
```github pty uname -a```
```local pull llama3.2```
```plugin name args```
```halt```

Do not stop after one file. Implement, run bash, read output, fix, update todo.
        """.trimIndent()
    }

    fun runAutonomous(userText: String, onEvent: (AgentEvent) -> Unit): String {
        stop.set(false)
        val keys = AndroApp.instance.keys
        val s = sessions.current
        if (s.messages.none { it.role == "system" }) s.messages.add(ChatMessage("system", systemPrompt()))
        s.messages.add(ChatMessage("user", userText))
        sessions.save(s)
        val all = StringBuilder()
        var idle = 0
        while (!stop.get() && s.rounds < keys.maxRounds) {
            s.rounds += 1
            if (s.title == "New session") s.title = userText.take(48)
            onEvent(AgentEvent("round", "round ${s.rounds}/${keys.maxRounds}"))
            LogStore.add("round", "${s.rounds}/${keys.maxRounds}")
            val streamBuf = StringBuilder()
            val result = router.complete(s.messages, { att ->
                onEvent(AgentEvent("route", "${att.provider}/${att.model} (${att.note})"))
            }, { delta ->
                streamBuf.append(delta)
                onEvent(AgentEvent("stream", delta))
            })
            if (result.reasoning.isNotBlank()) onEvent(AgentEvent("think", result.reasoning))
            val text = result.text.ifBlank { streamBuf.toString() }
            s.messages.add(ChatMessage("assistant", text, result.reasoning))
            val applied = applyActions(text, onEvent)
            sessions.save(s)
            all.append("\n\n--- round ${s.rounds} ---\n").append(text)
            if (applied.isNotBlank()) all.append("\n").append(applied)
            val halt = text.contains("```halt")
            if (halt) {
                onEvent(AgentEvent("status", "agent halted"))
                break
            }
            if (applied.isBlank()) idle++ else idle = 0
            if (idle >= 2) {
                s.messages.add(
                    ChatMessage(
                        "user",
                        "Continue. Propose 3 concrete improvements and implement the first two now. Update todo. Do not halt unless the project runs. Current todo:\n${TodoStore.render()}\nFiles:\n${AndroApp.instance.workspace.tree()}"
                    )
                )
            } else {
                s.messages.add(
                    ChatMessage(
                        "user",
                        "Keep going. Fix anything broken. Implement next todo. Do not write stubs. Files:\n${AndroApp.instance.workspace.tree()}\n${TodoStore.render()}"
                    )
                )
            }
        }
        sessions.save(s)
        return all.toString()
    }

    private fun applyActions(reply: String, onEvent: (AgentEvent) -> Unit): String {
        val ws = AndroApp.instance.workspace
        val s = sessions.current
        val log = StringBuilder()
        fun note(k: String, t: String) {
            log.appendLine(t)
            LogStore.add(k, t)
            onEvent(AgentEvent(k, t))
        }
        Regex("```todo\\n([\\s\\S]*?)```").findAll(reply).forEach { m ->
            val lines = m.groupValues[1].lines().filter { it.isNotBlank() }
            TodoStore.replaceAll(lines)
            note("todo", TodoStore.render())
        }
        Regex("```write\\s+([^\\n]+)\\n([\\s\\S]*?)```").findAll(reply).forEach { m ->
            ws.write(m.groupValues[1].trim(), m.groupValues[2])
            note("write", "WRITE ${m.groupValues[1].trim()} (${m.groupValues[2].length} chars)")
        }
        Regex("```delete\\s+([^\\n`]+)").findAll(reply).forEach { m ->
            ws.delete(m.groupValues[1].trim())
            note("delete", "DELETE ${m.groupValues[1].trim()}")
        }
        Regex("```read\\s+([^\\n`]+)").findAll(reply).forEach { m ->
            val path = m.groupValues[1].trim()
            val content = ws.read(path)
            s.messages.add(ChatMessage("user", "FILE $path:\n${content.take(16000)}"))
            note("read", "READ $path (${content.length})")
        }
        Regex("```move\\s+(.+?)\\s*->\\s*([^\\n`]+)").findAll(reply).forEach { m ->
            val ok = ws.move(m.groupValues[1].trim(), m.groupValues[2].trim())
            note("move", "MOVE ${m.groupValues[1].trim()} -> ${m.groupValues[2].trim()} $ok")
        }
        Regex("```(?:bash|cmd|sh)\\n([\\s\\S]*?)```").findAll(reply).forEach { m ->
            val cmd = m.groupValues[1].trim()
            val out = Shell.run(cmd)
            s.messages.add(ChatMessage("user", "SHELL $cmd\n$out"))
            note("term", out.take(2000))
        }
        Regex("```template\\s+([^\\n`]+)").findAll(reply).forEach { m ->
            note("tmpl", Templates.apply(m.groupValues[1].trim()))
        }
        Regex("```image\\s+([^|\\n]+)\\|\\s*([^\\n`]+)").findAll(reply).forEach { m ->
            val path = m.groupValues[1].trim()
            val bytes = ImageGen.generate(m.groupValues[2].trim())
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
            note("github", repos.take(800))
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
        Regex("```github\\s+pty\\s+([^\\n`]+)").findAll(reply).forEach { m ->
            val cmd = m.groupValues[1].trim()
            val dispatched = gh.dispatchWorkflow("pty.yml", mapOf("command" to cmd, "chat" to "androide"))
            Thread.sleep(4000)
            val run = gh.latestRun("pty")
            val extra = if (run != null) gh.runLogs(run.getLong("id")) else "run not visible yet"
            s.messages.add(ChatMessage("user", "PTY $dispatched\n$extra"))
            note("pty", "$dispatched\n$extra")
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
            note("plugin", plugins.invokeCommand(m.groupValues[1], m.groupValues[2].trim()))
        }
        return log.toString().trim()
    }
}
