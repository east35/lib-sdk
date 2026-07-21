package com.readershell.ebook

import com.readershell.core.AppConfig
import java.security.MessageDigest

/**
 * Ebook content scheme. Mirrors HonLib library.py
 * (https://github.com/east35/HonLib):
 *   book_id = sha256(library_relative_posix_path).hexdigest()[:16]
 */
class EbookConfig(override val cloudBaseUrl: String) : AppConfig {
    override val authPasswordKey = "EBOOK_LIB_PASSWORD"
    override val indexedExtensions = setOf("epub")
    override val proxyPort = 38765
    override val appBundleId = "honlib"  // matches HonLib web_bundle.py APP_ID

    override fun contentIdFor(relativePosixPath: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(relativePosixPath.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.substring(0, 16)
    }
}
