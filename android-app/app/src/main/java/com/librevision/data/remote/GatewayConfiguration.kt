package com.librevision.data.remote

import java.net.URI

object GatewayConfiguration {
    const val LOCAL_URL = "https://megalarper.onrender.com"
    const val UNAVAILABLE = "Netra Saathi Cloud AI is not connected. Local safety features still work."

    fun validUrl(value: String, debug: Boolean): Boolean = runCatching {
        if (value.isBlank()) return true
        val uri = URI(value)
        val host = uri.host
        !host.isNullOrBlank() &&
            uri.userInfo == null &&
            uri.rawQuery == null &&
            uri.fragment == null &&
            uri.path in listOf("", "/") &&
            (uri.scheme == "https" ||
                (debug && uri.scheme == "http" && (
                    host in setOf("127.0.0.1", "localhost", "10.0.2.2") ||
                    host.startsWith("192.168.") ||
                    host.startsWith("10.") ||
                    host.startsWith("172.")
                )))
    }
        .getOrDefault(false)

    fun resolve(bundledUrl: String, debug: Boolean, developerOverride: String?): String {
        if (debug && !developerOverride.isNullOrBlank() && validUrl(developerOverride, true)) {
            return developerOverride.trimEnd('/')
        }
        if (bundledUrl.isNotBlank() && validUrl(bundledUrl, false)) return bundledUrl.trimEnd('/')
        return if (debug) LOCAL_URL else ""
    }
}
