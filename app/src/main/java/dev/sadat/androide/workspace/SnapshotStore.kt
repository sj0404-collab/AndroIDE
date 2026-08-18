package dev.sadat.androide.workspace

import org.json.JSONObject
import java.io.File

data class Snap(val id: String, val label: String, val dir: File)

class SnapshotStore(private val ws: Workspace, root: File) {
    private val base = File(root, "snapshots").apply { mkdirs() }

    fun take(label: String): Snap {
        val id = System.currentTimeMillis().toString()
        val dir = File(base, "${ws.currentProject.name}/$id").apply { mkdirs() }
        ws.currentProject.copyRecursively(File(dir, "files"), overwrite = true)
        File(dir, "meta.json").writeText(JSONObject().put("label", label).put("id", id).toString())
        trim(File(base, ws.currentProject.name), keep = 30)
        return Snap(id, label, dir)
    }

    fun list(): List<Snap> {
        val p = File(base, ws.currentProject.name)
        return p.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }?.map {
            val label = try {
                JSONObject(File(it, "meta.json").readText()).optString("label", it.name)
            } catch (_: Exception) {
                it.name
            }
            Snap(it.name, label, it)
        } ?: emptyList()
    }

    fun restore(id: String): Boolean {
        val dir = File(base, "${ws.currentProject.name}/$id/files")
        if (!dir.exists()) return false
        ws.currentProject.listFiles()?.forEach { it.deleteRecursively() }
        dir.copyRecursively(ws.currentProject, overwrite = true)
        return true
    }

    private fun trim(folder: File, keep: Int) {
        val all = folder.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name } ?: return
        all.drop(keep).forEach { it.deleteRecursively() }
    }
}
