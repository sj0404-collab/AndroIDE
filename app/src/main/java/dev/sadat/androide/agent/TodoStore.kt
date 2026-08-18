package dev.sadat.androide.agent

import java.util.concurrent.CopyOnWriteArrayList

data class TodoItem(val id: Int, var text: String, var done: Boolean)

object TodoStore {
    private val items = CopyOnWriteArrayList<TodoItem>()
    private var seq = 1

    fun clear() { items.clear(); seq = 1 }

    fun replaceAll(lines: List<String>) {
        items.clear()
        seq = 1
        lines.filter { it.isNotBlank() }.forEach { add(it.trim().removePrefix("- ").removePrefix("[ ] ").removePrefix("[x] "), it.contains("[x]")) }
    }

    fun add(text: String, done: Boolean = false): TodoItem {
        val t = TodoItem(seq++, text, done)
        items.add(t)
        return t
    }

    fun mark(id: Int, done: Boolean) {
        items.firstOrNull { it.id == id }?.done = done
    }

    fun list(): List<TodoItem> = items.toList()

    fun render(): String =
        if (items.isEmpty()) "(no todos)"
        else items.joinToString("\n") { "${if (it.done) "[x]" else "[ ]"} #${it.id} ${it.text}" }
}
