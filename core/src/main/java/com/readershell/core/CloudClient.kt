package com.readershell.core

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * Cloud HTTP client. Owns the session cookie jar, performs /login on demand,
 * retries once on 401. All cloud traffic in the shell goes through here.
 */
class CloudClient(
    private val config: AppConfig,
    private val auth: Auth,
) {
    private val jar = object : CookieJar {
        private val store = mutableMapOf<String, List<Cookie>>()
        @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            store[url.host] = cookies
        }
        @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> =
            store[url.host].orEmpty()
    }

    private val client = OkHttpClient.Builder()
        .cookieJar(jar)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Execute a cloud request, logging in transparently on 401. Caller closes the Response. */
    fun execute(reqBuilder: () -> Request): Response {
        val first = client.newCall(reqBuilder()).execute()
        if (first.code != 401) return first
        first.close()
        if (!login()) return client.newCall(reqBuilder()).execute()
        return client.newCall(reqBuilder()).execute()
    }

    private fun login(): Boolean {
        val pwd = auth.password ?: return false
        val body = FormBody.Builder().add("password", pwd).build()
        val req = Request.Builder()
            .url("${config.cloudBaseUrl}/login")
            .post(body)
            .build()
        client.newCall(req).execute().use { resp ->
            return resp.isSuccessful || resp.code == 302
        }
    }
}
