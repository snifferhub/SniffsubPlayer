package com.sniffsub.sniff_sub_player.browser

/**
 * Uses the native browser's cookie store, including HttpOnly cookies.
 * Call for each destination URL; never reuse a Cookie header across hosts.
 */
interface CookieService {
    suspend fun requestHeader(url: String): String
    suspend fun setCookie(url: String, setCookieHeader: String)
    suspend fun clearAll()
}

expect fun createCookieService(): CookieService
