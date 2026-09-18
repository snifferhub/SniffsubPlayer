package com.sniffsub.sniff_sub_player.subtitle

import com.sniffsub.sniff_sub_player.util.HttpFetcher
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleRepositoryTest {
    @Test
    fun search_encodes_query_and_uses_the_xunlei_accept_header() = runTest {
        val fetcher = FakeHttpFetcher(
            responses = ArrayDeque(
                listOf("""{"data":[]}""".encodeToByteArray()),
            ),
        )
        val repository = SubtitleRepository(fetcher)

        assertEquals(emptyList<OnlineSubtitle>(), repository.search("AB C/中文?").getOrThrow())
        assertEquals(
            FetchRequest(
                url = "https://api-shoulei-ssl.xunlei.com/oracle/subtitle?name=AB+C%2F%E4%B8%AD%E6%96%87%3F",
                headers = mapOf("Accept" to "application/json, text/plain, */*"),
                timeoutMillis = 45_000,
                maximumBytes = 2 * 1024 * 1024,
            ),
            fetcher.requests.single(),
        )
    }

    @Test
    fun search_parses_robust_json_and_keeps_only_srt_or_vtt_urls() = runTest {
        val body = """
            {
              "data": [
                {"name":"One","extra_name":"简体","url":"https://cdn.test/one.SRT?token=x"},
                {"name":"Two","extra_name":"","url":"https://cdn.test/two.vtt"},
                {"name":7,"extra_name":{},"url":"https://cdn.test/three.srt"},
                {"name":"Archive","url":"https://cdn.test/four.srt.zip"},
                {"name":"Text","url":"https://cdn.test/five.txt"},
                {"name":"Local","url":"file:///sdcard/private.srt"},
                {"name":"Script","url":"javascript:payload.srt"},
                "not-an-object",
                null
              ]
            }
        """.trimIndent()
        val repository = SubtitleRepository(FakeHttpFetcher.response(body.encodeToByteArray()))
        val results = repository.search("ABC-123").getOrThrow()

        assertEquals(
            listOf(
                OnlineSubtitle("One", "简体", "https://cdn.test/one.SRT?token=x"),
                OnlineSubtitle("Two", "", "https://cdn.test/two.vtt"),
                OnlineSubtitle("", null, "https://cdn.test/three.srt"),
            ),
            results,
        )
        assertEquals("One (简体)", results[0].displayName)
        assertEquals("Two", results[1].displayName)
    }

    @Test
    fun search_tolerates_valid_json_with_missing_or_wrong_data_shape() = runTest {
        val fetcher = FakeHttpFetcher(
            responses = ArrayDeque(
                listOf(
                    "{}".encodeToByteArray(),
                    "[]".encodeToByteArray(),
                    """{"data":{"url":"https://cdn.test/a.srt"}}""".encodeToByteArray(),
                ),
            ),
        )
        val repository = SubtitleRepository(fetcher)

        repeat(3) {
            assertEquals(emptyList<OnlineSubtitle>(), repository.search("ABC-123").getOrThrow())
        }
    }

    @Test
    fun search_discards_http_urls_without_a_valid_host() = runTest {
        val body = """
            {
              "data": [
                {"name":"Missing authority","url":"https:/one.srt"},
                {"name":"Missing host","url":"https:///two.vtt"}
              ]
            }
        """.trimIndent()
        val repository = SubtitleRepository(FakeHttpFetcher.response(body.encodeToByteArray()))

        assertEquals(emptyList<OnlineSubtitle>(), repository.search("ABC-123").getOrThrow())
    }

    @Test
    fun malformed_json_is_reported_as_a_failure() = runTest {
        val repository = SubtitleRepository(FakeHttpFetcher.response("{".encodeToByteArray()))

        assertTrue(repository.search("ABC-123").isFailure)
    }

    @Test
    fun search_propagates_http_failures_from_the_fetcher() = runTest {
        val repository = SubtitleRepository(
            FakeHttpFetcher(failure = IOException("HTTP 503")),
        )

        val error = repository.search("ABC-123").exceptionOrNull()
        assertTrue(error is IOException)
        assertEquals("HTTP 503", error?.message)
    }

    @Test
    fun download_uses_text_accept_and_decodes_malformed_utf8_with_replacement() = runTest {
        val fetcher = FakeHttpFetcher.response(
            byteArrayOf(0xE4.toByte(), 0xB8.toByte(), 0xAD.toByte(), 0xC3.toByte(), 0x28),
        )
        val repository = SubtitleRepository(fetcher)

        assertEquals("中�(", repository.download("https://cdn.test/subtitle.srt").getOrThrow())
        assertEquals(
            FetchRequest(
                url = "https://cdn.test/subtitle.srt",
                headers = mapOf("Accept" to "text/plain,*/*"),
                timeoutMillis = 45_000,
                maximumBytes = 12 * 1024 * 1024,
            ),
            fetcher.requests.single(),
        )
    }

    @Test
    fun download_propagates_non_success_http_failure() = runTest {
        val repository = SubtitleRepository(
            FakeHttpFetcher(failure = IOException("HTTP 404")),
        )

        val error = repository.download("https://cdn.test/missing.srt").exceptionOrNull()
        assertTrue(error is IOException)
        assertEquals("HTTP 404", error?.message)
    }

    @Test
    fun download_rejects_non_http_urls_before_fetching() = runTest {
        val fetcher = FakeHttpFetcher()
        val repository = SubtitleRepository(fetcher)

        val error = repository.download("file:///sdcard/private.srt").exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(fetcher.requests.isEmpty())
    }

    @Test
    fun download_rejects_http_urls_without_a_valid_host_before_fetching() = runTest {
        val fetcher = FakeHttpFetcher()
        val repository = SubtitleRepository(fetcher)

        listOf("https:/one.srt", "https:///two.vtt").forEach { url ->
            val error = repository.download(url).exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
        }
        assertTrue(fetcher.requests.isEmpty())
    }
}

private data class FetchRequest(
    val url: String,
    val headers: Map<String, String>,
    val timeoutMillis: Long,
    val maximumBytes: Int,
)

private class FakeHttpFetcher(
    private val responses: ArrayDeque<ByteArray> = ArrayDeque(),
    private val failure: Exception? = null,
) : HttpFetcher {
    val requests = mutableListOf<FetchRequest>()

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        timeoutMillis: Long,
        maximumBytes: Int,
    ): ByteArray {
        requests += FetchRequest(url, headers, timeoutMillis, maximumBytes)
        failure?.let { throw it }
        return responses.removeFirst()
    }

    companion object {
        fun response(bytes: ByteArray) = FakeHttpFetcher(
            responses = ArrayDeque(listOf(bytes)),
        )
    }
}
