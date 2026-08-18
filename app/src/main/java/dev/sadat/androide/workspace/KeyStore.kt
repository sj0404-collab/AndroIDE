package dev.sadat.androide.workspace

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class KeyStore(ctx: Context) {
    private val p: SharedPreferences =
        ctx.getSharedPreferences("androide.keys", Context.MODE_PRIVATE)

    var account: String
        get() = p.getString("account", "default") ?: "default"
        set(v) { p.edit().putString("account", v.trim().ifBlank { "default" }).apply() }

    var provider: String
        get() = p.getString(pref("provider"), "zen") ?: "zen"
        set(v) { p.edit().putString(pref("provider"), v).apply() }

    var model: String
        get() = p.getString(pref("model"), "big-pickle") ?: "big-pickle"
        set(v) { p.edit().putString(pref("model"), v).apply() }

    var githubToken: String
        get() = p.getString(pref("github"), "") ?: ""
        set(v) { p.edit().putString(pref("github"), v.trim()).apply() }

    var localBase: String
        get() = p.getString(pref("localBase"), "http://127.0.0.1:11434/v1/chat/completions")
            ?: "http://127.0.0.1:11434/v1/chat/completions"
        set(v) { p.edit().putString(pref("localBase"), v.trim()).apply() }

    var maxRounds: Int
        get() = p.getInt(pref("maxRounds"), 128)
        set(v) { p.edit().putInt(pref("maxRounds"), v.coerceIn(1, 500)).apply() }

    var autoRotate: Boolean
        get() = p.getBoolean(pref("autoRotate"), true)
        set(v) { p.edit().putBoolean(pref("autoRotate"), v).apply() }

    var showReasoning: Boolean
        get() = p.getBoolean(pref("showReasoning"), true)
        set(v) { p.edit().putBoolean(pref("showReasoning"), v).apply() }

    fun getKey(id: String): String = p.getString(pref("k_$id"), "") ?: ""
    fun setKey(id: String, value: String) {
        p.edit().putString(pref("k_$id"), value.trim()).apply()
    }

    fun accounts(): List<String> {
        val raw = p.getString("accounts", "[\"default\"]") ?: "[\"default\"]"
        val arr = JSONArray(raw)
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) out.add(arr.getString(i))
        if (!out.contains(account)) out.add(account)
        return out.distinct()
    }

    fun addAccount(name: String) {
        val n = name.trim().ifBlank { return }
        val list = (accounts() + n).distinct()
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        p.edit().putString("accounts", arr.toString()).apply()
        account = n
    }

    fun snapshot(): JSONObject = JSONObject()
        .put("account", account)
        .put("provider", provider)
        .put("model", model)
        .put("maxRounds", maxRounds)
        .put("autoRotate", autoRotate)
        .put("localBase", localBase)

    private fun pref(k: String) = "${account}.$k"
}
