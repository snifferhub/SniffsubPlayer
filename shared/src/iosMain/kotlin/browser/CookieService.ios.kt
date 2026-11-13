@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.sniffsub.sniff_sub_player.browser

import com.sniffsub.sniff_sub_player.platform.parseWebUrl
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSDate
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSURL
import platform.Foundation.timeIntervalSince1970
import platform.WebKit.WKHTTPCookieStore
import platform.WebKit.WKWebsiteDataStore

actual fun createCookieService(): CookieService =
    IosCookieService(WKWebsiteDataStore.defaultDataStore().httpCookieStore)

class IosCookieService(private val store: WKHTTPCookieStore) : CookieService {
    private suspend fun allCookies(): List<NSHTTPCookie> =
        suspendCancellableCoroutine { continuation ->
            store.getAllCookies { cookies ->
                if (continuation.isActive) continuation.resume(cookies.orEmpty().filterIsInstance<NSHTTPCookie>())
            }
        }

    override suspend fun requestHeader(url: String): String = withContext(Dispatchers.Main) {
        val target = parseWebUrl(url) ?: return@withContext ""
        val now = NSDate().timeIntervalSince1970
        val matching = allCookies().filter { cookie ->
            val domain = cookie.domain.lowercase()
            val hostMatches = target.host == domain.removePrefix(".") ||
                (domain.startsWith(".") && target.host.endsWith(domain))
            val path = cookie.path.ifEmpty { "/" }
            val pathMatches = target.path == path ||
                (target.path.startsWith(path) && (path.endsWith("/") || target.path.getOrNull(path.length) == '/'))
            hostMatches && pathMatches && (!cookie.secure || target.scheme == "https") &&
                (cookie.expiresDate?.timeIntervalSince1970?.let { it > now } != false)
        }.sortedByDescending { it.path.length }
        NSHTTPCookie.requestHeaderFieldsWithCookies(matching)["Cookie"] as? String ?: ""
    }

    override suspend fun setCookie(url: String, setCookieHeader: String) = withContext(Dispatchers.Main) {
        require(parseWebUrl(url) != null)
        require(setCookieHeader.none { it == '\r' || it == '\n' })
        val cookies = NSHTTPCookie.cookiesWithResponseHeaderFields(
            mapOf("Set-Cookie" to setCookieHeader), NSURL(string = url),
        ).filterIsInstance<NSHTTPCookie>()
        require(cookies.isNotEmpty()) { "Invalid Set-Cookie header" }
        for (cookie in cookies) {
            suspendCancellableCoroutine<Unit> { continuation ->
                store.setCookie(cookie) {
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
        }
    }

    override suspend fun clearAll() = withContext(Dispatchers.Main) {
        for (cookie in allCookies()) {
            suspendCancellableCoroutine<Unit> { continuation ->
                store.deleteCookie(cookie) {
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
        }
    }
}
