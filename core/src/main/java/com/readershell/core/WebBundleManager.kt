package com.readershell.core

import android.content.Context
import android.util.Log
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Downloads immutable web bundles and switches versions only at process start.
 * All state lives in private app storage; the APK web tree is always the fallback.
 */
class WebBundleManager(
    context: Context,
    private val config: AppConfig,
    private val cloud: CloudClient,
    private val shellApiVersion: Int = SHELL_API_VERSION,
) {
    private val root = File(context.filesDir, "web-bundles/${config.bundleAppId}")
    private val versions = File(root, "versions")
    private val prefs = context.getSharedPreferences(
        "web_bundle_${config.bundleAppId}", Context.MODE_PRIVATE,
    )

    data class State(
        val activeVersion: String?,
        val pendingVersion: String?,
        val lastResult: String,
    ) {
        val activeLabel: String get() = activeVersion?.let { "Downloaded ${it.take(12)}" } ?: "Bundled APK"
    }

    /** Activate a completely validated pending version. Called before ProxyServer starts. */
    fun activatePending(): Boolean {
        val pending = prefs.getString(KEY_PENDING, null) ?: return false
        val dir = versionDir(pending)
        if (!validVersion(pending) || !validBundleDir(dir)) {
            Log.w(TAG, "Pending web bundle rejected during activation: invalid local content")
            prefs.edit().remove(KEY_PENDING).putString(KEY_LAST, "Pending bundle invalid; using previous version").commit()
            return false
        }
        if (!prefs.edit().putString(KEY_ACTIVE, pending).remove(KEY_PENDING)
                .putString(KEY_LAST, "Activated ${pending.take(12)}").commit()) {
            Log.w(TAG, "Could not persist web bundle activation; retaining previous version")
            return false
        }
        Log.i(TAG, "Activated web bundle ${pending.take(12)}")
        prune()
        return true
    }

    fun activeDirectory(): File? {
        val active = prefs.getString(KEY_ACTIVE, null) ?: return null
        val dir = versionDir(active)
        if (validVersion(active) && validBundleDir(dir)) return dir
        Log.w(TAG, "Active web bundle invalid or missing; falling back to APK assets")
        prefs.edit().remove(KEY_ACTIVE).putString(KEY_LAST, "Active bundle invalid; using bundled APK").commit()
        return null
    }

    fun state(): State = State(
        prefs.getString(KEY_ACTIVE, null),
        prefs.getString(KEY_PENDING, null),
        prefs.getString(KEY_LAST, "Not checked yet") ?: "Not checked yet",
    )

    /** Fetch exactly once per process (the Application owns that guard). */
    fun checkForUpdate() {
        try {
            val manifest = fetchManifest()
            val current = state()
            if (manifest.version == current.activeVersion || manifest.version == current.pendingVersion) {
                record("Up to date (${manifest.version.take(12)})")
                return
            }
            if (manifest.version == prefs.getString(KEY_REJECTED, null)) {
                record("Bundled version retained (${manifest.version.take(12)})")
                return
            }
            downloadAndStage(manifest)
        } catch (e: Exception) {
            val reason = e.message?.take(160) ?: e.javaClass.simpleName
            Log.w(TAG, "Web bundle update check failed: $reason")
            record("Update failed: $reason")
        }
    }

    /** Reset pointers only. Immutable files can be pruned after the proxy is restarted. */
    fun resetToBundled() {
        val rejected = prefs.getString(KEY_PENDING, null) ?: prefs.getString(KEY_ACTIVE, null)
        prefs.edit().remove(KEY_ACTIVE).remove(KEY_PENDING)
            .putString(KEY_REJECTED, rejected)
            .putString(KEY_LAST, "Reset to bundled APK")
            .commit()
        Log.i(TAG, "Reset web app to bundled APK assets")
        prune()
    }

    private data class Manifest(
        val version: String,
        val archivePath: String,
        val size: Long,
        val unpackedSize: Long,
    )

    private fun fetchManifest(): Manifest {
        val request = Request.Builder()
            .url("${config.cloudBaseUrl}/api/app-bundle/manifest")
            .get().build()
        cloud.execute { request }.use { response ->
            if (!response.isSuccessful) throw IOException("manifest HTTP ${response.code}")
            val body = response.body?.string() ?: throw IOException("empty manifest")
            val json = try { JSONObject(body) } catch (_: Exception) { throw IOException("malformed manifest") }
            if (json.optInt("schemaVersion", -1) != 1) throw IOException("unsupported manifest schema")
            if (json.optString("appId") != config.bundleAppId) throw IOException("wrong manifest appId")
            val version = json.optString("bundleVersion")
            val hash = json.optString("sha256")
            if (!validVersion(version) || hash != version) throw IOException("invalid bundle hash")
            if (json.optInt("minShellApiVersion", Int.MAX_VALUE) > shellApiVersion) {
                throw IOException("bundle requires a newer shell")
            }
            val path = json.optString("archivePath")
            if (path != "/api/app-bundle/$version.zip") throw IOException("unsafe archive path")
            val size = json.optLong("sizeBytes", -1)
            val unpacked = json.optLong("uncompressedSizeBytes", -1)
            if (size !in 1..MAX_COMPRESSED || unpacked !in 1..MAX_EXTRACTED) {
                throw IOException("bundle size outside limits")
            }
            return Manifest(version, path, size, unpacked)
        }
    }

    private fun downloadAndStage(manifest: Manifest) {
        root.mkdirs(); versions.mkdirs()
        val archive = File(root, ".${manifest.version}.zip.tmp")
        val extracting = File(root, ".${manifest.version}.extracting")
        archive.delete()
        extracting.deleteRecursively()
        try {
            val request = Request.Builder().url(config.cloudBaseUrl + manifest.archivePath).get().build()
            cloud.execute { request }.use { response ->
                if (!response.isSuccessful) throw IOException("archive HTTP ${response.code}")
                val body = response.body ?: throw IOException("empty archive")
                val digest = MessageDigest.getInstance("SHA-256")
                var count = 0L
                FileOutputStream(archive).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            count += n
                            if (count > manifest.size || count > MAX_COMPRESSED) throw IOException("archive exceeds declared size")
                            digest.update(buffer, 0, n); output.write(buffer, 0, n)
                        }
                        output.fd.sync()
                    }
                }
                if (count != manifest.size) throw IOException("archive size mismatch")
                if (digest.hex() != manifest.version) throw IOException("archive checksum mismatch")
            }
            rejectSymlinks(archive)
            val extracted = extract(archive, extracting, manifest.unpackedSize)
            if (extracted != manifest.unpackedSize) throw IOException("extracted size mismatch")
            if (!validBundleDir(extracting)) throw IOException("bundle missing root index.html")
            val destination = versionDir(manifest.version)
            if (destination.exists() && !validBundleDir(destination)) destination.deleteRecursively()
            if (!destination.exists() && !extracting.renameTo(destination)) throw IOException("could not install bundle atomically")
            if (!prefs.edit().putString(KEY_PENDING, manifest.version)
                    .putString(KEY_LAST, "Downloaded ${manifest.version.take(12)}; activates next launch").commit()) {
                throw IOException("could not record pending bundle")
            }
            Log.i(TAG, "Downloaded web bundle ${manifest.version.take(12)}; pending next launch")
        } finally {
            archive.delete(); extracting.deleteRecursively()
        }
    }

    private fun extract(zip: File, target: File, declaredSize: Long): Long {
        target.mkdirs()
        val canonicalRoot = target.canonicalFile
        var entries = 0
        var total = 0L
        ZipInputStream(BufferedInputStream(FileInputStream(zip))).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                entries++
                if (entries > MAX_ENTRIES) throw IOException("too many archive entries")
                val out = File(target, entry.name).canonicalFile
                if (entry.name.startsWith('/') || out.path != canonicalRoot.path && !out.path.startsWith(canonicalRoot.path + File.separator)) {
                    throw IOException("unsafe archive entry")
                }
                if (entry.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            if (total > declaredSize || total > MAX_EXTRACTED) throw IOException("extracted bundle exceeds limit")
                            output.write(buffer, 0, n)
                        }
                    }
                }
                input.closeEntry()
            }
        }
        return total
    }

    /** Read central-directory Unix modes: Java's ZipEntry does not expose them. */
    private fun rejectSymlinks(zip: File) {
        val bytes = zip.readBytes()
        var i = 0
        while (i + 46 <= bytes.size) {
            if (u32(bytes, i) == 0x02014b50L) {
                val nameLen = u16(bytes, i + 28)
                val extraLen = u16(bytes, i + 30)
                val commentLen = u16(bytes, i + 32)
                val mode = (u32(bytes, i + 38) ushr 16).toInt()
                if (mode and 0xF000 == 0xA000) throw IOException("symlink archive entry rejected")
                i += 46 + nameLen + extraLen + commentLen
            } else i++
        }
    }

    private fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 255) or ((b[i + 1].toInt() and 255) shl 8)
    private fun u32(b: ByteArray, i: Int): Long = u16(b, i).toLong() or (u16(b, i + 2).toLong() shl 16)
    private fun MessageDigest.hex() = digest().joinToString("") { "%02x".format(it) }
    private fun validVersion(value: String?) = value?.matches(Regex("[0-9a-f]{64}")) == true
    private fun versionDir(version: String) = File(versions, version)
    private fun validBundleDir(dir: File): Boolean {
        if (!dir.isDirectory) return false
        val root = try { dir.canonicalFile } catch (_: IOException) { return false }
        val index = try { File(dir, "index.html").canonicalFile } catch (_: IOException) { return false }
        return index.isFile && index.path.startsWith(root.path + File.separator)
    }
    private fun record(value: String) { prefs.edit().putString(KEY_LAST, value).apply() }
    private fun prune() {
        val keep = setOfNotNull(prefs.getString(KEY_ACTIVE, null), prefs.getString(KEY_PENDING, null))
        versions.listFiles()?.filter { it.isDirectory && it.name !in keep }?.forEach { it.deleteRecursively() }
    }

    companion object {
        private const val TAG = "WebBundleManager"
        private const val KEY_ACTIVE = "active"
        private const val KEY_PENDING = "pending"
        private const val KEY_REJECTED = "rejected"
        private const val KEY_LAST = "last_result"
        private const val MAX_COMPRESSED = 100L * 1024 * 1024
        private const val MAX_EXTRACTED = 200L * 1024 * 1024
        private const val MAX_ENTRIES = 10_000
    }
}
