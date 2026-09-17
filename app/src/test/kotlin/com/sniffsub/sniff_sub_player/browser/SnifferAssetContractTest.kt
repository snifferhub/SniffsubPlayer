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

    @Test
    fun sniffer_executes_fetch_and_xhr_wrappers_for_strings_requests_and_url_objects() {
        val asset = File("src/main/assets/sniffer.js").absoluteFile
        val harness = """
            const assert = require('assert');
            const fs = require('fs');
            const vm = require('vm');

            const script = fs.readFileSync(process.argv[1], 'utf8');
            const reports = [];
            let fetchCall = 0;

            class FakeRequest {
              constructor(input) {
                this.url = new URL(input, 'https://page.test/watch').href;
              }
            }

            class FakeXMLHttpRequest {
              open(method, url) {
                this.lastRequest = { method, url };
                return true;
              }
            }

            const sandbox = {
              URL,
              Request: FakeRequest,
              XMLHttpRequest: FakeXMLHttpRequest,
              location: { href: 'https://page.test/watch' },
              navigator: { userAgent: 'Test UA' },
              document: {
                querySelectorAll: () => [],
                addEventListener: () => {},
              },
              performance: { getEntriesByType: () => [] },
              setInterval: () => 1,
              SniffBridge: {
                report: (json) => reports.push(JSON.parse(json)),
              },
              fetch: () => Promise.resolve({
                url: fetchCall++ === 0
                  ? 'https://cdn.test/fetch-response.mpd'
                  : 'https://example.test/not-media',
              }),
            };
            sandbox.window = sandbox;
            vm.createContext(sandbox);
            vm.runInContext(script, sandbox);

            (async () => {
              await sandbox.fetch('fetch-string.m3u8');
              await sandbox.fetch(new URL('https://cdn.test/fetch-url.mp4'));
              await sandbox.fetch(new FakeRequest('https://cdn.test/fetch-request.mov'));

              const xhr = new sandbox.XMLHttpRequest();
              xhr.open('GET', 'xhr-string.aac');
              xhr.open('GET', new URL('https://cdn.test/xhr-url.m4s'));

              assert.deepStrictEqual(
                reports.map(({ url, source }) => ({ url, source })),
                [
                  { url: 'https://page.test/fetch-string.m3u8', source: 'fetch' },
                  { url: 'https://cdn.test/fetch-response.mpd', source: 'fetch-response' },
                  { url: 'https://cdn.test/fetch-url.mp4', source: 'fetch' },
                  { url: 'https://cdn.test/fetch-request.mov', source: 'fetch' },
                  { url: 'https://page.test/xhr-string.aac', source: 'xhr' },
                  { url: 'https://cdn.test/xhr-url.m4s', source: 'xhr' },
                ],
              );
            })().catch((error) => {
              console.error(error.stack || error);
              process.exit(1);
            });
        """.trimIndent()

        val process = ProcessBuilder("node", "-e", harness, asset.path)
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
