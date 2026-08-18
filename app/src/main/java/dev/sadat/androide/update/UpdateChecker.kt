package dev.sadat.androide.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import dev.sadat.androide.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class ReleaseInfo(val tag: String, val name: String, val apkUrl: String, val notes: String)

class UpdateChecker(private val ctx: Context) {
    private val http = OkHttpClient.Builder().followRedirects(true).readTimeout(180, TimeUnit.SECONDS).build()

    var repo: String = "sj0404-collab/AndroIDE"

    fun latest(): ReleaseInfo? {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases/latest")
            .header("User-Agent", "AndroIDE")
            .header("Accept", "application/vnd.github+json")
            .build()
        val resp = http.newCall(req).execute()
        val text = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) {
            val tags = http.newCall(
                Request.Builder().url("https://api.github.com/repos/$repo/releases?per_page=1")
                    .header("User-Agent", "AndroIDE").build()
            ).execute().body?.string().orEmpty()
            val arr = org.json.JSONArray(if (tags.startsWith("[")) tags else "[]")
            if (arr.length() == 0) throw RuntimeException("no releases yet HTTP ${resp.code}")
            return parse(arr.getJSONObject(0))
        }
        return parse(JSONObject(text))
    }

    private fun parse(o: JSONObject): ReleaseInfo {
        val assets = o.optJSONArray("assets") ?: org.json.JSONArray()
        var url = ""
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val n = a.optString("name")
            if (n.endsWith(".apk")) url = a.getString("browser_download_url")
        }
        if (url.isBlank()) throw RuntimeException("release has no APK")
        return ReleaseInfo(o.optString("tag_name"), o.optString("name"), url, o.optString("body"))
    }

    fun isNewer(tag: String): Boolean {
        val remote = tag.removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }
        val local = BuildConfig.VERSION_NAME.split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(remote.size, local.size)) {
            val r = remote.getOrElse(i) { 0 }
            val l = local.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }

    fun downloadApk(url: String, onProg: (String) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val dest = File(dir, "AndroIDE-update.apk")
        onProg("downloading update…")
        val resp = http.newCall(Request.Builder().url(url).header("User-Agent", "AndroIDE").build()).execute()
        if (!resp.isSuccessful) throw RuntimeException("apk HTTP ${resp.code}")
        dest.outputStream().use { out -> resp.body!!.byteStream().copyTo(out) }
        onProg("downloaded ${dest.length()} bytes")
        return dest
    }

    fun install(apk: File) {
        val uri: Uri = FileProvider.getUriForFile(ctx, "dev.sadat.androide.files", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }
}
