package dev.sadat.androide.local

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import dev.sadat.androide.AndroApp
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

data class ModelSpec(
    val id: String,
    val title: String,
    val kind: String,
    val url: String,
    val fileName: String,
    val note: String
)

object ModelManager {
    val catalog = listOf(
        ModelSpec(
            "tinyllama-q4",
            "TinyLlama 1.1B Q4 GGUF",
            "gguf",
            "https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/resolve/main/tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf",
            "tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf",
            "Small GGUF for llama.cpp / Ollama / GitHub runner. Not an in-app NDK runtime."
        ),
        ModelSpec(
            "phi3-q4",
            "Phi-3 Mini Q4 GGUF",
            "gguf",
            "https://huggingface.co/microsoft/Phi-3-mini-4k-instruct-gguf/resolve/main/Phi-3-mini-4k-instruct-q4.gguf",
            "Phi-3-mini-4k-instruct-q4.gguf",
            "Better quality, larger file."
        )
    )

    fun fileOf(spec: ModelSpec): File = File(AndroApp.instance.workspace.modelsDir, spec.fileName)

    fun installed(): List<ModelSpec> = catalog.filter { fileOf(it).exists() && fileOf(it).length() > 1024 }

    fun isInstalled(id: String): Boolean = catalog.firstOrNull { it.id == id }?.let { fileOf(it).exists() } == true

    fun download(id: String, onProg: (downloaded: Long, total: Long, msg: String) -> Unit): File {
        val spec = catalog.first { it.id == id }
        val dest = fileOf(spec)
        val tmp = File(dest.absolutePath + ".part")
        onProg(0, -1, "connecting ${spec.title}")
        val http = OkHttpClient.Builder().followRedirects(true).readTimeout(0, TimeUnit.SECONDS).build()
        val resp = http.newCall(Request.Builder().url(spec.url).header("User-Agent", "AndroIDE").build()).execute()
        if (!resp.isSuccessful) throw RuntimeException("download HTTP ${resp.code}")
        val total = resp.body!!.contentLength()
        var read = 0L
        resp.body!!.byteStream().use { inp ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = inp.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    read += n
                    onProg(read, total, "${spec.title}: ${read / 1024 / 1024} / ${if (total > 0) total / 1024 / 1024 else "?"} MB")
                }
            }
        }
        tmp.renameTo(dest)
        onProg(dest.length(), dest.length(), "installed ${dest.name} ${dest.length()} bytes")
        notify(AndroApp.instance, "Model ready", "${spec.title} installed")
        return dest
    }

    fun notify(ctx: Context, title: String, text: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("models", "Models", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val n = NotificationCompat.Builder(ctx, "models")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        nm.notify(4201, n)
    }
}
