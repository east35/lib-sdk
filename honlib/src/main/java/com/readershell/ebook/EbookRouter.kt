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
        val source = fetchCloudJson("/api/library", libraryCacheFile)
            ?: cachedJson(libraryCacheFile)
            ?: localLibraryJson()
        return libraryResponse(source)
    }

    /**
     * Both /api/library and /api/library/refresh return the SAME shape: the full
     * { folder, books, groups } payload. The web UI feeds either response
     * straight into setLibraryData(), so refresh MUST return a library — a bare
     * status object like {"ok":true} makes the UI render an empty library
     * ("No EPUBs found"). Keep this the single place that shapes the response.
     */
    private fun libraryResponse(source: String): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            "application/json; charset=utf-8",
            annotateOffline(source),
        )

    /**
     * GET a JSON endpoint from cloud, caching the body only when the response
     * is both successful and actually JSON. An error page (a tunnel's plain
     * "404 page not found", a login redirect, an nginx 502 body) is a non-empty
     * 200-adjacent body that will happily write to disk and then be served back
     * as application/json forever, defeating the offline fallbacks below. A
     * body we can't parse is not a response — treat it as no response at all.
     */
    private fun fetchCloudJson(path: String, cacheFile: File): String? = try {
        val req = Request.Builder().url("$cloudBaseUrl$path").get().build()
        val resp = cloud.execute { req }
        val body = resp.body?.string()
        val ok = resp.isSuccessful
        val code = resp.code
        resp.close()
        when {
            !ok -> { Log.w(TAG, "GET $path: cloud returned HTTP $code — not caching"); null }
            body.isNullOrEmpty() -> null
            !isJson(body) -> {
                Log.w(TAG, "GET $path: cloud body is not JSON (${body.take(40)}) — not caching")
                null
            }
            else -> { cacheFile.writeText(body); body }
        }
    } catch (e: Exception) {
        Log.i(TAG, "GET $path failed (offline?): ${e.message}")
        null
    }

    /**
     * Read a cache file, but only trust it if it still parses. Caches written
     * by older builds (before fetchCloudJson validated) can hold error text;
     * drop those instead of serving them until the end of time.
     */
    private fun cachedJson(cacheFile: File): String? {
        if (!cacheFile.exists()) return null
        val text = cacheFile.readText()
        if (isJson(text)) return text
        Log.w(TAG, "${cacheFile.name} holds non-JSON — discarding poisoned cache")
        cacheFile.delete()
        return null
    }

    private fun isJson(s: String): Boolean = try {
        JSONObject(s); true
    } catch (_: Exception) { false }

    /**
     * Synthesize a library response from the local index when the cloud is
     * unreachable and no library cache exists yet (first-run-offline). Without
     * this, the UI shows an empty library even though books are sitting on
     * disk. Title/group come from filename + parent dir; richer metadata
     * arrives the first time the cloud responds.
     */
    private fun localLibraryJson(): String {
        val obj = JSONObject()
        val booksArr = JSONArray()
        val groupsMap = linkedMapOf<String, JSONArray>()
        val root = index.rootPath?.let { File(it) }
        val rootUri = root?.toURI()
        val rootAbs = root?.absolutePath
        for ((id, file) in index.entries()) {
            val rel = if (rootUri != null) rootUri.relativize(file.toURI()).path else file.name
            val filename = file.name
            val parentName = file.parentFile?.takeIf { it.absolutePath != rootAbs }?.name ?: "Library"
            val title = filename.substringBeforeLast('.', filename)
            val cachedCover = cachedCoverFor(id)
            val hasCover = cachedCover != null
            val b = JSONObject()
                .put("id", id)
                .put("path", rel)
                .put("filename", filename)
                .put("group", parentName)
                .put("title", title)
                .put("author", "")
                .put("series", "")
                .put("genre", "")
                .put("has_cover", hasCover)
                .put("cover_url", if (hasCover) "/api/book/$id/cover" else JSONObject.NULL)
            booksArr.put(b)
            groupsMap.getOrPut(parentName) { JSONArray() }.put(b)
        }
        obj.put("books", booksArr)
        val groupsArr = JSONArray()
        for ((name, arr) in groupsMap) {
            groupsArr.put(JSONObject().put("name", name).put("books", arr))
        }
        obj.put("groups", groupsArr)
        Log.i(TAG, "localLibraryJson: synthesized ${booksArr.length()} book(s) from local index")
        return obj.toString()
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
        val source = fetchCloudJson("/api/progress", progressCacheFile)
            ?: cachedJson(progressCacheFile)
            ?: """{"books":{}}"""
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

    private fun cachedCoverFor(id: String): File? {
        val safeId = id.filter { it.isLetterOrDigit() }
        if (safeId.isEmpty()) return null
        val f = File(coverCacheDir, safeId)
        return if (f.isFile && f.length() > 0) f else null
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
     * progress rows we couldn't push at write-time, then returns a full library
     * payload — the web UI feeds this response straight into setLibraryData(),
     * exactly like /api/library, so it MUST carry { books, groups }. A status
     * object here empties the library on screen.
     *
     * Online: the cloud's refresh returns a fresh payload; cache and return it.
     * Offline: fall back to the last good cache, then the local index. Never
     * blow away the cache on a failed refresh — that's how a transient outage
     * turned into an empty library.
     */
    private fun handleLibraryRefresh(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        flushDirty()
        val body = readBody(session)
        val cloudPayload: String? = try {
            val req = Request.Builder()
                .url("$cloudBaseUrl/api/library/refresh")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            val resp = cloud.execute { req }
            val s = resp.body?.string().orEmpty()
            val ok = resp.isSuccessful
            resp.close()
            when {
                !ok -> { Log.w(TAG, "library/refresh: cloud HTTP ${resp.code} — using local library"); null }
                s.isEmpty() || !isJson(s) -> {
                    Log.w(TAG, "library/refresh: cloud body not JSON — using local library"); null
                }
                else -> { libraryCacheFile.writeText(s); s }
            }
        } catch (e: Exception) {
            Log.i(TAG, "library/refresh cloud forward failed (offline?): ${e.message}")
            null
        }

        val source = cloudPayload ?: cachedJson(libraryCacheFile) ?: localLibraryJson()
        return libraryResponse(source)
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
