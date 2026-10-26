package com.sniffsub.sniff_sub_player.browser

import android.webkit.CookieManager
import com.sniffsub.sniff_sub_player.platform.parseWebUrl
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

actual fun createCookieService(): CookieService = AndroidCookieService()

class AndroidCookieService : CookieService {
    override suspend fun requestHeader(url: String): String = withContext(Dispatchers.Main) {
        if (parseWebUrl(url) == null) return@withContext ""
        CookieManager.getInstance().getCookie(url).orEmpty()
    }

    override suspend fun setCookie(url: String, setCookieHeader: String) = withContext(Dispatchers.Main) {
        require(parseWebUrl(url) != null)
        require(setCookieHeader.none { it == '\r' || it == '\n' })
        val manager = CookieManager.getInstance()
        val accepted = suspendCancellableCoroutine<Boolean> { continuation ->
            manager.setCookie(url, setCookieHeader) {
                if (continuation.isActive) continuation.resume(it)
            }
        }
        check(accepted) { "The browser rejected the cookie" }
        withContext(Dispatchers.IO) { manager.flush() }
    }

    override suspend fun clearAll() = withContext(Dispatchers.Main) {
        val manager = CookieManager.getInstance()
        suspendCancellableCoroutine<Unit> { continuation ->
            manager.removeAllCookies {
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
        withContext(Dispatchers.IO) { manager.flush() }
    }
}
