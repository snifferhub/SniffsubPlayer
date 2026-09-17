package com.sniffsub.sniff_sub_player.browser

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SnifferAssetContractTest {
    @Test
    fun sniffer_asset_keeps_all_detection_sources_and_bridge_fields() {
        val asset = File("src/main/assets/sniffer.js")
        assertTrue("sniffer.js must be packaged as an Android asset", asset.isFile)
        val script = asset.readText()

        listOf(
            "window.__sniffSubPlayerInstalled",
            "new Set()",
            "new URL(url, location.href)",
            "window.fetch",
            "fetch-response",
            "XMLHttpRequest.prototype.open",
            "performance.getEntriesByType('resource')",
            "document.querySelectorAll('video, source, a')",
            "'loadstart'",
            "'loadedmetadata'",
            "'play'",
            "'canplay'",
            "window.SniffBridge.report",
            "navigator.userAgent",
            "setInterval(scan, 1200)",
        ).forEach { requiredText ->
            assertTrue("sniffer.js must contain $requiredText", script.contains(requiredText))
        }
    }
}
