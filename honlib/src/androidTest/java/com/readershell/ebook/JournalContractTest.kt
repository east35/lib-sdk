package com.readershell.ebook

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.readershell.core.AppConfig
import com.readershell.core.Auth
import com.readershell.core.CloudClient
import com.readershell.core.LocalIndex
import com.readershell.core.ProgressQueue
import com.readershell.core.ProxyServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Contract test for the annotation endpoints the HonLib web UI consumes.
 *
 * The web UI treats a highlight as saved the moment it is made and expects to
 * read it back from GET /api/journal/sync whether or not there is a network.
 * On this device that promise is kept by JournalMirror, not by the server, so
 * the cases that matter are the ones the real server never sees: cloud down,
 * cloud answering with a tunnel's error page, and cloud coming back.
 *
 * Shapes mirror HonLib app.py / journal.py:
 *   GET  /api/journal/sync[?since=]   -> { cursor, full, journals: [], passages: [] }
 *   POST /api/journal/<kind>/<id>     -> { ok, applied, doc }
 */
@RunWith(AndroidJUnit4::class)
class JournalContractTest {

    private lateinit var cloud: MockWebServer
    private lateinit var proxy: ProxyServer
    private lateinit var http: OkHttpClient
    private var port = 0

    /** What the stand-in cloud does. */
    @Volatile private var cloudUp = true
    @Volatile private var cloudSync = """{"cursor":"cloud-0","full":true,"journals":[],"passages":[]}"""
    private val cloudWrites = Collections.synchronizedList(mutableListOf<String>())

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
        // Start clean: a cursor or rows from a prior run would hide a regression.
        ctx.getSharedPreferences("ebook_journal", Context.MODE_PRIVATE).edit().clear().commit()
        val journalStore = ProgressQueue(ctx, "journal_contract_test")
        journalStore.all().forEach { journalStore.delete(it.key) }

