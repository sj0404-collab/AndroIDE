package dev.sadat.androide.preset

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Preset(val id: String, var title: String, var body: String)

class PresetStore(ctx: Context) {
    private val p = ctx.getSharedPreferences("androide.presets", Context.MODE_PRIVATE)

    var activeId: String
        get() = p.getString("active", "github-direct") ?: "github-direct"
        set(v) { p.edit().putString("active", v).apply() }

    fun all(): MutableList<Preset> {
        val raw = p.getString("list", null)
        if (raw.isNullOrBlank()) {
            val d = defaults()
            saveAll(d)
            return d
        }
        val arr = JSONArray(raw)
        val out = mutableListOf<Preset>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(Preset(o.getString("id"), o.optString("title"), o.optString("body")))
        }
        if (out.none { it.id == "github-direct" }) out.add(0, defaults()[0])
        return out
    }

    fun active(): Preset = all().firstOrNull { it.id == activeId } ?: all().first()

    fun upsert(pr: Preset) {
        val list = all()
        val i = list.indexOfFirst { it.id == pr.id }
        if (i >= 0) list[i] = pr else list.add(pr)
        saveAll(list)
        activeId = pr.id
    }

    private fun saveAll(list: List<Preset>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("title", it.title).put("body", it.body))
        }
        p.edit().putString("list", arr.toString()).apply()
    }

    companion object {
        fun defaults() = mutableListOf(
            Preset(
                "github-direct",
                "GitHub напрямую (без клона)",
                """PRESET github-direct:
Use the user's GitHub token. Do NOT clone. Do NOT write local project files unless asked.
Work only with github-remote tools:
```gh-tree owner/repo```
```gh-read owner/repo path```
```gh-write owner/repo path | commit message
file contents
```
```gh-rm owner/repo path | commit message```
```github list```
```github workflow name.yml```
Summarize changes in a markdown TABLE: path | action | sha/note.
Push is the Contents API commit itself — no local git."""
            ),
            Preset(
                "local-dev",
                "Локальный проект + Run",
                """PRESET local-dev:
Write files into the device workspace. Use write/read/bash/todo. Prefer complete playable index.html. Use github commit only if a remote is bound."""
            ),
            Preset(
                "runner-6h",
                "GitHub runner 6ч",
                """PRESET runner-6h:
Prefer github workflow / github pty / runner tools. Do not pretend a process is running if dispatch failed."""
            )
        )
    }
}
