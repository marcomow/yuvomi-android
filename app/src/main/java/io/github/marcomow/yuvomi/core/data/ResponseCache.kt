package io.github.marcomow.yuvomi.core.data

import java.io.File
import java.security.MessageDigest

/**
 * The read cache of ADR 0007: the last good answer per screen, as JSON text,
 * scoped to server and user. Plain files are enough for "show the last state
 * while offline"; there are no queries to run over it.
 */
class ResponseCache(private val root: File) {

    data class Entry(val json: String, val savedAt: Long)

    private var scope: File? = null

    /** Selects the folder for this server and user. */
    fun open(server: String, userId: Int) {
        scope = File(root, digest("$server#$userId")).apply { mkdirs() }
    }

    fun close() {
        scope = null
    }

    fun read(key: String): Entry? {
        val file = fileFor(key) ?: return null
        if (!file.exists()) return null
        return runCatching { Entry(file.readText(), file.lastModified()) }.getOrNull()
    }

    fun write(key: String, json: String) {
        val file = fileFor(key) ?: return
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json)
            tmp.renameTo(file)
        }
    }

    /** Small app-wide entries outside any user scope (the last session snapshot). */
    fun readGlobal(key: String): String? = File(root, "$key.json").takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }

    fun writeGlobal(key: String, json: String) {
        root.mkdirs()
        runCatching { File(root, "$key.json").writeText(json) }
    }

    fun deleteGlobal(key: String) {
        File(root, "$key.json").delete()
    }

    /** Sign-out deletes every cached row for that server and user (ADR 0007). */
    fun clear(server: String, userId: Int) {
        File(root, digest("$server#$userId")).deleteRecursively()
        scope = null
    }

    private fun fileFor(key: String): File? = scope?.let { File(it, key.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".json") }

    private fun digest(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
}
