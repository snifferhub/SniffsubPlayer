package com.sniffsub.sniff_sub_player.player

import android.content.Context
import android.view.TextureView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlayerViewSurfaceTest {
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test
    fun video_is_composited_inside_the_view_hierarchy_instead_of_a_separate_surface() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = createVideoPlayerView(context)
        assertTrue(view.videoSurfaceView is TextureView)
    }
}
