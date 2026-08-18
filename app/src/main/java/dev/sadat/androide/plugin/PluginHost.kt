package dev.sadat.androide.plugin

import dev.sadat.androide.AndroApp
import org.json.JSONObject
import java.io.File

data class Plugin(val id: String, val name: String, val on: String, val command: String, val appendSystem: String)

class PluginHost {
    fun list(): List<Plugin> {
        val dir = AndroApp.instance.workspace.pluginsDir
        return dir.listFiles()?.filter { it.extension == "json" }?.mapNotNull { parse(it) } ?: emptyList()
    }

    fun extraSystem(): String {
        val extra = list().filter { it.appendSystem.isNotBlank() }.joinToString("\n") { "- ${it.name}: ${it.appendSystem}" }
        val cmds = list().filter { it.command.isNotBlank() }.joinToString(", ") { it.command }
        return buildString {
            if (extra.isNotBlank()) appendLine("Plugins:\n$extra")
            if (cmds.isNotBlank()) appendLine("Custom plugin commands: $cmds via ```plugin name args```")
        }
    }

    fun invokeCommand(name: String, args: String): String {
        val p = list().firstOrNull { it.command.equals(name, true) || it.id.equals(name, true) }
            ?: return "plugin not found: $name"
        val log = File(AndroApp.instance.workspace.pluginsDir, "${p.id}.log")
        log.appendText("${System.currentTimeMillis()} $name $args\n")
        return "plugin ${p.name} recorded args=$args"
    }

    fun installExample() {
        val f = File(AndroApp.instance.workspace.pluginsDir, "example-lint.json")
        if (f.exists()) return
        f.writeText(
            """
            {
              "id": "example-lint",
              "name": "Example lint",
              "on": "after",
              "command": "lint",
              "appendSystem": "Prefer complete playable files. After writes, keep index.html runnable."
            }
            """.trimIndent()
        )
    }

    private fun parse(f: File): Plugin? = try {
        val o = JSONObject(f.readText())
        Plugin(o.optString("id", f.nameWithoutExtension), o.optString("name"), o.optString("on"), o.optString("command"), o.optString("appendSystem"))
    } catch (_: Exception) {
        null
    }
}
