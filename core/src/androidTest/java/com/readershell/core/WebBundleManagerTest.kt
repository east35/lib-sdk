package com.readershell.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class WebBundleManagerTest {
    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var config: TestConfig
    private lateinit var manager: WebBundleManager

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("web_bundle_testapp", Context.MODE_PRIVATE).edit().clear().commit()
        context.filesDir.resolve("web-bundles/testapp").deleteRecursively()
        server = MockWebServer().also { it.start() }
        config = TestConfig(server.url("/").toString().trimEnd('/'))
        manager = WebBundleManager(context, config, CloudClient(config, Auth(context, "bundle_test")))
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun downloadIsPendingUntilNextStartup() {
        val zip = zipOf("index.html" to "new web", "js/app.js" to "ok")
        enqueueBundle(zip)

        manager.checkForUpdate()

        assertNull(manager.state().activeVersion)
        assertEquals(sha256(zip), manager.state().pendingVersion)
        assertNull(manager.activeDirectory())

        val restarted = WebBundleManager(context, config, CloudClient(config, Auth(context, "bundle_test")))
        assertTrue(restarted.activatePending())
        assertEquals("new web", restarted.activeDirectory()!!.resolve("index.html").readText())
    }

    @Test fun incompatibleManifestPreservesBundledSource() {
        val zip = zipOf("index.html" to "new web")
        enqueueBundle(zip, minShell = SHELL_API_VERSION + 1)

        manager.checkForUpdate()

        assertNull(manager.state().pendingVersion)
        assertNull(manager.activeDirectory())
        assertTrue(manager.state().lastResult.contains("newer shell"))
        assertEquals(1, server.requestCount)
    }

    @Test fun missingIndexIsRejected() {
        val zip = zipOf("app.js" to "no index")
        enqueueBundle(zip)

        manager.checkForUpdate()

        assertNull(manager.state().pendingVersion)
        assertFalse(context.filesDir.resolve("web-bundles/testapp/versions/${sha256(zip)}").exists())
    }

    @Test fun wrongAppIdIsRejectedWithoutDownloadingArchive() {
        val zip = zipOf("index.html" to "new web")
        enqueueBundle(zip, appId = "honlib")

        manager.checkForUpdate()

        assertNull(manager.state().pendingVersion)
        assertTrue(manager.state().lastResult.contains("wrong manifest appId"))
        assertEquals(1, server.requestCount)
    }

    @Test fun archiveChecksumMismatchIsRejectedAndTemporaryFilesAreRemoved() {
        val declared = zipOf("index.html" to "declared")
        val delivered = zipOf("index.html" to "different")
        enqueueBundle(declared, archive = delivered)

        manager.checkForUpdate()

        assertNull(manager.state().pendingVersion)
        assertTrue(manager.state().lastResult.contains("archive exceeds declared size") ||
            manager.state().lastResult.contains("archive size mismatch") ||
            manager.state().lastResult.contains("archive checksum mismatch"))
        assertTrue(context.filesDir.resolve("web-bundles/testapp").walkTopDown().none { it.name.endsWith(".tmp") })
    }

    @Test fun traversalEntryIsRejected() {
        val zip = zipOf("index.html" to "valid", "../escaped.txt" to "bad")
        enqueueBundle(zip)

        manager.checkForUpdate()

        assertNull(manager.state().pendingVersion)
        assertTrue(manager.state().lastResult.contains("unsafe archive entry"))
        assertFalse(context.filesDir.resolve("web-bundles/testapp/escaped.txt").exists())
    }

    @Test fun resetKeepsBundledAssetsAndRejectsImmediateRedownload() {
        val zip = zipOf("index.html" to "new web")
        enqueueBundle(zip)
        manager.checkForUpdate()
        assertTrue(manager.activatePending())

        manager.resetToBundled()
        enqueueBundle(zip)
        manager.checkForUpdate()

        assertNull(manager.activeDirectory())
        assertNull(manager.state().pendingVersion)
        assertTrue(manager.state().lastResult.contains("Bundled version retained"))
        assertEquals(3, server.requestCount)
    }

    private fun enqueueBundle(
        zip: ByteArray,
        minShell: Int = SHELL_API_VERSION,
        appId: String = "testapp",
        archive: ByteArray = zip,
    ) {
        val hash = sha256(zip)
        val uncompressed = java.util.zip.ZipInputStream(zip.inputStream()).use { input ->
            var total = 0L
            while (input.nextEntry != null) {
                val buffer = ByteArray(1024)
                while (true) { val n = input.read(buffer); if (n < 0) break else total += n }
            }
            total
        }
        server.enqueue(MockResponse().setBody("""{
          "schemaVersion":1,"appId":"$appId","bundleVersion":"$hash",
          "archivePath":"/api/app-bundle/$hash.zip","sha256":"$hash",
          "sizeBytes":${zip.size},"uncompressedSizeBytes":$uncompressed,
          "minShellApiVersion":$minShell
        }"""))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(archive)))
    }

    private fun zipOf(vararg files: Pair<String, String>): ByteArray = ByteArrayOutputStream().use { bytes ->
        ZipOutputStream(bytes).use { zip ->
            files.forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name).apply { time = 0 })
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
        bytes.toByteArray()
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private class TestConfig(override val cloudBaseUrl: String) : AppConfig {
        override val bundleAppId = "testapp"
        override val authPasswordKey = "TEST"
        override val indexedExtensions = emptySet<String>()
        override val proxyPort = 39999
        override fun contentIdFor(relativePosixPath: String) = relativePosixPath
    }
}
