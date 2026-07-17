package com.readershell.ebook

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.readershell.core.AppConfig
import com.readershell.core.Auth
import com.readershell.core.CloudClient
import com.readershell.core.LocalIndex
import com.readershell.core.ProgressQueue
import com.readershell.core.ProxyServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Contract test for the endpoints the HonLib web UI consumes.
 *
 * This exists because the SDK re-implements HonLib's HTTP contract by hand in
 * Kotlin (EbookRouter) while the web UI ships from HonLib and expects the
 * server's shapes. Nothing else forces the two to agree, so every enhancement
 * to HonLib/GaLib risks silently breaking the library — offline first, because
 * offline is the only path where the Kotlin router (not the real server)
 * produces the response.
 *
 * The invariant under test: whatever the cloud does (down, or returning a
 * tunnel's plain-text "404 page not found"), the endpoints the UI feeds into
 * setLibraryData() / progress rendering MUST still return the JSON SHAPE the UI
 * expects. A malformed or wrong-shaped response is what produced, in one week:
 *   - "Unexpected non-whitespace character after JSON" (junk body reached JSON.parse)
 *   - "No EPUBs found in the library folder" (refresh returned a status object,
 *     not a { books, groups } library payload)
 *
 * Shapes asserted here mirror HonLib app.py:
 *   GET  /api/library          -> { books: [...], groups: [...] }
 *   POST /api/library/refresh  -> { books: [...], groups: [...] }  (SAME shape)
 *   GET  /api/progress         -> { books: { ... } }
 */
@RunWith(AndroidJUnit4::class)
class LibraryContractTest {

    private lateinit var cloud: MockWebServer
    private lateinit var proxy: ProxyServer
    private lateinit var http: OkHttpClient
    private lateinit var booksRoot: File
    private lateinit var filesDir: File
    private var port = 0

    /** Test config: real EbookConfig behavior, but a free port and injectable cloud URL. */
    private class TestConfig(
        override val cloudBaseUrl: String,
        override val proxyPort: Int,
    ) : AppConfig {
        private val delegate = EbookConfig(cloudBaseUrl)
        override val authPasswordKey = delegate.authPasswordKey
        override val indexedExtensions = delegate.indexedExtensions
        override fun contentIdFor(relativePosixPath: String) =
            delegate.contentIdFor(relativePosixPath)
    }

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        filesDir = ctx.filesDir
        // Start clean: stale caches from a prior run must not mask a regression.
        File(filesDir, "library_cache.json").delete()
        File(filesDir, "progress_cache.json").delete()

        // Seed a local library with a couple of fake epubs in a subfolder, so
        // the offline synthesis path (localLibraryJson) has something to return.
        booksRoot = File(ctx.cacheDir, "contract_books_${System.nanoTime()}").apply { mkdirs() }
        File(booksRoot, "Cormac McCarthy").mkdirs()
        File(booksRoot, "Cormac McCarthy/No Country for Old Men.epub").writeText("fake-epub")
        File(booksRoot, "Neuromancer.epub").writeText("fake-epub")

        cloud = MockWebServer().also { it.start() }
        port = findFreePort()
        val cfg = TestConfig(cloud.url("/").toString().trimEnd('/'), port)
        val auth = Auth(ctx, "contract_test")
        val cloudClient = CloudClient(cfg, auth)
        val index = LocalIndex(cfg).apply { setRoot(booksRoot.absolutePath) }
        val queue = ProgressQueue(ctx, "contract_test")
        queue.all().forEach { queue.delete(it.key) } // drain any leftovers

        val router = EbookRouter(ctx, cloudClient, index, queue, cfg.cloudBaseUrl)
        proxy = ProxyServer(ctx, cfg, cloudClient, ctx.assets, router).also { it.start() }

        http = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        runCatching { proxy.stop() }
        runCatching { cloud.shutdown() }
        booksRoot.deleteRecursively()
        File(filesDir, "library_cache.json").delete()
        File(filesDir, "progress_cache.json").delete()
    }

    // --- The regression that started this: a tunnel returning plain-text 404 ---

    @Test
    fun library_survivesCloudJunk404() {
        cloud.enqueueAll(junk404())
        val body = get("/api/library")
        assertLibraryShape(body)
        assertTrue("offline library must include local books", booksIn(body) >= 2)
        // And the junk must never be persisted as the cache.
        assertCacheNotPoisoned()
    }

    @Test
    fun refresh_returnsLibraryPayload_notStatusObject_whenCloudJunk404() {
        cloud.enqueueAll(junk404())
        val body = post("/api/library/refresh")
        // The exact bug: refresh returned {"ok":true} and the UI rendered
        // "No EPUBs found". Refresh MUST carry books/groups like /api/library.
        assertLibraryShape(body)
        assertTrue("refresh must include local books offline", booksIn(body) >= 2)
        assertCacheNotPoisoned()
    }

    @Test
    fun progress_survivesCloudJunk404() {
        cloud.enqueueAll(junk404())
        val body = get("/api/progress")
        val obj = JSONObject(body) // must parse
        assertTrue("progress must have a books object", obj.has("books"))
    }

    // --- Cloud fully unreachable (connection refused), the true-offline case ---

    @Test
    fun library_survivesCloudUnreachable() {
        cloud.shutdown() // nothing answering
        val body = get("/api/library")
        assertLibraryShape(body)
        assertTrue("offline library must include local books", booksIn(body) >= 2)
    }

    @Test
    fun refresh_survivesCloudUnreachable() {
        cloud.shutdown()
        val body = post("/api/library/refresh")
        assertLibraryShape(body)
        assertTrue("refresh must include local books offline", booksIn(body) >= 2)
    }

    // --- Happy path: cloud returns a real payload, shape preserved ---

    @Test
    fun library_passesThroughCloudPayload() {
        val payload = """{"folder":"/x","books":[{"id":"abc","title":"Cloud Book"}],"groups":[{"name":"G","books":[{"id":"abc"}]}]}"""
        cloud.enqueueAll(json(payload))
        val body = get("/api/library")
        assertLibraryShape(body)
        assertTrue("cloud book must be present", body.contains("Cloud Book"))
    }

    // --- Progress sync: a reset on another client must propagate here ---

    @Test
    fun progress_resetOnCloud_dropsLocallySyncedProgress() {
        // 1. Read a book: POST progress, cloud accepts (row becomes clean/synced).
        cloud.enqueueAll(json("""{"ok":true}"""))
        postBody("/api/progress", progressBody("testbook", "2026-07-17T10:00:00.000Z"))

        // 2. Progress is reset on the web → cloud no longer lists the book.
        cloud.enqueueAll(json("""{"books":{}}"""))

        // 3. Device fetches progress. The book must NOT come back.
        val body = get("/api/progress")
        val books = JSONObject(body).getJSONObject("books")
        assertTrue(
            "a synced book absent from an authoritative cloud response must not resurrect",
            !books.has("testbook"),
        )
    }

    @Test
    fun progress_offlineKeepsLastKnownProgress() {
        // Read a book and sync it, then go offline.
        cloud.enqueueAll(json("""{"ok":true}"""))
        postBody("/api/progress", progressBody("testbook", "2026-07-17T10:00:00.000Z"))
        cloud.shutdown()

        // Offline, absence from cloud is NOT deletion — keep showing progress.
        val body = get("/api/progress")
        val books = JSONObject(body).getJSONObject("books")
        assertTrue("offline reader must retain last-known progress", books.has("testbook"))
    }

    @Test
    fun progress_unsyncedLocalWriteSurvivesEmptyCloud() {
        // Cloud rejects the POST → row stays dirty (pending). It must still show
        // up on GET even though the authoritative cloud response omits it.
        cloud.enqueueAll(MockResponse().setResponseCode(500).setBody("boom"))
        postBody("/api/progress", progressBody("pending", "2026-07-17T11:00:00.000Z"))
        cloud.enqueueAll(json("""{"books":{}}"""))
        val body = get("/api/progress")
        val books = JSONObject(body).getJSONObject("books")
        assertTrue("a dirty (unsynced) local write must not be dropped", books.has("pending"))
    }

    // --- helpers ---

    private fun progressBody(id: String, lastOpened: String) =
        """{"book_id":"$id","cfi":"epubcfi(/6/2)","percent":0.5,"last_opened":"$lastOpened"}"""

    private fun assertLibraryShape(body: String) {
        val obj = JSONObject(body) // throws if not JSON — that IS the failure mode
        assertTrue("library response must have 'books' array", obj.optJSONArray("books") != null)
        assertTrue("library response must have 'groups' array", obj.optJSONArray("groups") != null)
    }

    private fun booksIn(body: String) = JSONObject(body).optJSONArray("books")?.length() ?: 0

    private fun assertCacheNotPoisoned() {
        val cache = File(filesDir, "library_cache.json")
        if (cache.exists()) {
            val text = cache.readText()
            runCatching { JSONObject(text) }.onFailure {
                throw AssertionError("library_cache.json poisoned with non-JSON: ${text.take(40)}")
            }
        }
    }

    private fun get(path: String): String =
        http.newCall(Request.Builder().url(base(path)).get().build()).execute()
            .use { it.body!!.string() }

    private fun post(path: String): String = postBody(path, "{}")

    private fun postBody(path: String, body: String): String =
        http.newCall(
            Request.Builder().url(base(path))
                .post(body.toRequestBody("application/json".toMediaType())).build(),
        ).execute().use { it.body!!.string() }

    private fun base(path: String) = "http://127.0.0.1:$port$path"

    // The router may call an endpoint more than once (e.g. flushDirty), so make
    // the mock answer every request with the same canned response.
    private fun MockWebServer.enqueueAll(response: MockResponse) {
        dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = response
        }
    }

    private fun junk404() = MockResponse()
        .setResponseCode(404)
        .setHeader("Content-Type", "text/plain; charset=utf-8")
        .setBody("404 page not found\n")

    private fun json(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun findFreePort(): Int =
        java.net.ServerSocket(0).use { it.localPort }
}
