package com.sniffsub.sniff_sub_player

import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MainActivityNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun bottom_tabs_restore_interactive_browser_repeatedly() {
        repeat(2) {
            compose.onNodeWithTag("settings_tab").performClick()
            compose.onNodeWithTag("user_agent_row").assertExists()
            compose.onNodeWithTag("settings_tab").assertIsSelected()
            compose.onNodeWithTag("browser_tab").performClick()
            compose.onNodeWithTag("browser_tab").assertIsSelected()
            compose.onNodeWithTag("user_agent_row").assertDoesNotExist()
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
}
