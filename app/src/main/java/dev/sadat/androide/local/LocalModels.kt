package dev.sadat.androide.local

import dev.sadat.androide.AndroApp
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class LocalModel(val id: String, val title: String, val url: String, val note: String)

object LocalModels {
    val catalog = listOf(
        LocalModel(
            "tinyllama-1.1b",
            "TinyLlama 1.1B Chat Q4 (HF GGUF)",
            "https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/resolve/main/tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf",
            "Small GGUF. Serve with llama.cpp / Ollama after download."
        ),
        LocalModel(
            "phi-3-mini",
            "Phi-3 Mini instruct Q4",
            "https://huggingface.co/microsoft/Phi-3-mini-4k-instruct-gguf/resolve/main/Phi-3-mini-4k-instruct-q4.gguf",
            "Better quality, larger download."
        )
    )

    fun downloaded(): List<File> =
        AndroApp.instance.workspace.modelsDir.listFiles()?.filter { it.isFile }?.toList() ?: emptyList()

    fun download(id: String, onProg: (String) -> Unit): File {
        val m = catalog.first { it.id == id }
        val dest = File(AndroApp.instance.workspace.modelsDir, "${m.id}.gguf")
        onProg("GET ${m.url}")
        val http = OkHttpClient.Builder().followRedirects(true).readTimeout(0, TimeUnit.SECONDS).build()
        val resp = http.newCall(Request.Builder().url(m.url).header("User-Agent", "AndroIDE").build()).execute()
        if (!resp.isSuccessful) throw RuntimeException("download HTTP ${resp.code}")
        dest.outputStream().use { out -> resp.body!!.byteStream().copyTo(out) }
        onProg("saved ${dest.name} ${dest.length()} bytes")
        return dest
    }

    fun ollamaPull(name: String): String {
        val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.SECONDS).build()
        val body = JSONObject().put("name", name).put("stream", false).toString()
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("http://127.0.0.1:11434/api/pull").post(body).build()
        val resp = http.newCall(req).execute()
        return "ollama pull $name → ${resp.code} ${resp.body?.string()?.take(300)}"
    }
}