        cloud = MockWebServer()
        cloud.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (!cloudUp) return junk404()
                val path = request.path.orEmpty()
                return when {
                    request.method == "POST" && path.startsWith("/api/journal/") -> {
                        val body = request.body.readUtf8()
                        cloudWrites.add(path)
                        json("""{"ok":true,"applied":true,"doc":$body}""")
                    }
                    path.startsWith("/api/journal/sync") -> json(cloudSync)
                    else -> junk404()
                }
            }
        }
        cloud.start()
        port = java.net.ServerSocket(0).use { it.localPort }
        val cfg = TestConfig(cloud.url("/").toString().trimEnd('/'), port)
        val cloudClient = CloudClient(cfg, Auth(ctx, "journal_contract_test"))
        val router = EbookRouter(
            ctx, cloudClient, LocalIndex(cfg), ProgressQueue(ctx, "journal_contract_progress"),
            cfg.cloudBaseUrl, journalStore,
        )
        proxy = ProxyServer(ctx, cfg, cloudClient, ctx.assets, router).also { it.start() }
        http = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        runCatching { proxy.stop() }
        runCatching { cloud.shutdown() }
    }

    // --- Offline: the device's copy is the journal ---

    @Test
    fun offlineWrite_isKept_andReadBack() {
        cloud.shutdown() // nothing answering
        val saved = JSONObject(postDoc("passages", passage(ID_A, "2026-10-01T10:00:00.000Z", "made offline")))
        assertTrue(saved.getBoolean("ok"))
        assertTrue("a first write must be applied", saved.getBoolean("applied"))
        assertTrue("an offline write must be reported as queued", saved.getBoolean("queued"))
        assertEquals("made offline", saved.getJSONObject("doc").getString("note"))

        val first = JSONObject(get("/api/journal/sync"))
        assertSyncShape(first)
        assertTrue("without a cursor the whole copy is returned", first.getBoolean("full"))
        assertEquals(1, first.getJSONArray("passages").length())
        assertEquals(ID_A, first.getJSONArray("passages").getJSONObject(0).getString("id"))

        // Polling with the cursor returns only what changed since: nothing.
        val again = JSONObject(get("/api/journal/sync?since=" + first.getString("cursor")))
        assertSyncShape(again)
        assertFalse(again.getBoolean("full"))
        assertEquals(0, again.getJSONArray("passages").length())
        assertEquals(first.getString("cursor"), again.getString("cursor"))
    }

    @Test
    fun olderWrite_losesToNewer_andIsToldWhichWon() {
        cloud.shutdown()
        postDoc("passages", passage(ID_A, "2026-10-01T12:00:00.000Z", "newer"))
        val lost = JSONObject(postDoc("passages", passage(ID_A, "2026-10-01T09:00:00.000Z", "older")))
        assertFalse("an older version must not replace a newer one", lost.getBoolean("applied"))
        assertEquals("newer", lost.getJSONObject("doc").getString("note"))
    }

    @Test
    fun deleteMarker_isADocument_notARemoval() {
        cloud.shutdown()
        postDoc("passages", passage(ID_A, "2026-10-01T10:00:00.000Z", "to be deleted"))
        val cursor = JSONObject(get("/api/journal/sync")).getString("cursor")
        postDoc("passages", """{"v":1,"id":"$ID_A","updated":"2026-10-01T11:00:00.000Z","device":"device-a","deleted":true}""")
        val changes = JSONObject(get("/api/journal/sync?since=$cursor")).getJSONArray("passages")
        assertEquals("the delete must be handed out as a change", 1, changes.length())
        assertTrue(changes.getJSONObject(0).getBoolean("deleted"))
    }

    @Test
    fun malformedWrites_areRefused() {
        cloud.shutdown()
        assertEquals(400, postStatus("/api/journal/passages/$ID_A", "not json"))
        assertEquals(400, postStatus("/api/journal/passages/$ID_A", passage(ID_B, "2026-10-01T10:00:00.000Z", "wrong address")))
        assertEquals(400, postStatus("/api/journal/passages/$ID_A", """{"id":"$ID_A","updated":"yesterday"}"""))
        assertEquals(0, JSONObject(get("/api/journal/sync")).getJSONArray("passages").length())
    }

    // --- Cloud answering with junk must not break the UI or clear the queue ---

    @Test
    fun cloudJunk_keepsTheShape_andTheQueue() {
        cloudUp = false
        val saved = JSONObject(postDoc("passages", passage(ID_A, "2026-10-01T10:00:00.000Z", "kept")))
        assertTrue("a write the cloud did not accept stays queued", saved.getBoolean("queued"))
        val body = JSONObject(get("/api/journal/sync"))
        assertSyncShape(body)
        assertEquals(1, body.getJSONArray("passages").length())
        assertTrue(body.getBoolean("offline"))
    }

    // --- Reconnect: queued writes go out, cloud's changes come in ---

    @Test
    fun reconnect_sendsQueuedWrites_andTakesCloudChanges() {
        cloudUp = false
        postDoc("passages", passage(ID_A, "2026-10-01T10:00:00.000Z", "made offline"))
        postDoc("journals", """{"v":1,"id":"$ID_J","updated":"2026-10-01T10:00:00.000Z","device":"device-a","deleted":false,"name":"My First Journal","cover":null,"sources":[]}""")
        assertEquals("nothing reaches cloud while it is down", 0, cloudWrites.size)

        cloudUp = true
        cloudSync = """{"cursor":"cloud-7","full":true,"journals":[],"passages":[${passage(ID_B, "2026-10-01T11:00:00.000Z", "made on the tablet")}]}"""
        val body = JSONObject(get("/api/journal/sync"))
        assertSyncShape(body)
        assertFalse(body.getBoolean("offline"))
        assertTrue("queued passage must be sent on reconnect", cloudWrites.contains("/api/journal/passages/$ID_A"))
        assertTrue("queued journal must be sent on reconnect", cloudWrites.contains("/api/journal/journals/$ID_J"))
        val notes = (0 until body.getJSONArray("passages").length())
            .map { body.getJSONArray("passages").getJSONObject(it).getString("note") }.toSet()
        assertEquals(setOf("made offline", "made on the tablet"), notes)
        assertEquals(1, body.getJSONArray("journals").length())

        // Accepted writes are not sent again.
        cloudWrites.clear()
        get("/api/journal/sync")
        assertEquals(0, cloudWrites.size)
    }

    @Test
    fun cloudVersion_replacesAnOlderLocalOne() {
        cloudSync = """{"cursor":"cloud-1","full":true,"journals":[],"passages":[${passage(ID_A, "2026-10-01T10:00:00.000Z", "first")}]}"""
        val cursor = JSONObject(get("/api/journal/sync")).getString("cursor")
        cloudSync = """{"cursor":"cloud-2","full":false,"journals":[],"passages":[${passage(ID_A, "2026-10-01T12:00:00.000Z", "edited on the tablet")}]}"""
        val changes = JSONObject(get("/api/journal/sync?since=$cursor")).getJSONArray("passages")
        assertEquals(1, changes.length())
        assertEquals("edited on the tablet", changes.getJSONObject(0).getString("note"))
    }

    // --- helpers ---

    private fun passage(id: String, updated: String, note: String) =
        """{"v":1,"id":"$id","created":"2026-10-01T10:00:00.000Z","updated":"$updated","device":"device-a","deleted":false,""" +
            """"text":"In a hole in the ground there lived a hobbit.","note":"$note","tags":[],""" +
            """"style":{"highlight":"yellow","underline":null},"journals":[],"source":{"book_key":"id:hobbit","title":"The Hobbit"}}"""

    private fun assertSyncShape(obj: JSONObject) {
        assertTrue("sync response must have a cursor", obj.optString("cursor").isNotEmpty())
        assertTrue("sync response must have 'journals' array", obj.optJSONArray("journals") != null)
        assertTrue("sync response must have 'passages' array", obj.optJSONArray("passages") != null)
        assertTrue("sync response must say whether it is complete", obj.has("full"))
    }

    private fun postDoc(kind: String, body: String): String {
        val id = JSONObject(body).getString("id")
        return http.newCall(
            Request.Builder().url(base("/api/journal/$kind/$id"))
                .post(body.toRequestBody("application/json".toMediaType())).build(),
        ).execute().use { it.body!!.string() }
    }

    private fun postStatus(path: String, body: String): Int =
        http.newCall(
            Request.Builder().url(base(path))
                .post(body.toRequestBody("application/json".toMediaType())).build(),
        ).execute().use { it.code }

    private fun get(path: String): String =
        http.newCall(Request.Builder().url(base(path)).get().build()).execute()
            .use { it.body!!.string() }

    private fun base(path: String) = "http://127.0.0.1:$port$path"

    private fun junk404() = MockResponse()
        .setResponseCode(404)
        .setHeader("Content-Type", "text/plain; charset=utf-8")
        .setBody("404 page not found\n")

    private fun json(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    companion object {
        private const val ID_A = "11111111-1111-4111-8111-111111111111"
        private const val ID_B = "22222222-2222-4222-8222-222222222222"
        private const val ID_J = "33333333-3333-4333-8333-333333333333"
    }
}
