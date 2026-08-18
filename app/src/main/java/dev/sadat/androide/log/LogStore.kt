package dev.sadat.androide.log

import java.util.concurrent.CopyOnWriteArrayList

data class LogLine(val kind: String, val text: String, val ts: Long = System.currentTimeMillis())

object LogStore {
    private val lines = CopyOnWriteArrayList<LogLine>()
    private val listeners = CopyOnWriteArrayList<(LogLine) -> Unit>()

    fun add(kind: String, text: String) {
        val l = LogLine(kind, text)
        lines.add(l)
        if (lines.size > 2000) lines.removeAt(0)
        listeners.forEach { it(l) }
    }

    fun all(): List<LogLine> = lines.toList()
    fun dump(): String = lines.joinToString("\n") { "[${it.kind}] ${it.text}" }
    fun listen(fn: (LogLine) -> Unit) { listeners.add(fn) }
    fun unlisten(fn: (LogLine) -> Unit) { listeners.remove(fn) }
}
