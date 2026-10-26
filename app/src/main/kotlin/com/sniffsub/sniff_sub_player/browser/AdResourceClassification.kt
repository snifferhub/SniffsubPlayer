package com.sniffsub.sniff_sub_player.browser

import android.webkit.WebResourceRequest
import com.sniffsub.sniff_sub_player.adblock.ResourceType
import java.net.URI

internal fun classifyWebResource(request: WebResourceRequest): ResourceType = classifyWebResource(
    request.url.toString(), request.isForMainFrame, request.requestHeaders,
)

internal fun classifyWebResource(url: String, isMainFrame: Boolean, headers: Map<String, String>): ResourceType {
    if (isMainFrame) return ResourceType.DOCUMENT
    fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value.orEmpty().lowercase()
    when (header("Sec-Fetch-Dest")) {
        "script", "worker", "sharedworker", "serviceworker" -> return ResourceType.SCRIPT
        "image" -> return ResourceType.IMAGE
        "style" -> return ResourceType.STYLESHEET
        "font" -> return ResourceType.FONT
        "audio", "video", "track" -> return ResourceType.MEDIA
        "iframe", "frame", "document" -> return ResourceType.SUBDOCUMENT
    }
    if (header("X-Requested-With") == "xmlhttprequest") return ResourceType.XMLHTTPREQUEST
    val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault("")
    val extension = path.substringAfterLast('.', "")
    return when {
        extension in setOf("js", "mjs") -> ResourceType.SCRIPT
        extension == "css" -> ResourceType.STYLESHEET
        extension in setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "avif") || header("Accept").startsWith("image/") -> ResourceType.IMAGE
        extension in setOf("woff", "woff2", "ttf", "otf") -> ResourceType.FONT
        extension in setOf("mp4", "m3u8", "mpd", "webm", "ts", "m4s", "mp3", "aac", "ogg") -> ResourceType.MEDIA
        header("Accept").contains("text/html") -> ResourceType.SUBDOCUMENT
        else -> ResourceType.OTHER
    }
}
