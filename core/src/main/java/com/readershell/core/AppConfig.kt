package com.readershell.core

/**
 * Per-app configuration the proxy needs. honlib and galib each provide
 * an implementation; core/ stays app-agnostic.
 */
interface AppConfig {
    /** Cloud base URL, e.g. "https://ebook.example.com". No trailing slash. */
    val cloudBaseUrl: String

    /** Password env-var equivalent, plain text. Stored in keystore by Auth. */
    val authPasswordKey: String  // e.g. "EBOOK_LIB_PASSWORD" — label only

    /** Compute the content id used by the server from a library-relative POSIX path. */
    fun contentIdFor(relativePosixPath: String): String

    /** File extensions to index under the local root. */
    val indexedExtensions: Set<String>

    /** Localhost port for the embedded server. */
    val proxyPort: Int

    /**
     * App id for the OTA web-bundle updater, matching the backend's APP_ID
     * (e.g. "honlib"). When non-null the shell pulls versioned web bundles from
     * the backend's app-bundle endpoints and serves the active one in preference
     * to the APK-baked assets. null (the default) disables OTA — the app serves only
     * its bundled web UI, exactly as before.
     */
    val appBundleId: String? get() = null
}
