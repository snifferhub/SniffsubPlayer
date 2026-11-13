package com.sniffsub.sniff_sub_player.util

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface HttpFetcher {
    suspend fun get(
        url: String,
        headers: Map<String, String>,
        timeoutMillis: Long,
        maximumBytes: Int,
    ): ByteArray
}

class UrlConnectionHttpFetcher : HttpFetcher {
    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        timeoutMillis: Long,
        maximumBytes: Int,
    ): ByteArray = withContext(Dispatchers.IO) {
        require(maximumBytes >= 0) { "maximumBytes must not be negative" }
        val timeout = timeoutMillis.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = timeout
            connection.readTimeout = timeout
            connection.instanceFollowRedirects = true
            headers.forEach(connection::setRequestProperty)

            val status = connection.responseCode
            if (status !in 200..299) {
                throw IOException("HTTP $status")
            }

            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream(minOf(maximumBytes, DEFAULT_BUFFER_SIZE))
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val remainingProbeBytes = maximumBytes + 1L - total
                    val count = input.read(
                        buffer,
                        0,
                        minOf(buffer.size.toLong(), remainingProbeBytes).toInt(),
                    )
                    if (count < 0) break
                    total += count
                    if (total > maximumBytes) {
                        throw IOException("Response exceeds $maximumBytes bytes")
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }
}
