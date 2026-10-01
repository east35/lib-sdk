package com.readershell.ebook

import android.content.Context
import android.util.Log
import com.readershell.core.CloudClient
import com.readershell.core.ProgressQueue
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The device's own copy of every journal and passage, so highlights can be
 * made, noted, tagged and reviewed with the server out of reach.
 *
 * Mirrors HonLib journal.py (JournalStore) for the two endpoints the web UI
 * drives its annotations through:
 *
 *   GET  /api/journal/sync?since=<cursor>  -> { cursor, full, journals, passages }
 *   POST /api/journal/<kind>/<id>          -> { ok, applied, doc }
 *
 * A document is a whole JSON object owned by whichever device wrote it last:
 * `updated` (ISO 8601) decides between two versions, `device` breaks a tie,
 * and a delete is a document with `deleted: true`. Nothing here interprets a
 * document beyond that, which is what lets the web UI change its shape without
 * a new APK.
 *
 * Storage reuses [ProgressQueue]: one row per document, keyed "<kind>/<id>".
 * `dirty` means the server has not accepted this version yet. `updated` is not
 * a time here but a local change counter, so the UI can ask for "everything
 * after N" exactly as it asks the server.
 */
class JournalMirror(
    ctx: Context,
    private val cloud: CloudClient,
    private val store: ProgressQueue,
    private val cloudBaseUrl: String,
) {
    private val prefs = ctx.getSharedPreferences("ebook_journal", Context.MODE_PRIVATE)
    private val lock = Any()

    /**
     * Store a document written by the web UI, then offer it to the server.
     * The local copy is what makes the write real; the server is best-effort
     * and is retried by [flushDirty]. Null when [raw] is not a document for
     * this address.
     */
    fun put(kind: String, id: String, raw: String): JSONObject? {
        val doc = try { JSONObject(raw) } catch (_: Exception) { return null }
        if (doc.optString("id") != id || parseTime(doc.optString("updated")) == null) return null
        val key = "$kind/$id"
        val applied = synchronized(lock) {
            val current = stored(key)
            val newer = current == null || isNewer(doc, current)
            if (newer) store.upsert(key, doc, nextSeq(), dirty = true)
            newer
        }
        val synced = applied && push(key, doc) == Push.DONE
        val held = synchronized(lock) { stored(key) } ?: doc
        return JSONObject()
            .put("ok", true)
            .put("applied", applied)
            .put("doc", held)
            .put("queued", applied && !synced)
    }

    /**
     * Answer a sync request from the local copy, after exchanging changes with
     * the server if it can be reached. Offline this is simply the local copy,
     * which is the point.
     */
    fun sync(since: String?): JSONObject {
        flushDirty()
        val reachable = pull()
        return synchronized(lock) { changesSince(since) }.put("offline", !reachable)
    }

    /** Send every document the server has not accepted yet. Returns how many it took. */
    fun flushDirty(): Int {
        var sent = 0
        for (row in synchronized(lock) { store.dirtyRows() }) {
            val doc = try { JSONObject(row.payload) } catch (_: Exception) { continue }
            when (push(row.key, doc)) {
                Push.DONE -> sent++
                Push.KEPT -> {}
                // No server: every other row would wait out the same timeout.
                Push.UNREACHABLE -> break
            }
        }
        if (sent > 0) Log.i(TAG, "flushDirty: pushed $sent journal document(s)")
        return sent
    }

    private enum class Push { DONE, KEPT, UNREACHABLE }

    private fun push(key: String, doc: JSONObject): Push {
        val code: Int
        val body: String?
        try {
            val resp = cloud.execute {
                Request.Builder()
                    .url("$cloudBaseUrl/api/journal/$key")
                    .post(doc.toString().toRequestBody("application/json".toMediaType()))
                    .build()
            }
            code = resp.code
            body = resp.body?.string()
            resp.close()
        } catch (e: Exception) {
            Log.i(TAG, "POST /api/journal/$key failed (offline?): ${e.message}")
            return Push.UNREACHABLE
        }
        // The server will never accept this document; retrying cannot help.
        if (code == 400 || code == 413) {
            synchronized(lock) { settle(key, doc, null) }
            return Push.DONE
        }
        if (code !in 200..299) return Push.KEPT
        // Only a body shaped like the server's answer counts. A tunnel's error
        // page with a 200 on it must not clear the queue.
        val held = (try { JSONObject(body ?: "").optJSONObject("doc") } catch (_: Exception) { null })
            ?: return Push.KEPT
        synchronized(lock) { settle(key, doc, held) }
        return Push.DONE
    }

    /**
     * The server has answered for [sent]. Take its version if that is newer
     * (the write lost to another device), and stop retrying unless the document
     * was edited again while the request was in flight.
     */
    private fun settle(key: String, sent: JSONObject, held: JSONObject?) {
        val local = stored(key) ?: return
        if (held != null && isNewer(held, local)) {
            store.upsert(key, held, nextSeq(), dirty = false)
        } else if (!isNewer(local, sent)) {
            store.markClean(key)
        }
    }

    /** Take what changed on the server since the last pull. False when it could not be reached. */
    private fun pull(): Boolean {
        val cursor = prefs.getString(KEY_CLOUD_CURSOR, null)
        val query = cursor?.let { "?since=" + URLEncoder.encode(it, "UTF-8") } ?: ""
        val body = try {
            val resp = cloud.execute {
                Request.Builder().url("$cloudBaseUrl/api/journal/sync$query").get().build()
            }
            val text = resp.body?.string()
            val ok = resp.isSuccessful
            resp.close()
            if (!ok) return false
            text
        } catch (e: Exception) {
            Log.i(TAG, "GET /api/journal/sync failed (offline?): ${e.message}")
            return false
        }
        val data = try { JSONObject(body ?: "") } catch (_: Exception) { return false }
        val next = data.optString("cursor")
        if (next.isEmpty()) return false
        synchronized(lock) {
            for (kind in KINDS) {
                val docs = data.optJSONArray(kind) ?: continue
                for (i in 0 until docs.length()) {
                    val doc = docs.optJSONObject(i) ?: continue
                    val id = doc.optString("id")
                    if (id.isEmpty()) continue
                    val key = "$kind/$id"
                    val local = stored(key)
                    // A local edit newer than the server's copy stays, and
                    // stays queued; flushDirty sends it.
                    if (local == null || isNewer(doc, local)) {
                        store.upsert(key, doc, nextSeq(), dirty = false)
                    }
                }
            }
            prefs.edit().putString(KEY_CLOUD_CURSOR, next).apply()
        }
        return true
    }

    private fun changesSince(since: String?): JSONObject {
        val prefix = "${epoch()}-"
        val after = since?.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.toLongOrNull()
        val lists = KINDS.associateWith { JSONArray() }
        for (row in store.since((after ?: 0L).toDouble())) {
            val doc = try { JSONObject(row.payload) } catch (_: Exception) { continue }
            lists[row.key.substringBefore('/')]?.put(doc)
        }
        val out = JSONObject()
            .put("cursor", prefix + store.maxUpdated().toLong())
            .put("full", after == null)
        for ((kind, list) in lists) out.put(kind, list)
        return out
    }

    /**
     * Identifies this copy's change counter. A cursor from a copy that has
     * since been wiped (app data cleared) must not be honoured: its numbers
     * mean nothing against a counter that started again from zero.
     */
    private fun epoch(): String {
        val saved = prefs.getString(KEY_EPOCH, null)
        if (saved != null && store.maxUpdated() > 0.0) return saved
        val fresh = UUID.randomUUID().toString().replace("-", "").take(16)
        prefs.edit().putString(KEY_EPOCH, fresh).apply()
        return fresh
    }

    private fun nextSeq(): Double = store.maxUpdated() + 1.0

    private fun stored(key: String): JSONObject? =
        store.get(key)?.let { row -> try { JSONObject(row.payload) } catch (_: Exception) { null } }

    private fun parseTime(value: String): Instant? = try {
        Instant.parse(value)
    } catch (_: Exception) {
        try { OffsetDateTime.parse(value).toInstant() } catch (_: Exception) { null }
    }

    /** Last writer wins; the device id settles an exact tie the same way everywhere. */
    private fun isNewer(candidate: JSONObject, current: JSONObject): Boolean {
        val a = parseTime(candidate.optString("updated")) ?: Instant.EPOCH
        val b = parseTime(current.optString("updated")) ?: Instant.EPOCH
        return if (a != b) a.isAfter(b) else candidate.optString("device") > current.optString("device")
    }

    companion object {
        private const val TAG = "ReaderShellJournal"
        private const val KEY_EPOCH = "epoch"
        private const val KEY_CLOUD_CURSOR = "cloud_cursor"
        val KINDS = listOf("journals", "passages")
    }
}
