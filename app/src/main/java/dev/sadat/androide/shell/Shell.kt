package dev.sadat.androide.shell

import dev.sadat.androide.AndroApp
import dev.sadat.androide.log.LogStore
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object Shell {
    private val current = AtomicReference<Process?>(null)

    fun kill() {
        current.getAndSet(null)?.destroyForcibly()
    }

    fun run(command: String, timeoutSec: Long = 120, cwd: File = AndroApp.instance.workspace.currentProject): String {
        LogStore.add("term", "$ $command")
        cwd.mkdirs()
        val pb = ProcessBuilder("sh", "-c", command)
            .directory(cwd)
            .redirectErrorStream(true)
        val env = pb.environment()
        env["HOME"] = cwd.absolutePath
        env["TMPDIR"] = AndroApp.instance.cacheDir.absolutePath
        val p = pb.start()
        current.set(p)
        val out = StringBuilder()
        val reader = Thread {
            p.inputStream.bufferedReader().forEachLine { line ->
                out.appendLine(line)
                LogStore.add("term", line)
            }
        }
        reader.start()
        val finished = p.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!finished) {
            p.destroyForcibly()
            val msg = "TIMEOUT after ${timeoutSec}s"
            LogStore.add("term", msg)
            current.set(null)
            return out.toString() + "\n" + msg
        }
        reader.join(2000)
        current.set(null)
        val code = p.exitValue()
        LogStore.add("term", "exit $code")
        return out.toString() + "\nexit $code"
    }
}
