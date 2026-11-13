package com.sniffsub.sniff_sub_player.browser

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SnifferAssetContractTest {
    @Test
    fun sniffer_asset_keeps_all_detection_sources_and_bridge_fields() {
        val asset = File("../composeApp/src/commonMain/composeResources/files/sniffer.js")
        assertTrue("sniffer.js must be packaged as a shared Compose resource", asset.isFile)
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
            "setTimeout(tick, 500)",
        ).forEach { requiredText ->
            assertTrue("sniffer.js must contain $requiredText", script.contains(requiredText))
        }
    }

    @Test
    fun sniffer_executes_fetch_and_xhr_wrappers_for_strings_requests_and_url_objects() {
        val process = ProcessBuilder("node", "--test", "src/test/js/sniffer.test.cjs")
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            fail("Node sniffer behavior harness timed out")
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(output, 0, process.exitValue())
    }
}
