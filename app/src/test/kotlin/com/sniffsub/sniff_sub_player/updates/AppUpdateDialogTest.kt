package com.sniffsub.sniff_sub_player.updates

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppUpdateDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun available_update_requires_explicit_download_and_cannot_install() {
        var downloads = 0
        compose.setContent {
            MaterialTheme {
                AppUpdateDialog(AppUpdateState(UpdateStage.AVAILABLE, sampleRelease()), "1.1.0 · build 18",
                    {}, { downloads++ }, {}, {}, {})
            }
        }
        compose.onNodeWithText("发现新版本").assertExists()
        compose.onNodeWithTag("install_update_button").assertDoesNotExist()
        compose.onNodeWithTag("download_update_button").performClick()
        assertEquals(1, downloads)
    }

    @Test
    fun preparing_install_disables_duplicate_install_taps() {
        compose.setContent {
            AppUpdateDialog(AppUpdateState(UpdateStage.READY, sampleRelease()), "1.1.0 · build 18",
                {}, {}, {}, {}, {}, installing = true)
        }
        compose.onNodeWithTag("install_update_button").assertIsNotEnabled()
    }

    @Test
    fun network_failure_offers_check_retry() {
        var retries = 0
        compose.setContent {
            AppUpdateDialog(AppUpdateState(UpdateStage.ERROR, message = "网络连接失败"), "1.1.0 · build 18",
                { retries++ }, {}, {}, {}, {})
        }
        compose.onNodeWithText("网络连接失败").assertExists()
        compose.onNodeWithTag("retry_update_button").performClick()
        assertEquals(1, retries)
    }
}
