package com.readershell.ebook

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.readershell.core.AppConfig
import com.readershell.core.Auth
import com.readershell.core.CloudClient
import com.readershell.core.ProxyServer
import com.readershell.core.WebBundleUpdater
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Contract test for the OTA web-bundle updater.
 *
 * The updater re-implements, in Kotlin, the client half of HonLib's app-bundle
 * protocol (web_bundle.py): fetch a manifest, download the content-addressed
 * ZIP, verify sha256 == bundleVersion, stage it, and promote it to active only
 * at cold start. This test pins that behaviour — and the two ways a bundle must
 * be REJECTED (bad checksum, shell too old) — against a mock backend so a
 * protocol drift on either side fails here instead of on the device.
 */
@RunWith(AndroidJUnit4::class)
class WebBundleUpdaterTest {

    private lateinit var cloud: MockWebServer
    private lateinit var cloudClient: CloudClient
    private lateinit var updater: WebBundleUpdater
    private val appId = "honlib_test"

    private lateinit var bundlesDir: File
    private lateinit var prefsName: String

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        bundlesDir = File(ctx.filesDir, "web-bundles/$appId")
        prefsName = "web_bundle_$appId"
        cleanState(ctx)

        cloud = MockWebServer().also { it.start() }
        val cfg = EbookConfig(cloud.url("/").toString().trimEnd('/'))
        cloudClient = CloudClient(cfg, Auth(ctx, "bundle_test"))
        updater = WebBundleUpdater(ctx, cloudClient, cfg.cloudBaseUrl, appId)
    }

    @After
    fun tearDown() {
        runCatching { cloud.shutdown() }
        cleanState(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    // --- Happy path: download, stage, cold-start activate, then serve ---

    @Test
    fun downloads_stages_and_activates_onColdStart() {
        val zip = buildZip("index.html" to "<html>bookmarks</html>", "app.js" to "console.log(1)")
        val version = sha256(zip)
        serve(manifest(version), version to zip)

        // Nothing active yet.
        assertNull("no bundle should be active before first check", updater.activeRoot())

        updater.checkForUpdate()
        // Staged, not yet active: activation is cold-start-only.
        assertEquals("Downloaded ${version.take(12)}; activates next launch", lastResult())
        assertNull("staged bundle must NOT be active mid-session", updater.activeRoot())

        updater.activatePending()
        val root = updater.activeRoot()
        assertNotNull("cold-start activation must make the bundle active", root)
        assertTrue("activated bundle must contain index.html", File(root, "index.html").isFile)
    }

    @Test
    fun proxy_servesActiveBundle_overApkAssets() {
        val zip = buildZip("index.html" to "OTA-INDEX", "app.js" to "OTA-APPJS")
        val version = sha256(zip)
        serve(manifest(version), version to zip)
        updater.checkForUpdate()
        updater.activatePending()

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val port = freePort()
        val cfg = object : AppConfig {
            override val cloudBaseUrl = cloud.url("/").toString().trimEnd('/')
            override val authPasswordKey = "x"
            override val indexedExtensions = setOf("epub")
            override val proxyPort = port
            override fun contentIdFor(relativePosixPath: String) = relativePosixPath
        }
        val proxy = ProxyServer(
            ctx, cfg, cloudClient, ctx.assets,
            router = object : ProxyServer.Router {
                override fun route(session: fi.iki.elonen.NanoHTTPD.IHTTPSession) = null
            },
            webRoot = { updater.activeRoot() },
        ).also { it.start() }

        try {
            assertEquals("OTA-INDEX", httpGet(port, "/"))
            assertEquals("OTA-APPJS", httpGet(port, "/app.js"))
        } finally {
            proxy.stop()
        }
    }

    // --- Rejections: a bundle that fails verification must never be staged ---

    @Test
    fun rejects_checksumMismatch() {
        val zip = buildZip("index.html" to "<html/>")
        val wrongVersion = sha256(buildZip("index.html" to "DIFFERENT"))
        // Manifest advertises wrongVersion; the served archive hashes to something else.
        serve(manifest(wrongVersion), wrongVersion to zip)

        updater.checkForUpdate()
        updater.activatePending()
        assertNull("a checksum-mismatched bundle must not activate", updater.activeRoot())
        assertTrue("last_result should note the mismatch", lastResult().contains("Checksum mismatch"))
    }

    @Test
    fun rejects_shellTooOld() {
        val zip = buildZip("index.html" to "<html/>")
        val version = sha256(zip)
        // Requires a newer shell API than this build speaks.
        val tooNew = WebBundleUpdater.SHELL_API_VERSION + 1
        serve(manifest(version, minShellApiVersion = tooNew), version to zip)

        updater.checkForUpdate()
        updater.activatePending()
        assertNull("a bundle needing a newer shell must not activate", updater.activeRoot())
        assertTrue("last_result should say the shell is too old", lastResult().contains("too old"))
    }

    @Test
    fun reportsUpToDate_whenActiveMatchesManifest() {
        val zip = buildZip("index.html" to "<html/>")
        val version = sha256(zip)
        serve(manifest(version), version to zip)
        updater.checkForUpdate()
        updater.activatePending()

        // Second check with the same manifest: already active → up to date, no re-stage.
        updater.checkForUpdate()
        assertEquals("Up to date (${version.take(12)})", lastResult())
    }

    // --- helpers ---

    private fun manifest(version: String, minShellApiVersion: Int = 1) = """
        {"schemaVersion":1,"appId":"$appId","bundleVersion":"$version",
         "archivePath":"/api/app-bundle/$version.zip","sha256":"$version",
         "minShellApiVersion":$minShellApiVersion}
    """.trimIndent()

    /** Route the mock backend by path: manifest as JSON, the archive as bytes. */
    private fun serve(manifestJson: String, archive: Pair<String, ByteArray>) {
        val (version, zip) = archive
        cloud.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/app-bundle/manifest" -> MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(manifestJson)
                "/api/app-bundle/$version.zip" -> MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/zip")
                    .setBody(Buffer().write(zip))
                else -> MockResponse().setResponseCode(404).setBody("not found")
            }
        }
    }

    private fun buildZip(vararg entries: Pair<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((name, content) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(content.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun lastResult(): String {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        return ctx.getSharedPreferences(prefsName, android.content.Context.MODE_PRIVATE)
            .getString("last_result", "") ?: ""
    }

    private fun cleanState(ctx: android.content.Context) {
        ctx.getSharedPreferences(prefsName, android.content.Context.MODE_PRIVATE)
            .edit().clear().apply()
        bundlesDir.deleteRecursively()
    }

    private fun httpGet(port: Int, path: String): String =
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
            .newCall(Request.Builder().url("http://127.0.0.1:$port$path").get().build())
            .execute().use { it.body!!.string() }

    private fun freePort(): Int = java.net.ServerSocket(0).use { it.localPort }
}
