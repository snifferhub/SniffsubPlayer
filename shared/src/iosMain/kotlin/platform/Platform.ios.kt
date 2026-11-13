@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.sniffsub.sniff_sub_player.platform

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.NSURLComponents
import platform.Foundation.NSUUID
import platform.Foundation.timeIntervalSince1970

actual fun epochMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()
actual fun randomId(): String = NSUUID().UUIDString
actual fun formatLocalDateTime(epochMs: Long): String = NSDateFormatter().apply {
    dateFormat = "yyyy-MM-dd HH:mm"
}.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0))

actual fun parseWebUrl(value: String): WebUrl? {
    if (value.any { it.isWhitespace() || it.isISOControl() }) return null
    val url = NSURLComponents.componentsWithString(value) ?: return null
    val scheme = url.scheme?.lowercase() ?: return null
    val host = url.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    if (scheme !in setOf("http", "https")) return null
    return WebUrl(scheme, host, url.port?.intValue ?: -1, url.percentEncodedPath.orEmpty().ifEmpty { "/" })
}
