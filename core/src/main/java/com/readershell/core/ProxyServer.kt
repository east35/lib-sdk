package com.readershell.core

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.File
import java.io.FileInputStream

/**
 * The embedded localhost HTTP server. Bound to 127.0.0.1 only.
 *
 * Routing is delegated to a per-app [Router] so core/ stays content-agnostic.
 * Default behavior for unknown paths: serve from bundled assets (the web UI),
 * falling back to cloud passthrough.
 */
class ProxyServer(
    private val ctx: Context,
    private val config: AppConfig,
    private val cloud: CloudClient,
    private val assets: AssetManager,
    private val router: Router,
    /** Fixed for this server's lifetime; null means the APK assets/web tree. */
    private val webRoot: File? = null,
) : NanoHTTPD("127.0.0.1", config.proxyPort) {

    interface Router {
        /** Return a response, or null to fall through to default handling. */
        fun route(session: IHTTPSession): Response?
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            val r = router.route(session)
            if (r != null) { Log.i(TAG, "router ${session.method} ${session.uri} -> ${r.status}"); return r }
            val a = serveAsset(session.uri)
            if (a != null) { Log.i(TAG, "asset  ${session.method} ${session.uri} -> ${a.status}"); return a }
            val c = passthroughCloud(session)
            Log.i(TAG, "cloud  ${session.method} ${session.uri} -> ${c.status}")
            c
        } catch (e: Exception) {
            Log.e(TAG, "serve ${session.uri} failed", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain",
                "proxy error: ${e.message}",
            )
        }
    }


    private fun serveAsset(uri: String): Response? {
        val relative = when {
            uri == "/" -> "index.html"
            uri.startsWith("/") -> uri.drop(1)
            else -> uri
        }.substringBefore('?')
        val path = "web/$relative"
        val (data, mime) = try {
            if (webRoot == null) {
                assets.open(path).use { it.readBytes() } to mimeFor(path)
            } else {
                val root = webRoot.canonicalFile
                val file = File(root, relative).canonicalFile
                if (file.path != root.path && !file.path.startsWith(root.path + File.separator)) return null
                if (!file.isFile) return null
                FileInputStream(file).use { it.readBytes() } to mimeFor(file.name)
            }
        } catch (_: IOException) {
            return null
        }
        return newFixedLengthResponse(
            Response.Status.OK, mime, ByteArrayInputStream(data), data.size.toLong(),
        )
    }

    private fun passthroughCloud(session: IHTTPSession): Response {
        val url = config.cloudBaseUrl + session.uri +
                (session.queryParameterString?.let { "?$it" } ?: "")
        val builder = Request.Builder().url(url)
        when (session.method) {
            Method.GET, Method.HEAD -> builder.get()
            Method.POST -> {
                val len = session.headers["content-length"]?.toIntOrNull() ?: 0
                val body = ByteArray(len)
                session.inputStream.read(body, 0, len)
                val type = session.headers["content-type"] ?: "application/octet-stream"
                builder.post(body.toRequestBody(type.toMediaTypeOrNull()))
            }
            else -> return newFixedLengthResponse(
                Response.Status.METHOD_NOT_ALLOWED, "text/plain", "",
            )
        }
        val resp = cloud.execute { builder.build() }
        val bytes = resp.body?.bytes() ?: ByteArray(0)
        val mime = resp.header("content-type") ?: "application/octet-stream"
        val out = newFixedLengthResponse(
            Response.Status.lookup(resp.code) ?: Response.Status.OK,
            mime,
            ByteArrayInputStream(bytes),
            bytes.size.toLong(),
        )
        resp.close()
        return out
    }

    companion object {
        private const val TAG = "ReaderShellProxy"
        fun mimeFor(path: String): String = when (path.substringAfterLast('.').lowercase()) {
            "html", "htm" -> "text/html; charset=utf-8"
            "js", "mjs"   -> "application/javascript; charset=utf-8"
            "css"         -> "text/css; charset=utf-8"
            "json"        -> "application/json; charset=utf-8"
            "svg"         -> "image/svg+xml"
            "png"         -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp"        -> "image/webp"
            "gif"         -> "image/gif"
            "woff"        -> "font/woff"
            "woff2"       -> "font/woff2"
            "ttf"         -> "font/ttf"
            "otf"         -> "font/otf"
            "epub"        -> "application/epub+zip"
            "webmanifest" -> "application/manifest+json"
            else          -> "application/octet-stream"
        }
    }
}
