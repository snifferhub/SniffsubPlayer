package com.sniffsub.sniff_sub_player

import org.junit.Assert.assertEquals
import org.junit.Test

class BuildIdentityTest {
    @Test
    fun release_identity_is_1_1_0_for_android_9() {
        assertEquals("1.1.0", BuildIdentity.VERSION_NAME)
        org.junit.Assert.assertTrue(BuildIdentity.VERSION_CODE > 3)
        assertEquals(28, BuildIdentity.MIN_SDK)
    }
}
