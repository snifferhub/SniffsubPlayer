package com.sniffsub.sniff_sub_player.browser

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BrowserScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun empty_home_keeps_address_entry_visible() {
        compose.setContent {
            BrowserScreen(
                state = BrowserUiState(),
                actions = noOpActions(),
            )
        }

        compose.onNodeWithTag("address_row").assertExists()
        compose.onNodeWithTag("address_field").assertExists().assertIsEnabled()
    }

    @Test
    fun chrome_keeps_expected_controls_and_enabled_states() {
        compose.setContent {
            BrowserScreen(
                state = sampleState(),
                actions = noOpActions(),
                webViewContent = { Text("WebView placeholder") },
            )
        }

        compose.onNodeWithTag("bookmark_button").assertExists().assertIsEnabled()
        compose.onNodeWithTag("settings_button").assertExists().assertIsEnabled()
        compose.onNodeWithTag("back_button").assertExists().assertIsNotEnabled()
        compose.onNodeWithTag("forward_button").assertExists().assertIsEnabled()
        compose.onNodeWithTag("address_field").assertExists()
        compose.onNodeWithTag("browser_webview").assertExists()
        compose.onNodeWithTag("candidate_panel").assertExists()
        compose.onNodeWithTag("enable_sniffer_button").assertIsEnabled()
        compose.onNodeWithTag("clear_candidates_button").assertIsEnabled()
    }

    @Test
    fun loading_disables_sniffer_and_empty_candidates_disable_clear() {
        compose.setContent {
            BrowserScreen(
                state = sampleState().copy(loading = true, candidates = emptyList()),
                actions = noOpActions(),
                webViewContent = {},
            )
        }

        compose.onNodeWithTag("enable_sniffer_button").assertIsNotEnabled()
        compose.onNodeWithTag("clear_candidates_button").assertIsNotEnabled()
    }

    @Test
    fun candidate_panel_caps_rows_at_thirty_and_opens_selected_candidate() {
        val candidates = (0 until 31).map { index -> candidate(index) }
        var opened: MediaCandidate? = null
        compose.setContent {
            BrowserScreen(
                state = sampleState().copy(candidates = candidates),
                actions = noOpActions().copy(onOpenCandidate = { opened = it }),
                webViewContent = {},
            )
        }

        compose.onAllNodesWithTag("candidate_row", useUnmergedTree = true).assertCountEquals(30)
        compose.onNodeWithTag("candidate_0").performClick()
        assertEquals(candidates.first(), opened)
    }

    @Test
    fun address_ime_submission_clears_focus_after_dispatch() {
        var submitted: String? = null
        compose.setContent {
            BrowserScreen(
                state = sampleState(),
                actions = noOpActions().copy(onSubmitAddress = { submitted = it }),
            )
        }

        val addressField = compose.onNodeWithTag("address_field")
        addressField.performClick()
        addressField.performTextClearance()
        addressField.performTextInput("example.com")
        addressField.performImeAction()

        assertEquals("example.com", submitted)
        addressField.assertIsNotFocused()
    }

    @Test
    fun system_back_leaves_address_editing_before_web_history() {
        var backCount = 0
        compose.setContent {
            BrowserScreen(
                state = sampleState().copy(canGoBack = true),
                actions = noOpActions().copy(onBack = { backCount++ }),
            )
        }

        compose.onNodeWithTag("address_field").performClick()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("address_field").assertIsNotFocused()
        compose.runOnIdle { assertEquals(0, backCount) }

        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle { assertEquals(1, backCount) }
    }

    @Test
    fun transient_message_is_explicitly_consumed() {
        val consumed = AtomicInteger()
        compose.setContent {
            BrowserScreen(
                state = sampleState().copy(
                    transientMessage = "更新失败",
                    transientMessageRevision = 1L,
                ),
                actions = noOpActions().copy(
                    onConsumeTransientMessage = {
                        consumed.incrementAndGet()
                    },
                ),
            )
        }

        compose.waitUntil { consumed.get() == 1 }
    }

    private fun sampleState() = BrowserUiState(
        address = "https://example.test",
        title = "示例页面",
        currentPageUrl = "https://example.test",
        canGoForward = true,
        candidates = listOf(candidate(0)),
    )

    private fun candidate(index: Int) = MediaCandidate(
        url = "https://cdn.test/video-$index.m3u8",
        kind = MediaKind.HLS,
        source = "fetch",
        pageUrl = "https://example.test",
        referer = "https://example.test",
        userAgent = "UA",
        detectedAtEpochMs = index.toLong(),
    )

    private fun noOpActions() = BrowserActions(
        onToggleBookmark = {},
        onOpenSettings = {},
        onBack = {},
        onForward = {},
        onSubmitAddress = {},
        onEnableSniffer = {},
        onClearCandidates = {},
        onOpenCandidate = {},
        onConsumeTransientMessage = {},
    )
}
