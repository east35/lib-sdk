package com.readershell.core

import android.content.Context
import android.util.Log
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Over-the-air web-UI updater. The web UI is published by the backend as a
 * versioned, content-addressed bundle (see HonLib web_bundle.py):
 *
 *   GET /api/app-bundle/manifest    -> { bundleVersion, sha256, minShellApiVersion, ... }
 *   GET /api/app-bundle/<version>.zip -> a reproducible ZIP of the web root
 *
 * `bundleVersion` IS the sha256 of the ZIP, so it doubles as the integrity check
 * and the on-disk directory name. A downloaded bundle is verified, extracted to
 * `files/web-bundles/<appId>/versions/<version>/`, and staged as `pending`.
 *
 * Promotion pending -> active normally happens at cold start via
 * [activatePending]. An explicit user action can call [activateLatest] and then
 * rebuild the proxy/WebView, so activation is deterministic without swapping
 * files underneath a live page. [activeRoot] is what the proxy serves from; a
 * null return means "fall back to the APK-baked assets" (first run, or OTA
 * disabled).
 *
 * State lives in `shared_prefs/web_bundle_<appId>.xml` (active / pending /
 * last_result), matching the shipped shell so an in-place source swap is
 * behaviour-compatible with what's already on the device.
 *
 * Every method swallows its own failures: an update problem must never take down
 * an app whose currently-active bundle is serving fine. Diagnostics land in
 * `last_result` (surfaced in Setup) and logcat.
 */
