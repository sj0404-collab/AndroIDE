package dev.sadat.androide.github

import dev.sadat.androide.AndroApp
import dev.sadat.androide.workspace.Workspace
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class Repo(val fullName: String, val cloneUrl: String, val defaultBranch: String, val private: Boolean)

class GitHubClient(private val ws: Workspace) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun token(): String {
        val t = AndroApp.instance.keys.githubToken
        if (t.isBlank()) throw RuntimeException("GitHub token is empty. Paste a PAT (repo scope) in Settings.")
        return t
    }

    private fun api(path: String, method: String = "GET", body: String? = null): String {
        val b = Request.Builder()
            .url("https://api.github.com$path")
            .addHeader("Authorization", "Bearer ${token()}")
            .addHeader("Accept", "application/vnd.github+json")
            .addHeader("X-GitHub-Api-Version", "2022-11-28")
            .addHeader("User-Agent", "AndroIDE")
        val req = when (method) {
            "POST" -> b.post((body ?: "{}").toRequestBody(jsonType))
            "PUT" -> b.put((body ?: "{}").toRequestBody(jsonType))
            "PATCH" -> b.patch((body ?: "{}").toRequestBody(jsonType))
            "DELETE" -> b.delete((body ?: "{}").toRequestBody(jsonType))
            else -> b.get()
        }.build()
        val resp = http.newCall(req).execute()
        val text = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) throw RuntimeException("GitHub $method $path → ${resp.code}: ${text.take(700)}")
        return text
    }

    fun whoami(): String {
        val o = JSONObject(api("/user"))
        return o.optString("login") + " (" + o.optString("name") + ")"
    }

    fun listRepos(page: Int = 1): List<Repo> {
        val arr = JSONArray(api("/user/repos?per_page=50&sort=updated&page=$page"))
        val out = mutableListOf<Repo>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                Repo(
                    o.getString("full_name"),
                    o.getString("clone_url"),
                    o.optString("default_branch", "main"),
                    o.optBoolean("private")
                )
            )
        }
        return out
    }

    fun cloneRepo(fullName: String, projectName: String? = null): File {
        val name = projectName ?: fullName.substringAfter("/")
        val dest = ws.openOrCreate(name)
        dest.listFiles()?.forEach { it.deleteRecursively() }
        pullTree(fullName, dest)
        File(dest, ".androide-remote").writeText(fullName)
        return dest
    }

    fun currentRemote(): String {
        val f = File(ws.currentProject, ".androide-remote")
        return if (f.exists()) f.readText().trim() else ""
    }

    fun bindRemote(fullName: String) {
        File(ws.currentProject, ".androide-remote").writeText(fullName.trim())
    }

    private fun pullTree(fullName: String, dest: File) {
        val repo = JSONObject(api("/repos/$fullName"))
        val branch = repo.optString("default_branch", "main")
        val ref = JSONObject(api("/repos/$fullName/git/ref/heads/$branch"))
        val sha = ref.getJSONObject("object").getString("sha")
        val tree = JSONObject(api("/repos/$fullName/git/trees/$sha?recursive=1"))
        val items = tree.getJSONArray("tree")
        for (i in 0 until items.length()) {
            val n = items.getJSONObject(i)
            if (n.getString("type") != "blob") continue
            val path = n.getString("path")
            val blob = JSONObject(api("/repos/$fullName/contents/${path.encodeUrl()}"))
            val content = if (blob.optString("encoding") == "base64") {
                android.util.Base64.decode(blob.getString("content").replace("\n", ""), android.util.Base64.DEFAULT)
                    .toString(Charsets.UTF_8)
            } else blob.optString("content")
            File(dest, path).apply { parentFile?.mkdirs(); writeText(content) }
        }
    }

    fun commitAndPush(message: String): String {
        val full = currentRemote()
        if (full.isBlank()) throw RuntimeException("No remote bound. Clone a repo or bind owner/name.")
        val repo = JSONObject(api("/repos/$full"))
        val branch = repo.optString("default_branch", "main")
        val ref = JSONObject(api("/repos/$full/git/ref/heads/$branch"))
        val parent = ref.getJSONObject("object").getString("sha")
        val baseCommit = JSONObject(api("/repos/$full/git/commits/$parent"))
        val baseTree = baseCommit.getJSONObject("tree").getString("sha")

        val blobs = JSONArray()
        ws.listFiles().forEach { f ->
            val rel = f.relativeTo(ws.currentProject).path.replace('\\', '/')
            if (rel.startsWith(".androide") || rel.startsWith(".git")) return@forEach
            val content = f.readText()
            val blob = JSONObject(api("/repos/$full/git/blobs", "POST",
                JSONObject().put("content", content).put("encoding", "utf-8").toString()))
            blobs.put(
                JSONObject()
                    .put("path", rel)
                    .put("mode", "100644")
                    .put("type", "blob")
                    .put("sha", blob.getString("sha"))
            )
        }
        if (blobs.length() == 0) throw RuntimeException("Nothing to commit")
        val tree = JSONObject(api("/repos/$full/git/trees", "POST",
            JSONObject().put("base_tree", baseTree).put("tree", blobs).toString()))
        val commit = JSONObject(
            api(
                "/repos/$full/git/commits", "POST",
                JSONObject()
                    .put("message", message)
                    .put("tree", tree.getString("sha"))
                    .put("parents", JSONArray().put(parent))
                    .toString()
            )
        )
        val sha = commit.getString("sha")
        api(
            "/repos/$full/git/refs/heads/$branch", "PATCH",
            JSONObject().put("sha", sha).put("force", false).toString()
        )
        return "Pushed $sha to $full@$branch"
    }

    fun createRepo(name: String, privateRepo: Boolean = false): Repo {
        val body = JSONObject().put("name", name).put("private", privateRepo)
            .put("auto_init", true).put("description", "AndroIDE project")
        val o = JSONObject(api("/user/repos", "POST", body.toString()))
        return Repo(o.getString("full_name"), o.getString("clone_url"), o.optString("default_branch", "main"), o.optBoolean("private"))
    }

    fun createRelease(tag: String, name: String, body: String): String {
        val full = currentRemote()
        if (full.isBlank()) throw RuntimeException("Bind a remote first")
        val o = JSONObject(
            api(
                "/repos/$full/releases", "POST",
                JSONObject().put("tag_name", tag).put("name", name).put("body", body)
                    .put("generate_release_notes", true).toString()
            )
        )
        return o.optString("html_url")
    }

    fun dispatchWorkflow(workflow: String = "android.yml", inputs: Map<String, String> = emptyMap()): String {
        val full = currentRemote()
        if (full.isBlank()) throw RuntimeException("Bind a remote first")
        val repo = JSONObject(api("/repos/$full"))
        val branch = repo.optString("default_branch", "main")
        val body = JSONObject().put("ref", branch)
        if (inputs.isNotEmpty()) {
            val inn = JSONObject()
            inputs.forEach { (k, v) -> inn.put(k, v) }
            body.put("inputs", inn)
        }
        api("/repos/$full/actions/workflows/$workflow/dispatches", "POST", body.toString())
        return "Dispatched $workflow on $full@$branch inputs=$inputs"
    }

    fun latestRun(workflowHint: String = ""): JSONObject? {
        val full = currentRemote()
        val arr = JSONObject(api("/repos/$full/actions/runs?per_page=5")).optJSONArray("workflow_runs") ?: return null
        if (arr.length() == 0) return null
        if (workflowHint.isBlank()) return arr.getJSONObject(0)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("path").contains(workflowHint) || o.optString("name").contains(workflowHint, true)) return o
        }
        return arr.getJSONObject(0)
    }

    fun runLogs(runId: Long): String {
        val full = currentRemote()
        val jobs = JSONObject(api("/repos/$full/actions/runs/$runId/jobs")).optJSONArray("jobs") ?: JSONArray()
        val sb = StringBuilder()
        for (i in 0 until jobs.length()) {
            val j = jobs.getJSONObject(i)
            sb.appendLine("JOB ${j.optString("name")} ${j.optString("status")} ${j.optString("conclusion")}")
            val steps = j.optJSONArray("steps") ?: JSONArray()
            for (s in 0 until steps.length()) {
                val st = steps.getJSONObject(s)
                sb.appendLine("  - ${st.optString("name")}: ${st.optString("conclusion")}")
            }
        }
        return sb.toString()
    }

    fun apiGet(path: String): String = api(path)

    fun remoteTree(full: String): String {
        val repo = JSONObject(api("/repos/$full"))
        val branch = repo.optString("default_branch", "main")
        val ref = JSONObject(api("/repos/$full/git/ref/heads/$branch"))
        val sha = ref.getJSONObject("object").getString("sha")
        val tree = JSONObject(api("/repos/$full/git/trees/$sha?recursive=1"))
        val items = tree.getJSONArray("tree")
        val sb = StringBuilder()
        sb.appendLine("| path | type | size |")
        sb.appendLine("|---|---|---|")
        for (i in 0 until items.length()) {
            val n = items.getJSONObject(i)
            sb.appendLine("| ${n.optString("path")} | ${n.optString("type")} | ${n.optInt("size")} |")
        }
        return sb.toString()
    }

    fun remoteRead(full: String, path: String): String {
        val blob = JSONObject(api("/repos/$full/contents/${path.encodeUrl()}"))
        return if (blob.optString("encoding") == "base64") {
            String(android.util.Base64.decode(blob.getString("content").replace("\n", ""), android.util.Base64.DEFAULT))
        } else blob.optString("content")
    }

    fun remoteWrite(full: String, path: String, content: String, message: String): String {
        val enc = android.util.Base64.encodeToString(content.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        val body = JSONObject().put("message", message).put("content", enc)
        try {
            val old = JSONObject(api("/repos/$full/contents/${path.encodeUrl()}"))
            body.put("sha", old.getString("sha"))
        } catch (_: Exception) { }
        val o = JSONObject(api("/repos/$full/contents/${path.encodeUrl()}", "PUT", body.toString()))
        val sha = o.optJSONObject("commit")?.optString("sha") ?: "?"
        return "committed $path @$sha ($full)"
    }

    fun remoteDelete(full: String, path: String, message: String): String {
        val old = JSONObject(api("/repos/$full/contents/${path.encodeUrl()}"))
        api(
            "/repos/$full/contents/${path.encodeUrl()}",
            "DELETE",
            JSONObject().put("message", message).put("sha", old.getString("sha")).toString()
        )
        return "deleted $path on $full"
    }

    private fun String.encodeUrl(): String = java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")
}
