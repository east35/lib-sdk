package com.readershell.manga

import com.readershell.core.AppConfig
import java.net.URLDecoder
import java.net.URLEncoder

class MangaConfig(override val cloudBaseUrl: String) : AppConfig {
    override val bundleAppId = "galib"
    override val authPasswordKey = "MANGA_DL_PASSWORD"
    override val indexedExtensions = setOf("cbz")
    override val proxyPort = 38766

    override fun contentIdFor(relativePosixPath: String): String = relativePosixPath

    companion object {
        fun encodePathPart(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        fun decodePathPart(value: String): String =
            URLDecoder.decode(value, "UTF-8")
    }
}
