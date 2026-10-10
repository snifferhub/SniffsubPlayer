package com.sniffsub.sniff_sub_player.updates

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AndroidAppUpdatesTest {
    @Test
    fun installer_uses_content_uri_and_provider_cannot_share_unrelated_app_files() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = updateApkFile(context)
        file.parentFile!!.mkdirs()
        file.writeText("APK fixture")
        try {
            val intent = updateInstallIntent(context)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("content", intent.data!!.scheme)
            assertEquals("${context.packageName}.updates", intent.data!!.authority)
            assertEquals("application/vnd.android.package-archive", intent.type)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertThrows(IllegalArgumentException::class.java) {
                FileProvider.getUriForFile(context, "${context.packageName}.updates", File(context.getExternalFilesDir(null), "private.json"))
            }
        } finally { file.delete() }
    }

    @Test
    fun pending_download_metadata_survives_store_recreation_and_clear() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val first = SharedPreferencesAppUpdateStore(context)
        first.clear()
        val pending = PendingAppUpdate(99, sampleRelease())
        first.write(pending)
        val second = SharedPreferencesAppUpdateStore(context)
        assertEquals(pending, second.read())
        second.clear()
        assertNull(first.read())
    }
}
