package com.readershell.core

import android.util.Log
import java.io.File

/**
 * Walks the local root and builds {content_id -> absolute path}. The id function
 * comes from AppConfig so the same code serves ebook (sha256 prefix) and manga
 * (path-based) by swapping the config.
 *
 * NOTE: This uses java.io.File and assumes the local root is a real filesystem
 * path. On Boox with SAF-only access this needs adaptation; the spec §11 flags
 * this as an open item.
 */
class LocalIndex(private val config: AppConfig) {
    @Volatile private var map: Map<String, File> = emptyMap()
    @Volatile var rootPath: String? = null
        private set

    fun setRoot(absolutePath: String) {
        rootPath = absolutePath
        rescan()
    }

    fun rescan() {
        val root = rootPath?.let { File(it) } ?: run {
            Log.w(TAG, "rescan: no root set"); return
        }
        if (!root.isDirectory) {
            Log.w(TAG, "rescan: root not a directory: ${root.absolutePath} " +
                    "(exists=${root.exists()}, canRead=${root.canRead()})")
            map = emptyMap(); return
        }
        val topLevel = root.listFiles()
        Log.i(TAG, "rescan: ${root.absolutePath} canRead=${root.canRead()} " +
                "listFiles=${topLevel?.size ?: "null"}")
        if (topLevel != null) {
            topLevel.take(5).forEach { Log.i(TAG, "  child: ${it.name} isDir=${it.isDirectory}") }
        }
        val rootUri = root.toURI()
        val next = mutableMapOf<String, File>()
        root.walkTopDown().forEach { f ->
            if (!f.isFile) return@forEach
            val ext = f.extension.lowercase()
            if (ext !in config.indexedExtensions) return@forEach
            val rel = rootUri.relativize(f.toURI()).path  // POSIX
            val id = config.contentIdFor(rel)
            next[id] = f
        }
        map = next
        Log.i(TAG, "rescan: indexed ${next.size} file(s) under ${root.absolutePath}")
        next.entries.take(5).forEach { (id, file) ->
            val rel = rootUri.relativize(file.toURI()).path
            Log.i(TAG, "  sample: id=$id rel=$rel")
        }
    }

    companion object { private const val TAG = "ReaderShellIndex" }

    operator fun get(contentId: String): File? = map[contentId]
    fun ids(): Set<String> = map.keys
    fun size(): Int = map.size
    fun entries(): Map<String, File> = map
}
