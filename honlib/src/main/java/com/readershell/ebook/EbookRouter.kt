package com.readershell.ebook

import android.content.Context
import android.util.Log
import com.readershell.core.CloudClient
import com.readershell.core.LocalIndex
import com.readershell.core.ProgressQueue
import fi.iki.elonen.NanoHTTPD
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream

/**
 * Per-spec §5 ebook routing. Endpoint shapes confirmed against
 * HonLib app.py (https://github.com/east35/HonLib):
 *   /api/library, /api/book/<id>/file, /api/book/<id>/cover, /api/progress
 *
 * Phase-1 stub: handles local hits for /file and /cover (when reachable),
 * annotates /api/library with offline:true for ids present locally, and
 * write-firsts progress to the local queue. Cloud fallback is the proxy's
 * default passthrough — this router returns null for anything it doesn't
 * intentionally handle.
 */
class EbookRouter(
    private val ctx: Context,
    private val cloud: CloudClient,
    private val index: LocalIndex,
    private val queue: ProgressQueue,
    private val cloudBaseUrl: String,
) : com.readershell.core.ProxyServer.Router {

    private val libraryCacheFile: File by lazy { File(ctx.filesDir, "library_cache.json") }
    private val progressCacheFile: File by lazy { File(ctx.filesDir, "progress_cache.json") }
    private val coverCacheDir: File by lazy {
        File(ctx.filesDir, "cover_cache").apply { mkdirs() }
    }

    companion object { private const val TAG = "ReaderShellRouter" }

    override fun route(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response? {
        val uri = session.uri
        if (uri.startsWith("/api/")) Log.i(TAG, "${session.method} $uri")
        return when {
            uri == "/api/library" && session.method == NanoHTTPD.Method.GET ->
                handleLibrary()
            uri.startsWith("/api/book/") && uri.endsWith("/file") ->
                handleBookFile(uri.removePrefix("/api/book/").removeSuffix("/file"))
            uri.startsWith("/api/book/") && uri.endsWith("/cover") ->
                handleBookCover(uri.removePrefix("/api/book/").removeSuffix("/cover"))
            uri == "/api/progress" && session.method == NanoHTTPD.Method.GET ->
                handleProgressGet()
            uri == "/api/progress" && session.method == NanoHTTPD.Method.POST ->
                handleProgressPost(session)
            uri == "/api/progress/reset" && session.method == NanoHTTPD.Method.POST ->
                handleProgressReset(session)
            uri == "/api/library/refresh" && session.method == NanoHTTPD.Method.POST ->
                handleLibraryRefresh(session)
            else -> null
        }
    }

    private fun handleLibrary(): NanoHTTPD.Response {
        val cloudJson: String? = try {
            val req = Request.Builder().url("$cloudBaseUrl/api/library").get().build()
            val resp = cloud.execute { req }
            val body = resp.body?.string()
            resp.close()
            if (body != null && body.isNotEmpty()) {
                libraryCacheFile.writeText(body)
                body
            } else null
        } catch (_: Exception) { null }

        val source = cloudJson ?: run {
            if (libraryCacheFile.exists()) libraryCacheFile.readText()
            else """{"books":[]}"""
        }
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            "application/json; charset=utf-8",
            annotateOffline(source),
        )
    }

    private fun annotateOffline(json: String): String = try {
        val obj = JSONObject(json)
        val books = obj.optJSONArray("books") ?: JSONArray()
        for (i in 0 until books.length()) {
            val b = books.getJSONObject(i)
            val id = b.optString("id")
            b.put("offline", index[id] != null)
        }
        obj.toString()
    } catch (_: Exception) { json }

    private fun handleBookFile(id: String): NanoHTTPD.Response? {
        val file = index[id]
        if (file == null) {
            Log.w(TAG, "handleBookFile: no local match for id=$id (have ${index.size()} indexed)")
            return null  // miss → fall through to cloud
        }
        Log.i(TAG, "handleBookFile: serving local id=$id path=${file.absolutePath}")
        val fis = FileInputStream(file)
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            "application/epub+zip",
            fis,
            file.length(),
        )
    }

    /**
     * Server shape: { "books": { "<book_id>": { "cfi", "percent", "last_opened" }, ... } }
     * Returns cloud-merged-with-local-queue: for each book_id, whichever side
     * has the newer last_opened wins. last_opened is ISO 8601 → string compare works.
     */
    private fun handleProgressGet(): NanoHTTPD.Response {
        val cloudJson: String? = try {
            val req = Request.Builder().url("$cloudBaseUrl/api/progress").get().build()
            val resp = cloud.execute { req }
            val body = resp.body?.string()
            resp.close()
            if (body != null && body.isNotEmpty()) {
                progressCacheFile.writeText(body)
                body
            } else null
        } catch (_: Exception) { null }

        val source = cloudJson ?: run {
            if (progressCacheFile.exists()) progressCacheFile.readText()
            else """{"books":{}}"""
        }
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            "application/json; charset=utf-8",
            mergeProgress(source),
        )
    }

    private fun mergeProgress(cloudJson: String): String = try {
        val obj = JSONObject(cloudJson)
        val books = obj.optJSONObject("books") ?: JSONObject().also { obj.put("books", it) }
        for (row in queue.all()) {
            val local = JSONObject(row.payload)
            // Reset tombstones strip the book from progress entirely until
            // cloud confirms the reset.
            if (local.optBoolean("_reset")) {
                books.remove(row.key)
                continue
            }
            val localTs = local.optString("last_opened")
            val cloudEntry = books.optJSONObject(row.key)
            val cloudTs = cloudEntry?.optString("last_opened").orEmpty()
            if (cloudEntry == null || localTs > cloudTs) {
                books.put(row.key, local)
            }
        }
        obj.toString()
    } catch (e: Exception) {
        Log.w(TAG, "mergeProgress failed: ${e.message}")
        cloudJson
    }

    /**
     * Cover cache: each book's cover image is fetched from cloud once and
     * persisted under filesDir/cover_cache/<id>. After that, every request
     * serves from disk — survives reinstalls (well, app-data wipes), works
     * offline, and never hits cloud again for the same id.
     */
    private fun handleBookCover(id: String): NanoHTTPD.Response? {
        val safeId = id.filter { it.isLetterOrDigit() }
        if (safeId.isEmpty()) return null
        val cached = File(coverCacheDir, safeId)
        if (cached.isFile && cached.length() > 0) {
            val mime = sniffImageMime(cached) ?: "image/jpeg"
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK, mime, FileInputStream(cached), cached.length(),
            )
        }
        // Cache miss → fetch from cloud and persist.
        return try {
            val req = Request.Builder().url("$cloudBaseUrl/api/book/$id/cover").get().build()
            val resp = cloud.execute { req }
            val bytes = resp.body?.bytes()
            val mime = resp.header("content-type") ?: "image/jpeg"
            resp.close()
            if (bytes != null && bytes.isNotEmpty()) {
                cached.writeBytes(bytes)
                NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK, mime,
                    java.io.ByteArrayInputStream(bytes), bytes.size.toLong(),
                )
            } else null  // fall through (will hit cloud passthrough → 404)
        } catch (e: Exception) {
            Log.i(TAG, "cover fetch failed for $id (offline?): ${e.message}")
            null
        }
    }

    private fun sniffImageMime(f: File): String? {
        val head = ByteArray(8)
        FileInputStream(f).use { it.read(head) }
        return when {
            head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() -> "image/jpeg"
            head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte() -> "image/png"
            head[0] == 'G'.code.toByte() && head[1] == 'I'.code.toByte() -> "image/gif"
            head[0] == 'R'.code.toByte() && head[1] == 'I'.code.toByte() -> "image/webp"
            else -> null
        }
    }

    private fun handleProgressPost(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val len = session.headers["content-length"]?.toIntOrNull() ?: 0
        val buf = ByteArray(len)
        if (len > 0) {
            var read = 0
            while (read < len) {
                val n = session.inputStream.read(buf, read, len - read)
                if (n <= 0) break
                read += n
            }
        }
        val raw = String(buf, Charsets.UTF_8)
        val payload = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
        val key = payload.optString("book_id").ifEmpty { payload.optString("id") }
        // Server stamps last_opened itself, but we need a sortable timestamp
        // locally for merge. Use current ISO time if the payload omits it.
        if (!payload.has("last_opened")) {
            payload.put("last_opened", isoNow())
        }
        val updated = payload.optString("last_opened").hashCode().toDouble() // sort key not critical
        var clean = false
        if (key.isNotEmpty()) {
            // Best-effort forward to cloud; if it succeeds we mark clean.
            try {
                val req = Request.Builder()
                    .url("$cloudBaseUrl/api/progress")
                    .post(raw.toRequestBody("application/json".toMediaType()))
                    .build()
                val resp = cloud.execute { req }
                clean = resp.isSuccessful
                resp.close()
            } catch (e: Exception) {
                Log.i(TAG, "POST /api/progress cloud forward failed (offline?): ${e.message}")
            }
            queue.upsert(key, payload, updated, dirty = !clean)
        }
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            "application/json",
            """{"ok":true,"queued":true,"synced":$clean}""",
        )
    }

    /**
     * Forward reset to cloud AND drop the local queue row, so our merge
     * doesn't resurrect the cleared progress on the next GET.
     */
    private fun handleProgressReset(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val raw = readBody(session)
        val key = try { JSONObject(raw).optString("book_id") } catch (_: Exception) { "" }
        if (key.isEmpty()) {
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK, "application/json", """{"ok":true}""",
            )
        }
        // Try cloud immediately. If it succeeds, drop the local row entirely
        // (clean). If it fails (offline), drop the existing row but queue a
        // tombstone marker so flushDirty can replay the reset on reconnect.
        var clean = false
        try {
            val req = Request.Builder()
                .url("$cloudBaseUrl/api/progress/reset")
                .post(raw.toRequestBody("application/json".toMediaType()))
                .build()
            val resp = cloud.execute { req }
            clean = resp.isSuccessful
            resp.close()
        } catch (e: Exception) {
            Log.i(TAG, "progress/reset cloud forward failed (offline?): ${e.message}")
        }
        queue.delete(key)
        if (!clean) {
            val marker = JSONObject().put("book_id", key).put("_reset", true)
            queue.upsert(key, marker, isoNow().hashCode().toDouble(), dirty = true)
        }
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK, "application/json", """{"ok":true}""",
        )
    }

    /**
     * Hooked into the existing "Refresh library" button. Flushes any dirty
     * progress rows we couldn't push at write-time, then invalidates the local
     * caches so the next /api/library and /api/progress are forced to refetch
     * from cloud (assuming we're online; offline just returns the existing
     * caches).
     */
    private fun handleLibraryRefresh(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val flushed = flushDirty()
        val body = readBody(session)
        val out: String = try {
            val req = Request.Builder()
                .url("$cloudBaseUrl/api/library/refresh")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            val resp = cloud.execute { req }
            val s = resp.body?.string().orEmpty()
            resp.close()
            // Only invalidate caches on a real cloud success — otherwise we'd
            // strand the user offline with no library to display.
            libraryCacheFile.delete()
            progressCacheFile.delete()
            s.ifEmpty { """{"ok":true}""" }
        } catch (e: Exception) {
            Log.i(TAG, "library/refresh cloud forward failed (offline?): ${e.message}")
            """{"ok":true,"offline":true,"flushed":$flushed}"""
        }
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK, "application/json", out,
        )
    }

    /** Push every dirty queue row to cloud. Returns count successfully synced. */
    fun flushDirty(): Int {
        var n = 0
        for (row in queue.dirtyRows()) {
            val isReset = try { JSONObject(row.payload).optBoolean("_reset") } catch (_: Exception) { false }
            val url = if (isReset) "$cloudBaseUrl/api/progress/reset"
                      else "$cloudBaseUrl/api/progress"
            try {
                val req = Request.Builder()
                    .url(url)
                    .post(row.payload.toRequestBody("application/json".toMediaType()))
                    .build()
                val resp = cloud.execute { req }
                if (resp.isSuccessful) {
                    // Reset tombstones are one-shot: delete after successful push.
                    if (isReset) queue.delete(row.key) else queue.markClean(row.key)
                    n++
                }
                resp.close()
            } catch (_: Exception) { /* stay dirty */ }
        }
        if (n > 0) Log.i(TAG, "flushDirty: pushed $n row(s)")
        return n
    }

    private fun readBody(session: NanoHTTPD.IHTTPSession): String {
        val len = session.headers["content-length"]?.toIntOrNull() ?: return ""
        val buf = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = session.inputStream.read(buf, read, len - read)
            if (n <= 0) break
            read += n
        }
        return String(buf, Charsets.UTF_8)
    }

    private fun isoNow(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return sdf.format(java.util.Date())
    }
}
