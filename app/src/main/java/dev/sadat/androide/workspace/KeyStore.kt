package dev.sadat.androide.workspace

import android.content.Context
import android.content.SharedPreferences

class KeyStore(ctx: Context) {
    private val p: SharedPreferences =
        ctx.getSharedPreferences("androide.keys", Context.MODE_PRIVATE)

    var provider: String
        get() = p.getString("provider", "zen") ?: "zen"
        set(v) { p.edit().putString("provider", v).apply() }

    var model: String
        get() = p.getString("model", "big-pickle") ?: "big-pickle"
        set(v) { p.edit().putString("model", v).apply() }

    var githubToken: String
        get() = p.getString("github", "") ?: ""
        set(v) { p.edit().putString("github", v.trim()).apply() }

    fun getKey(id: String): String = p.getString("k_$id", "") ?: ""
    fun setKey(id: String, value: String) {
        p.edit().putString("k_$id", value.trim()).apply()
    }
}
