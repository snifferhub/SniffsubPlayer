package com.sniffsub.sniff_sub_player.browser

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
    fun editing_expands_address_without_moving_page_and_cancel_restores_url() {
        var submitted = 0
        compose.setContent {
            BrowserScreen(
                state = sampleState().copy(address = "https://example.test/watch?id=42", currentPageUrl = "https://example.test/watch?id=42"),
                actions = noOpActions().copy(onSubmitAddress = { submitted++ }),
            )
        }
        val address = compose.onNodeWithTag("address_field")
        address.assertTextEquals("example.test")
        val compact = address.fetchSemanticsNode().boundsInRoot
        val pageTop = compose.onNodeWithTag("browser_webview").fetchSemanticsNode().boundsInRoot.top
        address.performClick()
        address.assertTextEquals("https://example.test/watch?id=42")
        org.junit.Assert.assertTrue(address.fetchSemanticsNode().boundsInRoot.width > compact.width)
        assertEquals(pageTop, compose.onNodeWithTag("browser_webview").fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.onNodeWithTag("back_button").assertDoesNotExist()
        compose.onNodeWithTag("browser_menu_button").assertDoesNotExist()
        compose.onNodeWithTag("clear_address_button").performClick()
        address.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        address.performTextInput("discarded.test")
        compose.onNodeWithTag("cancel_address_button").performClick()
        address.assertIsNotFocused().assertTextEquals("example.test")
        address.performClick()
        address.assertTextEquals("https://example.test/watch?id=42")
        assertEquals(0, submitted)
    }

    @Test
    fun chrome_moves_forward_and_bookmark_to_menu_with_correct_actions() {
        var forwarded = 0
        var bookmarked = 0
        compose.setContent {
            BrowserScreen(
                state = sampleState(),
                actions = noOpActions().copy(onForward = { forwarded++ }, onToggleBookmark = { bookmarked++ }),
                webViewContent = { Text("WebView placeholder") },
            )
        }
        compose.onNodeWithTag("settings_button").assertDoesNotExist()
        compose.onNodeWithTag("back_button").assertExists().assertIsNotEnabled()
        compose.onNodeWithTag("forward_button").assertDoesNotExist()
        compose.onNodeWithTag("bookmark_button").assertDoesNotExist()
        compose.onNodeWithTag("browser_menu_button").performClick()
        compose.onNodeWithTag("bookmark_button").assertIsEnabled()
        compose.onNodeWithTag("forward_button").assertIsEnabled().performClick()
        assertEquals(1, forwarded)
        compose.onNodeWithTag("browser_menu_button").performClick()
        compose.onNodeWithTag("bookmark_button").performClick()
        assertEquals(1, bookmarked)
        compose.onNodeWithTag("address_field").assertExists()
        compose.onNodeWithTag("browser_webview").assertExists()
        compose.onNodeWithTag("candidate_panel").assertDoesNotExist()
        compose.onNodeWithTag("enable_sniffer_button").assertDoesNotExist()
    }

    @Test
    fun reload_button_dispatches_refresh_and_is_disabled_without_a_page() {
        var refreshed = 0
        compose.setContent {
            BrowserScreen(state = sampleState(), actions = noOpActions().copy(onReload = { refreshed++ }))
        }
        compose.onNodeWithTag("reload_button").assertIsEnabled().performClick()
        assertEquals(1, refreshed)
    }

    @Test
    fun empty_page_cannot_refresh() {
        compose.setContent { BrowserScreen(state = BrowserUiState(), actions = noOpActions()) }
        compose.onNodeWithTag("reload_button").assertIsNotEnabled()
    }

    @Test
    fun sheet_loading_disables_rescan_and_empty_candidates_disable_clear() {
        compose.setContent {
            BrowserScreen(
                state = sampleState().copy(loading = true, candidates = emptyList(), candidateSheetVisible = true),
                actions = noOpActions(),
                webViewContent = {},
            )
        }

        compose.onNodeWithTag("rescan_button").assertIsNotEnabled()
        compose.onNodeWithTag("clear_candidates_button").assertIsNotEnabled()
    }

    @Test
    fun candidate_panel_caps_rows_at_thirty_and_opens_selected_candidate() {
        val candidates = (0 until 31).map { index -> candidate(index) }
        var opened: MediaCandidate? = null
        compose.setContent {
            BrowserScreen(
                state = sampleState().copy(candidates = candidates, candidateSheetVisible = true),
                actions = noOpActions().copy(onOpenCandidate = { opened = it }),
                webViewContent = {},
            )
        }

        compose.onNodeWithTag("candidate_list").performScrollToIndex(29)
        compose.onNodeWithTag("candidate_29").assertExists()
        compose.onNodeWithTag("candidate_30").assertDoesNotExist()
        compose.onNodeWithTag("candidate_list").performScrollToIndex(0)
        compose.onNodeWithTag("candidate_0").performClick()
        compose.waitUntil { opened != null }
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
        onBack = {},
        onForward = {},
        onSubmitAddress = {},
        onRequestSniff = {},
        onDismissCandidates = {},
        onClearCandidates = {},
        onOpenCandidate = {},
        onConsumeTransientMessage = {},
    )
}
