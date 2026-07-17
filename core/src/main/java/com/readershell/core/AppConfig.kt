package com.readershell.core

/**
 * Per-app configuration the proxy needs. honlib and galib each provide
 * an implementation; core/ stays app-agnostic.
 */
interface AppConfig {
    /** Stable identity used by the backend app-bundle manifest. */
    val bundleAppId: String

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
}

/** Version of the native/web contract implemented by this shell. */
const val SHELL_API_VERSION = 1
