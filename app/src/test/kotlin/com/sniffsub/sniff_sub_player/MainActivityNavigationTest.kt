package com.sniffsub.sniff_sub_player

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MainActivityNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun bottom_tabs_restore_interactive_browser_repeatedly() {
        val original = findWebView(compose.activity.window.decorView)
        repeat(5) {
            compose.onNodeWithTag("settings_tab").performClick()
            compose.onNodeWithTag("user_agent_row").assertExists()
            compose.onNodeWithTag("settings_tab").assertIsSelected()
            compose.runOnIdle {
                assertEquals(View.INVISIBLE, original.visibility)
            }
            compose.onNodeWithTag("browser_tab").performClick()
            compose.onNodeWithTag("browser_tab").assertIsSelected()
            compose.onNodeWithTag("user_agent_row").assertDoesNotExist()
            compose.runOnIdle {
                assertSame(original, findWebView(compose.activity.window.decorView))
                assertEquals(View.VISIBLE, original.visibility)
            }
            compose.onNodeWithTag("address_field").performClick().assertIsFocused()
        }
    }

    @Test
    fun system_back_returns_from_nested_settings_then_browser() {
        compose.onNodeWithTag("settings_tab").performClick()
        compose.onNodeWithTag("bookmarks_row").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("user_agent_row").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("user_agent_row").assertDoesNotExist()
        pressBack()
        compose.onNodeWithTag("user_agent_row").assertExists()
        pressBack()
        compose.onNodeWithTag("user_agent_row").assertDoesNotExist()
        compose.onNodeWithTag("address_field").performClick().assertIsFocused()
    }

    @Test
    fun home_uses_bottom_navigation_and_disables_sniff_without_a_page() {
        compose.onNodeWithTag("bottom_navigation").assertExists()
        compose.onNodeWithTag("browser_tab").assertIsSelected()
        compose.onNodeWithTag("settings_button").assertDoesNotExist()
        compose.onNodeWithTag("candidate_panel").assertDoesNotExist()
        compose.onNodeWithTag("sniff_button").assertIsNotEnabled()
    }

    @Test
    fun settings_tab_returns_from_nested_page_to_settings_root() {
        compose.onNodeWithTag("settings_tab").performClick()
        compose.onNodeWithTag("bookmarks_row").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("user_agent_row").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("settings_tab").performClick()
        compose.onNodeWithTag("user_agent_row").assertExists()
        compose.onNodeWithTag("browser_tab").performClick()
        compose.onNodeWithTag("address_field").performClick().assertIsFocused()
    }

    private fun pressBack() {
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    }

    private fun findWebView(view: View): WebView {
        fun search(node: View): WebView? {
            if (node is WebView) return node
            if (node is ViewGroup) {
                for (index in 0 until node.childCount) {
                    search(node.getChildAt(index))?.let { return it }
                }
            }
            return null
        }
        return requireNotNull(search(view))
    }
}
