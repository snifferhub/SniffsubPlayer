package com.sniffsub.sniff_sub_player.subtitle

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.sniffsub.sniff_sub_player.util.HttpFetcher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import androidx.compose.ui.test.assertIsNotEnabled
import kotlinx.coroutines.CompletableDeferred
import java.io.IOException
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SubtitleScreensTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun system_back_from_paste_returns_to_search_with_query_and_results_preserved() {
        showSearchAndLoadResult()

        compose.onNodeWithContentDescription("粘贴字幕").performClick()
        compose.onNodeWithText("粘贴 SRT / VTT").assertExists()
        pressSystemBack()

        assertSearchStateWasPreserved()
    }

    @Test
    fun system_back_from_subtitlecat_returns_to_search_with_query_and_results_preserved() {
        showSearchAndLoadResult()

        compose.onNodeWithText("打开 Subtitlecat").performClick()
        compose.onNodeWithText("Subtitlecat").assertExists()
        pressSystemBack()

        assertSearchStateWasPreserved()
    }

    @Test
    fun tapping_the_result_loads_subtitles_and_disables_other_results_while_loading() {
        val download = CompletableDeferred<ByteArray>()
        val urls = mutableListOf<String>()
        val selected = mutableListOf<Pair<String, String>>()
        val repository = SubtitleRepository(object : HttpFetcher {
            override suspend fun get(
                url: String,
                headers: Map<String, String>,
                timeoutMillis: Long,
                maximumBytes: Int,
            ): ByteArray {
                if (url.contains("/oracle/subtitle")) return """{"data":[{"name":"Result One","url":"https://cdn.test/one.srt"},{"name":"Result Two","url":"https://cdn.test/two.srt"}]}""".encodeToByteArray()
                urls += url
                return download.await()
            }
        })
        compose.setContent {
            SubtitleSearchScreen(
                initialQuery = "ABC-123",
                repository = repository,
                onClose = {},
                onSubtitleSelected = { text, label -> selected += text to label },
            )
        }
        compose.onNodeWithContentDescription("字幕搜索").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Result One").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("载入").assertDoesNotExist()
        compose.onNodeWithText("Result One").performClick()
        compose.onNodeWithText("Result Two").assertIsNotEnabled()
        compose.onNodeWithText("Result Two").performClick()
        compose.runOnIdle {
            assertEquals(listOf("https://cdn.test/one.srt"), urls)
            download.complete("1\n00:00:01,000 --> 00:00:02,000\n字幕".encodeToByteArray())
        }
        compose.waitUntil(5_000) { selected.isNotEmpty() }
        compose.runOnIdle { assertEquals("Result One", selected.single().second) }
    }

    @Test
    fun a_failed_download_can_be_retried_by_tapping_the_result_again() {
        var attempts = 0
        var selected = false
        val repository = SubtitleRepository(object : HttpFetcher {
            override suspend fun get(
                url: String,
                headers: Map<String, String>,
                timeoutMillis: Long,
                maximumBytes: Int,
            ): ByteArray {
                if (url.contains("/oracle/subtitle")) return """{"data":[{"name":"Result One","url":"https://cdn.test/one.srt"}]}""".encodeToByteArray()
                if (++attempts == 1) throw IOException("offline")
                return "字幕".encodeToByteArray()
            }
        })
        compose.setContent {
            SubtitleSearchScreen("ABC-123", repository, {}, { _, _ -> selected = true })
        }
        compose.onNodeWithContentDescription("字幕搜索").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Result One").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Result One").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("字幕下载失败: offline").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Result One").performClick()
        compose.waitUntil(5_000) { selected }
        compose.runOnIdle { assertEquals(2, attempts) }
    }

    private fun showSearchAndLoadResult() {
        val repository = SubtitleRepository(
            ScreenFakeHttpFetcher(
                """{"data":[{"name":"Result One","url":"https://cdn.test/one.srt"}]}"""
                    .encodeToByteArray(),
            ),
        )
        compose.setContent {
            SubtitleSearchScreen(
                initialQuery = "ABC-123",
                repository = repository,
                onClose = {},
                onSubtitleSelected = { _, _ -> },
            )
        }
        compose.onNodeWithContentDescription("字幕搜索").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Result One").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Result One").assertExists()
    }

    private fun pressSystemBack() {
        compose.runOnIdle {
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()
    }

    private fun assertSearchStateWasPreserved() {
        assertFalse(compose.activity.isFinishing)
        compose.onNodeWithText("在线字幕").assertExists()
        compose.onNodeWithText("ABC-123").assertExists()
        compose.onNodeWithText("Result One").assertExists()
    }
}

private class ScreenFakeHttpFetcher(
    private val response: ByteArray,
) : HttpFetcher {
    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        timeoutMillis: Long,
        maximumBytes: Int,
    ): ByteArray = response
}
