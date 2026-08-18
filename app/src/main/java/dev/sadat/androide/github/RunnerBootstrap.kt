package dev.sadat.androide.github

import dev.sadat.androide.AndroApp
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Creates androide-runner on the account of the CURRENT token (any login),
 * writes workflows, dispatches a 6h GGUF job. Not limited to sj0404-collab/AndroIDE.
 */
class RunnerBootstrap {
    private val http = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
    private val json = "application/json; charset=utf-8".toMediaType()

    private fun token(): String {
        val t = AndroApp.instance.keys.githubToken
        if (t.isBlank()) throw RuntimeException("Paste a PAT with repo + workflow on the account you want.")
        return t
    }

    private fun api(path: String, method: String = "GET", body: String? = null): String {
        val b = Request.Builder().url("https://api.github.com$path")
            .addHeader("Authorization", "Bearer ${token()}")
            .addHeader("Accept", "application/vnd.github+json")
            .addHeader("User-Agent", "AndroIDE")
        val req = when (method) {
            "POST" -> b.post((body ?: "{}").toRequestBody(json))
            "PUT" -> b.put((body ?: "{}").toRequestBody(json))
            else -> b.get()
        }.build()
        val resp = http.newCall(req).execute()
        val text = resp.body?.string().orEmpty()
        if (!resp.isSuccessful && !(method == "POST" && resp.code == 422)) {
            throw RuntimeException("GitHub $method $path → ${resp.code}: ${text.take(400)}")
        }
        return text
    }

    fun login(): String = JSONObject(api("/user")).getString("login")

    fun ensureRepo(): String {
        val me = login()
        val name = "androide-runner"
        val full = "$me/$name"
        try {
            api("/repos/$full")
        } catch (_: Exception) {
            api(
                "/user/repos", "POST",
                JSONObject().put("name", name).put("private", true)
                    .put("auto_init", true).put("description", "AndroIDE GGUF runner").toString()
            )
        }
        return full
    }

    fun putFile(full: String, path: String, content: String, message: String) {
        val enc = android.util.Base64.encodeToString(content.toByteArray(), android.util.Base64.NO_WRAP)
        val body = JSONObject().put("message", message).put("content", enc)
        try {
            val old = JSONObject(api("/repos/$full/contents/$path"))
            body.put("sha", old.getString("sha"))
        } catch (_: Exception) { }
        api("/repos/$full/contents/$path", "PUT", body.toString())
    }

    fun pushWorkflows(full: String) {
        putFile(full, ".github/workflows/llm.yml", LLM_YML, "androide llm runner")
        putFile(full, "cli/agent.py", CLI_PY, "cli agent no-key")
        putFile(full, "cli/mcp_server.py", MCP_PY, "mcp stdio no-key")
    }

    fun startLlm(full: String, model: String = "tinyllama"): String {
        val repo = JSONObject(api("/repos/$full"))
        val branch = repo.optString("default_branch", "main")
        api(
            "/repos/$full/actions/workflows/llm.yml/dispatches",
            "POST",
            JSONObject().put("ref", branch).put("inputs", JSONObject().put("model", model)).toString()
        )
        AndroApp.instance.keys.provider = "local"
        return "Started 6h GGUF job on $full@$branch model=$model. Poll Actions logs for trycloudflare URL, then set Local URL."
    }

    companion object {
        val LLM_YML = """
name: GGUF 6h
on:
  workflow_dispatch:
    inputs:
      model:
        default: tinyllama
jobs:
  llm:
    runs-on: ubuntu-latest
    timeout-minutes: 360
    steps:
      - uses: actions/checkout@v4
      - name: Fetch llama.cpp server + GGUF
        run: |
          set -e
          curl -L -o llama.tgz https://github.com/ggerganov/llama.cpp/releases/latest/download/llama-b5191-bin-ubuntu-x64.tar.gz || true
          mkdir -p llama && tar -xzf llama.tgz -C llama || true
          BIN=${'$'}(find llama -name llama-server -type f | head -1)
          if [ -z "${'$'}BIN" ]; then
            sudo apt-get update && sudo apt-get install -y build-essential cmake
            git clone --depth 1 https://github.com/ggerganov/llama.cpp.git src
            cmake -S src -B src/build -DGGML_NATIVE=OFF
            cmake --build src/build -j --target llama-server
            BIN=src/build/bin/llama-server
          fi
          echo BIN=${'$'}BIN >> ${'$'}GITHUB_ENV
          curl -L -o model.gguf "https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/resolve/main/tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf"
          ls -lh model.gguf
      - name: Serve + tunnel 6h
        run: |
          set -e
          curl -L -o cloudflared https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64
          chmod +x cloudflared ${'$'}BIN || chmod +x cloudflared
          ${'$'}BIN -m model.gguf --port 8080 --host 127.0.0.1 > llama.log 2>&1 &
          sleep 8
          ./cloudflared tunnel --url http://127.0.0.1:8080 > tunnel.log 2>&1 &
          for i in ${'$'}(seq 1 30); do
            grep -oE 'https://[-a-z0-9]+.trycloudflare.com' tunnel.log && break
            sleep 2
          done
          echo "TUNNEL:" && cat tunnel.log | tail -20
          echo "Use OpenAI base: https://xxxx.trycloudflare.com/v1  (no key / dummy key)"
          sleep 21000
        """.trimIndent()

        val CLI_PY = """
#!/usr/bin/env python3
import json, os, sys, urllib.request
BASE = os.environ.get("OPENAI_BASE", "http://127.0.0.1:8080/v1")
def chat(msg):
    req = urllib.request.Request(
        BASE + "/chat/completions",
        data=json.dumps({"model":"local","messages":[{"role":"user","content":msg}]}).encode(),
        headers={"Content-Type":"application/json","Authorization":"Bearer local"},
    )
    with urllib.request.urlopen(req, timeout=180) as r:
        return json.load(r)["choices"][0]["message"]["content"]
if __name__ == "__main__":
    print(chat(" ".join(sys.argv[1:]) or "hello"))
        """.trimIndent()

        val MCP_PY = """
#!/usr/bin/env python3
# Minimal MCP-style stdio: one JSON line in, one out. No API key.
import json, sys, os, urllib.request
BASE = os.environ.get("OPENAI_BASE", "http://127.0.0.1:8080/v1")
def call(prompt):
    req = urllib.request.Request(
        BASE + "/chat/completions",
        data=json.dumps({"model":"local","messages":[{"role":"user","content":prompt}]}).encode(),
        headers={"Content-Type":"application/json","Authorization":"Bearer local"},
    )
    with urllib.request.urlopen(req, timeout=180) as r:
        return json.load(r)["choices"][0]["message"]["content"]
for line in sys.stdin:
    line=line.strip()
    if not line: continue
    try:
        o=json.loads(line)
        print(json.dumps({"id":o.get("id"),"result":call(o.get("prompt") or o.get("params",{}).get("text",""))}), flush=True)
    except Exception as e:
        print(json.dumps({"error":str(e)}), flush=True)
        """.trimIndent()
    }
}