class WebBundleUpdater(
    ctx: Context,
    private val cloud: CloudClient,
    private val cloudBaseUrl: String,
    private val appId: String,
) {
    private val prefs = ctx.getSharedPreferences("web_bundle_$appId", Context.MODE_PRIVATE)
    private val versionsDir = File(ctx.filesDir, "web-bundles/$appId/versions")

    /** Directory the web UI should be served from, or null to use APK assets. */
    fun activeRoot(): File? {
        val v = prefs.getString(KEY_ACTIVE, null) ?: return null
        val dir = versionDir(v)
        return if (File(dir, "index.html").isFile) dir else null
    }

    /**
     * Promote a staged bundle to active. Cold-start only: call from
     * Application.onCreate BEFORE the proxy starts serving. A pending bundle
     * whose directory is missing or corrupt is discarded rather than activated,
     * so the previous active keeps serving.
     */
    @Synchronized
    fun activatePending(): Boolean {
        val pending = prefs.getString(KEY_PENDING, null) ?: return false
        val previousActive = prefs.getString(KEY_ACTIVE, null)
        val dir = versionDir(pending)
        if (File(dir, "index.html").isFile) {
            if (!prefs.edit().putString(KEY_ACTIVE, pending).remove(KEY_PENDING).commit()) {
                setResult("Could not activate ${short(pending)}")
                return false
            }
            // A running proxy may still be pinned to the previous root until
            // its owner rebuilds it. Retain that root through this activation;
            // it will be removed by a later activation.
            pruneOldVersions(pending, previousActive)
            setResult("Activated ${short(pending)}")
            Log.i(TAG, "activated bundle ${short(pending)}")
            return true
        } else {
            prefs.edit().remove(KEY_PENDING).commit()
            Log.w(TAG, "pending bundle ${short(pending)} missing/corrupt — discarded")
            return false
        }
    }

    /**
     * Activate an already-staged bundle, or download and activate the latest
     * bundle when none is staged. The caller must rebuild the proxy and WebView
     * after a true result; no live page is changed by this method itself.
     */
    @Synchronized
    fun activateLatest(): Boolean {
        if (activatePending()) return true
        checkForUpdate()
        return activatePending()
    }

    /**
     * Ask the backend for the latest bundle and stage it as pending if it's
     * newer than what's active/pending. Network + disk work — call off the main
     * thread. Never throws.
     */
    @Synchronized
    fun checkForUpdate() {
        try {
            val manifest = fetchManifest() ?: return
            val version = manifest.optString("bundleVersion")
            if (!isSha256(version)) { setResult("Bad manifest"); return }
            val minApi = manifest.optInt("minShellApiVersion", 1)
            if (minApi > SHELL_API_VERSION) {
                setResult("Shell too old for ${short(version)} (needs API $minApi)")
                return
            }
            when (version) {
                prefs.getString(KEY_ACTIVE, null) -> { setResult("Up to date (${short(version)})"); return }
                prefs.getString(KEY_PENDING, null) -> {
                    if (versionDir(version).let { File(it, "index.html").isFile }) {
                        setResult("Downloaded ${short(version)}; activates next launch"); return
                    }
                }
            }
            if (!downloadAndExtract(version)) return
            prefs.edit().putString(KEY_PENDING, version).apply()
            setResult("Downloaded ${short(version)}; activates next launch")
            Log.i(TAG, "staged bundle ${short(version)} (activates next cold start)")
        } catch (e: Exception) {
            Log.w(TAG, "update check failed: ${e.message}")
            setResult("Update check failed: ${e.message}")
        }
    }

    private fun fetchManifest(): JSONObject? {
        val req = Request.Builder().url("$cloudBaseUrl/api/app-bundle/manifest").get().build()
        cloud.execute { req }.use { resp ->
            if (!resp.isSuccessful) { setResult("Manifest HTTP ${resp.code}"); return null }
            val body = resp.body?.string().orEmpty()
            return try { JSONObject(body) } catch (_: Exception) { setResult("Manifest not JSON"); null }
        }
    }

    private fun downloadAndExtract(version: String): Boolean {
        val req = Request.Builder().url("$cloudBaseUrl/api/app-bundle/$version.zip").get().build()
        val bytes = cloud.execute { req }.use { resp ->
            if (!resp.isSuccessful) { setResult("Download HTTP ${resp.code}"); return false }
            resp.body?.bytes() ?: ByteArray(0)
        }
        // bundleVersion is the sha256 of the archive: verify before trusting a byte.
        if (sha256Hex(bytes) != version) { setResult("Checksum mismatch for ${short(version)}"); return false }

        versionsDir.mkdirs()
        val tmp = File(versionsDir, ".tmp-$version").apply { deleteRecursively() }
        try {
            if (!unzip(bytes, tmp)) { setResult("Corrupt bundle ${short(version)}"); return false }
            if (!File(tmp, "index.html").isFile) {
                setResult("Bundle ${short(version)} has no index.html"); return false
            }
            val dest = versionDir(version).apply { deleteRecursively() }
            if (!tmp.renameTo(dest)) { setResult("Install failed for ${short(version)}"); return false }
            return true
        } finally {
            tmp.deleteRecursively()
        }
    }

    /**
     * Extract [bytes] into [dest], refusing any entry that would escape it
     * (zip-slip). Returns false on a malformed archive.
     */
    private fun unzip(bytes: ByteArray, dest: File): Boolean {
        dest.mkdirs()
        val root = dest.canonicalFile
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                val out = File(dest, entry.name)
                if (out.canonicalFile != root &&
                    !out.canonicalPath.startsWith(root.path + File.separator)
                ) {
                    Log.w(TAG, "zip-slip entry blocked: ${entry.name}")
                    return false
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zin.copyTo(it) }
                }
                zin.closeEntry()
                entry = zin.nextEntry
            }
        }
        return true
    }

    /** Drop every extracted version except the ones still referenced. */
    private fun pruneOldVersions(vararg keep: String?) {
        val retained = keep.filterNotNull().toSet()
        val pending = prefs.getString(KEY_PENDING, null)
        versionsDir.listFiles()?.forEach { f ->
            if (f.isDirectory && f.name !in retained && f.name != pending) f.deleteRecursively()
        }
    }

    private fun versionDir(v: String) = File(versionsDir, v)
    private fun setResult(msg: String) { prefs.edit().putString(KEY_LAST_RESULT, msg).apply() }
    private fun short(v: String) = v.take(12)
    private fun isSha256(v: String) = v.length == 64 && v.all { it in "0123456789abcdef" }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "ReaderShellBundle"

        /**
         * Contract version this shell speaks. The backend advertises
         * `minShellApiVersion` per bundle; we refuse anything newer than this,
         * so a UI that needs shell features we don't have is never activated.
         */
        const val SHELL_API_VERSION = 1

        private const val KEY_ACTIVE = "active"
        private const val KEY_PENDING = "pending"
        private const val KEY_LAST_RESULT = "last_result"
    }
}
