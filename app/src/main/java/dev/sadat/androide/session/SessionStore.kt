package dev.sadat.androide.session

import android.content.Context
import dev.sadat.androide.ai.ChatMessage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Session(
    val id: String,
    var title: String,
    var project: String,
    val messages: MutableList<ChatMessage> = mutableListOf(),
    var rounds: Int = 0
)

class SessionStore(ctx: Context) {
    private val dir = File(ctx.filesDir, "sessions").apply { mkdirs() }
    var current: Session = list().firstOrNull() ?: create("New session")

    fun list(): List<Session> =
        dir.listFiles()?.filter { it.extension == "json" }?.mapNotNull { load(it) }
            ?.sortedByDescending { it.messages.lastOrNull()?.ts ?: 0 } ?: emptyList()

    fun create(title: String, project: String = "default"): Session {
        val s = Session(UUID.randomUUID().toString().take(8), title, project)
        current = s
        save(s)
        return s
    }

    fun switchTo(id: String) {
        val s = list().firstOrNull { it.id == id } ?: return
        current = s
    }

    fun save(s: Session = current) {
        val arr = JSONArray()
        s.messages.forEach {
            arr.put(
                JSONObject()
                    .put("role", it.role)
                    .put("content", it.content)
                    .put("reasoning", it.reasoning)
                    .put("ts", it.ts)
                    .put("visible", it.visible)
                    .put("kind", it.kind)
            )
        }
        File(dir, "${s.id}.json").writeText(
            JSONObject()
                .put("id", s.id)
                .put("title", s.title)
                .put("project", s.project)
                .put("rounds", s.rounds)
                .put("messages", arr)
                .toString()
        )
    }

    private fun load(f: File): Session? = try {
        val o = JSONObject(f.readText())
        val msgs = mutableListOf<ChatMessage>()
        val arr = o.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            msgs.add(
                ChatMessage(
                    m.getString("role"),
                    m.optString("content"),
                    m.optString("reasoning"),
                    m.optLong("ts"),
                    m.optBoolean("visible", true),
                    m.optString("kind", "text")
                )
            )
        }
        Session(o.getString("id"), o.optString("title"), o.optString("project"), msgs, o.optInt("rounds"))
    } catch (_: Exception) {
        null
    }
}
