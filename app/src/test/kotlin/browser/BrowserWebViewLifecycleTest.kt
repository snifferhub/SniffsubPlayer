package com.sniffsub.sniff_sub_player.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class BrowserWebViewLifecycleTest {
    @Test
    fun release_stops_clears_detaches_then_destroys_once() {
        val releasedTarget = Any()
        val replacementTarget = Any()
        val coordinator = BrowserWebViewReleaseCoordinator<Any>()
        val events = mutableListOf<String>()
        val operations = object : BrowserWebViewReleaseOperations<Any> {
            override fun stopLoading(target: Any) {
                events += "stopLoading"
            }

            override fun removeJavascriptBridge(target: Any) {
                events += "removeJavascriptBridge"
            }

            override fun clearClients(target: Any) {
                events += "clearClients"
            }

            override fun destroy(target: Any) {
                coordinator.withAttached { events += "staleController" }
                events += "destroy"
            }
        }
        coordinator.attach(releasedTarget)

        coordinator.release(releasedTarget, operations)
        coordinator.release(releasedTarget, operations)

        assertEquals(
            listOf("stopLoading", "removeJavascriptBridge", "clearClients", "destroy"),
            events,
        )
        assertNull(coordinator.withAttached { it })

        coordinator.attach(replacementTarget)
        coordinator.release(releasedTarget, operations)
        assertSame(replacementTarget, coordinator.withAttached { it })
    }
}
