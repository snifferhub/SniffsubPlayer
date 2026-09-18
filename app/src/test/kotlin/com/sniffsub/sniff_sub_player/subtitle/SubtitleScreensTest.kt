package com.sniffsub.sniff_sub_player.subtitle

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.sniffsub.sniff_sub_player.util.HttpFetcher
import org.junit.Assert.assertFalse
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
