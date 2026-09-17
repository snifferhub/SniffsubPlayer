package com.sniffsub.sniff_sub_player.settings

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.sniffsub.sniff_sub_player.adblock.AdBlockState
import com.sniffsub.sniff_sub_player.adblock.AdBlockSubscription
import com.sniffsub.sniff_sub_player.data.AppPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun settings_selects_exact_user_agent_and_accent_and_opens_bookmarks() {
        var selectedUserAgent: String? = null
        var selectedAccent: Long? = null
        var openedBookmarks = false
        compose.setContent {
            SettingsScreen(
                preferences = AppPreferences(),
                actions = noOpSettingsActions().copy(
                    onUserAgentSelected = { selectedUserAgent = it },
                    onAccentSelected = { selectedAccent = it },
                    onOpenBookmarks = { openedBookmarks = true },
                ),
            )
        }

        compose.onNodeWithTag("user_agent_row").performClick()
        compose.onNodeWithTag("ua_ipad_safari").performClick()
        assertEquals(IPAD_SAFARI_USER_AGENT, selectedUserAgent)

        compose.onNodeWithTag("accent_color_row").performClick()
        compose.onNodeWithTag("accent_color_FFFF2D55").performClick()
        assertEquals(0xFFFF2D55L, selectedAccent)

        compose.onNodeWithTag("bookmarks_row").performScrollTo().performClick()
        assertTrue(openedBookmarks)
    }

    @Test
    fun ad_block_controls_toggle_and_builtin_subscription_cannot_be_deleted() {
        val builtIn = AdBlockSubscription(
            id = "built-in",
            name = "内置规则",
            url = "https://example.test/built-in.txt",
            enabled = true,
            builtIn = true,
        )
        val custom = AdBlockSubscription(
            id = "custom",
            name = "自定义规则",
            url = "https://example.test/custom.txt",
            enabled = true,
        )
        var adBlockEnabled: Boolean? = null
        var disabledSubscription: String? = null
        var removedSubscription: String? = null
        compose.setContent {
            AdBlockSettingsScreen(
                state = AdBlockState(
                    isEnabled = false,
                    subscriptions = listOf(builtIn, custom),
                ),
                actions = noOpAdBlockActions().copy(
                    onSetEnabled = { adBlockEnabled = it },
                    onSetSubscriptionEnabled = { id, enabled ->
                        if (!enabled) disabledSubscription = id
                    },
                    onRemoveSubscription = { removedSubscription = it },
                ),
            )
        }

        compose.onNodeWithTag("ad_block_enabled_switch").performClick()
        assertEquals(true, adBlockEnabled)
        compose.onNodeWithTag("subscription_switch_custom").performScrollTo().performClick()
        assertEquals("custom", disabledSubscription)
        compose.onNodeWithTag("delete_subscription_built-in").assertDoesNotExist()
        compose.onNodeWithTag("delete_subscription_custom").performScrollTo().performClick()
        assertEquals("custom", removedSubscription)
    }

    @Test
    fun add_subscription_rejects_http_before_invoking_repository_action() {
        var addedUrl: String? = null
        compose.setContent {
            AdBlockSettingsScreen(
                state = AdBlockState(),
                actions = noOpAdBlockActions().copy(onAddSubscription = { addedUrl = it }),
            )
        }

        compose.onNodeWithTag("add_subscription_button").performClick()
        compose.onNodeWithTag("subscription_url_field").performTextInput("http://rules.test/filter.txt")
        compose.onNodeWithTag("confirm_add_subscription").performClick()

        assertNull(addedUrl)
        compose.onNodeWithTag("subscription_validation_error")
            .assertTextContains("请输入有效的 HTTPS raw 规则链接")
    }

    @Test
    fun exported_options_keep_the_exact_values_and_palette() {
        assertEquals("", USER_AGENT_OPTIONS.first().value)
        assertEquals(
            "Mozilla/5.0 (iPad; CPU OS 17_0 like Mac OS X) " +
                "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 " +
                "Mobile/15E148 Safari/604.1",
            IPAD_SAFARI_USER_AGENT,
        )
        assertEquals(
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) " +
                "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15",
            MACOS_SAFARI_USER_AGENT,
        )
        assertEquals(
            listOf(
                0xFFE8A7BFL,
                0xFFFF2D55L,
                0xFFFF9500L,
                0xFFFFCC00L,
                0xFF34C759L,
                0xFF00C7BEL,
                0xFF007AFFL,
                0xFF5856D6L,
                0xFFAF52DEL,
                0xFFFFFFFFL,
                0xFF8E8E93L,
                0xFF1C1C1EL,
            ),
            ACCENT_COLOR_PALETTE,
        )
    }

    private fun noOpSettingsActions() = SettingsActions(
        onBack = {},
        onUserAgentSelected = {},
        onAccentSelected = {},
        onResetAccent = {},
        onOpenBookmarks = {},
        onOpenAdBlock = {},
    )

    private fun noOpAdBlockActions() = AdBlockSettingsActions(
        onBack = {},
        onSetEnabled = {},
        onSetAutomaticUpdatesEnabled = {},
        onSetSubscriptionEnabled = { _, _ -> },
        onAddSubscription = {},
        onRemoveSubscription = {},
        onRefresh = {},
    )
}
