package dev.sadat.androide.workspace

import android.content.Context
import java.io.File

class Workspace(ctx: Context) {
    val root: File = File(ctx.filesDir, "workspace").apply { mkdirs() }
    var currentProject: File = File(root, "default").apply { mkdirs() }

    fun listProjects(): List<File> =
        root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() } ?: emptyList()

    fun openOrCreate(name: String): File {
        val dir = File(root, sanitize(name)).apply { mkdirs() }
        currentProject = dir
        return dir
    }

    fun listFiles(dir: File = currentProject): List<File> {
        val out = mutableListOf<File>()
        walk(dir, out)
        return out.sortedBy { it.relativeTo(currentProject).path }
    }

    private fun walk(dir: File, out: MutableList<File>) {
        dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
            if (f.isDirectory) walk(f, out) else out.add(f)
        }
    }

    fun read(rel: String): String {
        val f = resolve(rel)
        return if (f.exists()) f.readText() else ""
    }

    fun write(rel: String, content: String): File {
        val f = resolve(rel)
        f.parentFile?.mkdirs()
        f.writeText(content)
        return f
    }

    fun delete(rel: String): Boolean {
        val f = resolve(rel)
        return f.exists() && f.delete()
    }

    fun resolve(rel: String): File {
        val clean = rel.trim().removePrefix("/").replace("..", "")
        return File(currentProject, clean)
    }

    fun tree(max: Int = 400): String {
        val files = listFiles()
        if (files.isEmpty()) return "(empty project ${currentProject.name})"
        return files.take(max).joinToString("\n") { it.relativeTo(currentProject).path }
    }

    private fun sanitize(name: String): String =
        name.trim().ifBlank { "project" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
