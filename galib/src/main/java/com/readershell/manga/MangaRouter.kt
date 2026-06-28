package com.readershell.manga

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MangaRouter(
    private val ctx: Context,
    private val cloud: CloudClient,
    private val index: LocalIndex,
    private val queue: ProgressQueue,
    private val cloudBaseUrl: String,
) : com.readershell.core.ProxyServer.Router {

    private val libraryCacheFile: File by lazy { File(ctx.filesDir, "manga_library_cache.json") }
    private val progressCacheFile: File by lazy { File(ctx.filesDir, "manga_progress_cache.json") }

    override fun route(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response? {
        val uri = session.uri
        if (uri.startsWith("/api/")) Log.i(TAG, "${session.method} $uri")
        return when {
            uri == "/api/library" && session.method == NanoHTTPD.Method.GET ->
                handleLibrary()
            uri.startsWith("/api/series/") && uri.endsWith("/chapters") && session.method == NanoHTTPD.Method.GET ->
                handleChapters(uri.removePrefix("/api/series/").removeSuffix("/chapters"))
            uri.startsWith("/api/series/") && uri.endsWith("/pages") && session.method == NanoHTTPD.Method.GET ->
                handlePages(uri)
            uri.startsWith("/api/series/") && uri.contains("/page/") && session.method == NanoHTTPD.Method.GET ->
                handlePage(uri, session)
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
        val cloudJson = fetchText("$cloudBaseUrl/api/library")?.also { libraryCacheFile.writeText(it) }
        val source = cloudJson ?: if (libraryCacheFile.exists()) libraryCacheFile.readText() else localLibraryJson()
        return json(annotateLibrary(source))
    }

    private fun localLibraryJson(): String {
        val root = index.rootPath?.let { File(it) }
        val series = root?.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()
        val arr = JSONArray()
        for (dir in series) {
            if (dir.listFiles()?.any { it.isFile && it.extension.lowercase() == "cbz" } == true) {
                arr.put(JSONObject().put("name", dir.name).put("offline", true))
            }
        }
        return JSONObject().put("series", arr).toString()
    }

    private fun annotateLibrary(json: String): String = try {
        val obj = JSONObject(json)
        val root = index.rootPath?.let { File(it) }
        val series = obj.optJSONArray("series") ?: obj.optJSONArray("library") ?: JSONArray()
        for (i in 0 until series.length()) {
            val item = series.optJSONObject(i) ?: continue
            val name = item.optString("name").ifEmpty { item.optString("series") }
            item.put("offline", root?.resolve(name)?.isDirectory == true)
        }
        obj.toString()
    } catch (_: Exception) { json }

    private fun handleChapters(encodedSeries: String): NanoHTTPD.Response? {
        val series = MangaConfig.decodePathPart(encodedSeries)
        val dir = index.rootPath?.let { File(it, series) } ?: return null
        if (!dir.isDirectory) return null
        val chapters = dir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() == "cbz" }
            ?.sortedBy { it.name }
            ?: emptyList()
        val arr = JSONArray()
        for (chapter in chapters) arr.put(JSONObject().put("file", chapter.name).put("name", chapter.name).put("offline", true))
        return json(JSONObject().put("chapters", arr).toString())
    }

    private fun handlePages(uri: String): NanoHTTPD.Response? {
        val parsed = parseChapterUri(uri.removeSuffix("/pages")) ?: return null
        val file = chapterFile(parsed.series, parsed.chapter) ?: return null
        val pages = pageNames(file).size
        return json(JSONObject().put("pages", pages).toString())
    }

    private fun handlePage(uri: String, session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response? {
        val prefix = "/api/series/"
        val chapterMarker = "/chapters/"
        val pageMarker = "/page/"
        val body = uri.removePrefix(prefix)
        val chapterIdx = body.indexOf(chapterMarker)
        val pageIdx = body.indexOf(pageMarker)
        if (chapterIdx < 0 || pageIdx < 0 || pageIdx <= chapterIdx) return null
        val series = MangaConfig.decodePathPart(body.substring(0, chapterIdx))
        val chapter = MangaConfig.decodePathPart(body.substring(chapterIdx + chapterMarker.length, pageIdx))
        val idx = body.substring(pageIdx + pageMarker.length).toIntOrNull() ?: return null
        val file = chapterFile(series, chapter) ?: return null
        val names = pageNames(file)
        val name = names.getOrNull(idx) ?: return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.NOT_FOUND,
            "application/json",
            """{"ok":false,"error":"page not found"}""",
        )
        val crop = session.parameters["crop"]?.firstOrNull() == "1"
        ZipFile(file).use { zip ->
            val entry = zip.getEntry(name) ?: return null
            val raw = zip.getInputStream(entry).use { it.readBytes() }
            val (bytes, mime) = if (crop) {
                autocropPage(raw) ?: (raw to imageMime(name))
            } else raw to imageMime(name)
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                mime,
                ByteArrayInputStream(bytes),
                bytes.size.toLong(),
            )
        }
    }

    // Kotlin port of manga-library/app.py:autocrop_page. Trims near-uniform
    // margins by projecting content pixels onto rows/cols of a downscaled copy,
    // then crops the original. Returns null when the page is blank or already
    // trimmed, so the caller serves the untouched bytes.
    private fun autocropPage(data: ByteArray): Pair<ByteArray, String>? {
        val src = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return null
        val w = src.width
        val h = src.height
        if (w < 8 || h < 8) return null
        val analyze = 500
        val maxSide = max(w, h)
        val scale = if (maxSide > analyze) maxSide.toDouble() / analyze else 1.0
        val sw = max(1, (w / scale).toInt())
        val sh = max(1, (h / scale).toInt())
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        val pixels = IntArray(sw * sh)
        small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        if (small !== src) small.recycle()

        fun luma(p: Int): Int {
            val r = (p shr 16) and 0xff
            val g = (p shr 8) and 0xff
            val b = p and 0xff
            return (299 * r + 587 * g + 114 * b) / 1000
        }

        val bg = (luma(pixels[0]) + luma(pixels[sw - 1]) +
                luma(pixels[(sh - 1) * sw]) + luma(pixels[sh * sw - 1])) / 4.0
        val thresh = 255.0 * 0.12
        val rowHit = IntArray(sh)
        val colHit = IntArray(sw)
        for (y in 0 until sh) {
            val off = y * sw
            for (x in 0 until sw) {
                if (abs(luma(pixels[off + x]) - bg) > thresh) {
                    rowHit[y]++
                    colHit[x]++
                }
            }
        }
        val rowMin = sw * 0.012
        val colMin = sh * 0.012
        var firstRow = -1; var lastRow = -1
        for (y in 0 until sh) if (rowHit[y] > rowMin) { if (firstRow < 0) firstRow = y; lastRow = y }
        var firstCol = -1; var lastCol = -1
        for (x in 0 until sw) if (colHit[x] > colMin) { if (firstCol < 0) firstCol = x; lastCol = x }
        if (firstRow < 0 || firstCol < 0) return null

        val padX = (w * 0.01).toInt()
        val padY = (h * 0.01).toInt()
        val left = max(0, (firstCol * scale).toInt() - padX)
        val top = max(0, (firstRow * scale).toInt() - padY)
        val right = min(w, ((lastCol + 1) * scale).toInt() + padX)
        val bottom = min(h, ((lastRow + 1) * scale).toInt() + padY)
        if (left < w * 0.03 && top < h * 0.03 && right > w * 0.97 && bottom > h * 0.97) return null

        val cropped = Bitmap.createBitmap(src, left, top, right - left, bottom - top)
        val out = ByteArrayOutputStream()
        cropped.compress(Bitmap.CompressFormat.JPEG, 88, out)
        if (cropped !== src) cropped.recycle()
        src.recycle()
        return out.toByteArray() to "image/jpeg"
    }

    private fun parseChapterUri(uri: String): ChapterRef? {
        val prefix = "/api/series/"
        val marker = "/chapters/"
        if (!uri.startsWith(prefix)) return null
        val body = uri.removePrefix(prefix)
        val idx = body.indexOf(marker)
        if (idx < 0) return null
        return ChapterRef(
            MangaConfig.decodePathPart(body.substring(0, idx)),
            MangaConfig.decodePathPart(body.substring(idx + marker.length)),
        )
    }

    private fun chapterFile(series: String, chapter: String): File? {
        val id = "$series/$chapter"
        return index[id]?.takeIf { it.isFile } ?: index.rootPath?.let { File(File(it, series), chapter) }?.takeIf { it.isFile }
    }

    private fun pageNames(file: File): List<String> = ZipFile(file).use { zip ->
        zip.entries().asSequence()
            .map { it.name }
            .filter { !it.endsWith("/") && !it.lowercase().endsWith("comicinfo.xml") }
            .sorted()
            .toList()
    }

    private fun handleProgressGet(): NanoHTTPD.Response {
        val cloudJson = fetchText("$cloudBaseUrl/api/progress")?.also { progressCacheFile.writeText(it) }
        val source = cloudJson ?: if (progressCacheFile.exists()) progressCacheFile.readText() else """{"series":{}}"""
        return json(mergeProgress(source))
    }

    private fun mergeProgress(cloudJson: String): String = try {
        val obj = JSONObject(cloudJson)
        val seriesObj = obj.optJSONObject("series") ?: JSONObject().also { obj.put("series", it) }
        for (row in queue.all()) {
            val local = JSONObject(row.payload)
            val parts = row.key.split("/", limit = 2)
            val localUpdated = local.optDouble("updated", row.updated)
            if (local.optBoolean("_reset")) {
                val series = local.optString("series").ifEmpty { parts[0] }
                val cloudSeries = seriesObj.optJSONObject(series)
                val cloudUpdated = cloudSeries?.optDouble("updated", 0.0) ?: 0.0
                if (cloudSeries == null || localUpdated >= cloudUpdated) seriesObj.remove(series)
                continue
            }
            if (parts.size != 2) continue
            val s = seriesObj.optJSONObject(parts[0]) ?: JSONObject().also { seriesObj.put(parts[0], it) }
            val chapters = s.optJSONObject("chapters") ?: JSONObject().also { s.put("chapters", it) }
            val cloudEntry = chapters.optJSONObject(parts[1])
            val cloudUpdated = cloudEntry?.optDouble("updated", 0.0) ?: 0.0
            if (cloudEntry == null || localUpdated >= cloudUpdated) {
                chapters.put(parts[1], local)
            }
        }
        obj.toString()
    } catch (e: Exception) {
        Log.w(TAG, "mergeProgress failed: ${e.message}")
        cloudJson
    }

    private fun handleProgressPost(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val raw = readBody(session)
        val payload = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
        val series = payload.optString("series")
        val chapter = payload.optString("chapter").ifEmpty { payload.optString("file") }
        if (!payload.has("updated")) payload.put("updated", System.currentTimeMillis() / 1000.0)
        val key = if (series.isNotEmpty() && chapter.isNotEmpty()) "$series/$chapter" else ""
        var clean = false
        if (key.isNotEmpty()) {
            clean = postCloud("$cloudBaseUrl/api/progress", raw)
            queue.upsert(key, payload, payload.optDouble("updated"), dirty = !clean)
        }
        return json("""{"ok":true,"queued":true,"synced":$clean}""")
    }

    private fun handleProgressReset(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val raw = readBody(session)
        val payload = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
        val series = payload.optString("series")
        if (series.isEmpty()) return json("""{"ok":true}""")
        val updated = payload.optDouble("updated", System.currentTimeMillis() / 1000.0)
        val key = resetKey(series)
        var clean = false
        if (shouldPushReset(series, updated)) {
            clean = postCloud("$cloudBaseUrl/api/progress/reset", JSONObject().put("series", series).toString())
        }
        Log.i(TAG, "progress/reset series=$series synced=$clean updated=$updated")
        queue.delete(key)
        if (!clean) {
            val marker = JSONObject()
                .put("series", series)
                .put("updated", updated)
                .put("_reset", true)
            queue.upsert(key, marker, updated, dirty = true)
        }
        return json("""{"ok":true,"queued":true,"synced":$clean}""")
    }

    private fun handleLibraryRefresh(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        index.rescan()
        val flushed = flushDirty()
        val body = readBody(session)
        val out = try {
            val req = Request.Builder()
                .url("$cloudBaseUrl/api/library/refresh")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            val resp = cloud.execute { req }
            val s = resp.body?.string().orEmpty()
            resp.close()
            libraryCacheFile.delete()
            progressCacheFile.delete()
            s.ifEmpty { """{"ok":true}""" }
        } catch (e: Exception) {
            Log.i(TAG, "library/refresh cloud forward failed (offline?): ${e.message}")
            """{"ok":true,"offline":true,"flushed":$flushed}"""
        }
        return json(out)
    }

    fun flushDirty(): Int {
        var n = 0
        for (row in queue.dirtyRows()) {
            val payload = try { JSONObject(row.payload) } catch (_: Exception) { JSONObject() }
            val isReset = payload.optBoolean("_reset")
            val pushed = if (isReset) {
                val series = payload.optString("series").ifEmpty { row.key.removePrefix(RESET_PREFIX) }
                val updated = payload.optDouble("updated", row.updated)
                if (shouldPushReset(series, updated)) {
                    postCloud("$cloudBaseUrl/api/progress/reset", JSONObject().put("series", series).toString())
                } else true
            } else {
                postCloud("$cloudBaseUrl/api/progress", row.payload)
            }
            Log.i(TAG, "flushDirty row=${row.key} reset=$isReset pushed=$pushed")
            if (pushed) {
                if (isReset) queue.delete(row.key) else queue.markClean(row.key)
                n++
            }
        }
        if (n > 0) Log.i(TAG, "flushDirty: pushed $n row(s)")
        return n
    }

    private fun shouldPushReset(series: String, resetUpdated: Double): Boolean {
        val cloudJson = fetchText("$cloudBaseUrl/api/progress") ?: return true
        val cloudUpdated = cloudSeriesUpdated(cloudJson, series) ?: return true
        return resetUpdated >= cloudUpdated
    }

    private fun cloudSeriesUpdated(cloudJson: String, series: String): Double? = try {
        JSONObject(cloudJson)
            .optJSONObject("series")
            ?.optJSONObject(series)
            ?.optDouble("updated")
    } catch (_: Exception) { null }

    private fun resetKey(series: String): String = "$RESET_PREFIX$series"

    private fun fetchText(url: String): String? = try {
        val req = Request.Builder().url(url).get().build()
        val resp = cloud.execute { req }
        val body = resp.body?.string()
        resp.close()
        body?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) { null }

    private fun postCloud(url: String, raw: String): Boolean = try {
        val req = Request.Builder()
            .url(url)
            .post(raw.toRequestBody("application/json".toMediaType()))
            .build()
        val resp = cloud.execute { req }
        val ok = resp.isSuccessful
        resp.close()
        ok
    } catch (_: Exception) { false }

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

    private fun imageMime(path: String): String = when (path.substringAfterLast('.').lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> "application/octet-stream"
    }

    private fun json(body: String): NanoHTTPD.Response = NanoHTTPD.newFixedLengthResponse(
        NanoHTTPD.Response.Status.OK,
        "application/json; charset=utf-8",
        body,
    )

    private data class ChapterRef(val series: String, val chapter: String)

    companion object {
        private const val TAG = "MangaShellRouter"
        private const val RESET_PREFIX = "__reset__/"
    }
}
