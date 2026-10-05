package com.sniffsub.sniff_sub_player

import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MainActivityNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun settings_toolbar_back_restores_interactive_browser_repeatedly() {
        repeat(2) {
            compose.onNodeWithTag("settings_button").performClick()
            compose.onNodeWithTag("user_agent_row").assertExists()
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithTag("user_agent_row").assertDoesNotExist()
            compose.onNodeWithTag("address_field").performClick().assertIsFocused()
        }
    }

    @Test
    fun system_back_returns_from_nested_settings_then_browser() {
        compose.onNodeWithTag("settings_button").performClick()
        compose.onNodeWithTag("bookmarks_row").performClick()
        compose.onNodeWithTag("user_agent_row").assertDoesNotExist()
        pressBack()
        compose.onNodeWithTag("user_agent_row").assertExists()
        pressBack()
        compose.onNodeWithTag("user_agent_row").assertDoesNotExist()
        compose.onNodeWithTag("address_field").performClick().assertIsFocused()
    }

    private fun pressBack() {
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    }
}
