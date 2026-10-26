@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.sniffsub.sniff_sub_player.browser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import com.sniffsub.composeapp.resources.Res
import kotlinx.cinterop.readValue
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.delay
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.darwin.NSObject
import platform.WebKit.*

class IosWebViewController {
    internal var view: WKWebView? = null
    internal var sniffRevision: Long? = null
    internal var rulesRevision: Long? = null
    internal var ready = false
    private var pendingUrl: String? = null

    fun loadUrl(url: String) {
        if (!ready) { pendingUrl = url; return }
        NSURL(string = url)?.let { view?.loadRequest(NSURLRequest(uRL = it)) }
    }
    internal fun configured() {
        ready = true
        pendingUrl?.let { pendingUrl = null; loadUrl(it) }
    }
    fun goBack() { view?.takeIf { it.canGoBack }?.goBack() }
    fun goForward() { view?.takeIf { it.canGoForward }?.goForward() }
    fun reload() { view?.reload() }
}

@Composable
internal fun IosBrowserWebView(
    state: BrowserUiState,
    session: BrowserSession,
    controller: IosWebViewController,
    isActive: Boolean,
    filterRevision: Long,
    configure: (WKWebView, () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val script by produceState<String?>(null) {
        value = Res.readBytes("files/sniffer.js").decodeToString()
    }
    val delegate = remember(session, state.webViewGeneration) { BrowserDelegate(session, controller) }
    delegate.active = isActive

    // Sample native navigation properties only while visible, including history.pushState changes.
    LaunchedEffect(controller, isActive) {
        if (!isActive) {
            session.stopSniff()
            controller.view?.evaluateJavaScript(STOP_SCANNER, null)
            return@LaunchedEffect
        }
        while (isActive) {
            controller.view?.let { view ->
                session.updatePage(view.URL?.absoluteString, view.title)
                session.setLoading(view.loading, view.estimatedProgress)
                session.setHistory(view.historySnapshot())
            }
            delay(100)
        }
    }

    key(state.webViewGeneration) {
        UIKitView(
            modifier = modifier,
            factory = {
                val configuration = WKWebViewConfiguration().apply {
                    websiteDataStore = WKWebsiteDataStore.defaultDataStore()
                    allowsInlineMediaPlayback = true
                    mediaTypesRequiringUserActionForPlayback = WKAudiovisualMediaTypeNone
                    userContentController.addScriptMessageHandler(delegate, name = BRIDGE)
                }
                WKWebView(frame = CGRectZero.readValue(), configuration = configuration).apply {
                    navigationDelegate = delegate
                    allowsBackForwardNavigationGestures = true
                    customUserAgent = state.userAgent.ifBlank { null }
                    controller.view = this
                    controller.ready = false
                    val createdView = this
                    configure(this) { if (controller.view === createdView) controller.configured() }
                    controller.rulesRevision = filterRevision
                    if (state.address.isNotBlank()) controller.loadUrl(state.address)
                }
            },
            update = { view ->
                view.hidden = !isActive
                view.userInteractionEnabled = isActive
                view.customUserAgent = state.userAgent.ifBlank { null }
                if (controller.rulesRevision != filterRevision) {
                    configure(view) {}
                    controller.rulesRevision = filterRevision
                }
                val requested = state.address.trim()
                if (requested.isNotBlank() && requested != view.URL?.absoluteString &&
                    (view.URL == null || requested != state.currentPageUrl)) {
                    controller.loadUrl(requested)
                }
                if (!isActive || !state.snifferEnabled) {
                    if (controller.sniffRevision != null) {
                        view.evaluateJavaScript(STOP_SCANNER, null)
                        controller.sniffRevision = null
                    }
                } else if (script != null && controller.sniffRevision != state.sniffRequestRevision) {
                    controller.sniffRevision = state.sniffRequestRevision
                    view.evaluateJavaScript(
                        "window.SniffBridge = { report: function(json) { window.webkit.messageHandlers.$BRIDGE.postMessage(json); } };\n" +
                            "window.__sniffSubPlayerRequest = ${state.sniffRequestRevision};\n$script",
                    ) { _, error -> if (error != null) session.setTransientMessage("嗅探脚本执行失败") }
                }
            },
            onRelease = { view ->
                delegate.released = true
                view.stopLoading()
                view.evaluateJavaScript(STOP_SCANNER, null)
                view.navigationDelegate = null
                view.configuration.userContentController.removeScriptMessageHandlerForName(BRIDGE)
                if (controller.view === view) {
                    controller.view = null
                    controller.ready = false
                    controller.sniffRevision = null
                }
            },
            properties = UIKitInteropProperties(
                interactionMode = UIKitInteropInteractionMode.NonCooperative,
                isNativeAccessibilityEnabled = true,
            ),
        )
    }
}

private class BrowserDelegate(
    private val session: BrowserSession,
    private val controller: IosWebViewController,
) : NSObject(), WKNavigationDelegateProtocol, WKScriptMessageHandlerProtocol {
    var active = false
    var released = false

    override fun userContentController(userContentController: WKUserContentController, didReceiveScriptMessage: WKScriptMessage) {
        if (!active || released || !didReceiveScriptMessage.frameInfo.mainFrame) return
        val json = didReceiveScriptMessage.body as? String ?: return
        // Invalidate stale SPA callbacks before applying the scan/page token checks.
        session.updatePage(controller.view?.URL?.absoluteString)
        session.acceptSnifferPayload(json)
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didStartProvisionalNavigation: WKNavigation?) {
        if (released) return
        controller.sniffRevision = null
        session.updatePage(webView.URL?.absoluteString, started = true)
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
        if (released) return
        session.updatePage(webView.URL?.absoluteString, webView.title)
        session.setLoading(false, 1.0)
        session.setHistory(webView.historySnapshot())
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFailProvisionalNavigation: WKNavigation?, withError: NSError) {
        if (released) return
        session.setLoading(false, 0.0)
        session.setTransientMessage(withError.localizedDescription)
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFailNavigation: WKNavigation?, withError: NSError) {
        if (released) return
        session.setLoading(false, 0.0)
        session.setTransientMessage(withError.localizedDescription)
    }

    override fun webViewWebContentProcessDidTerminate(webView: WKWebView) {
        if (!released) session.handleRendererGone()
    }
}

private fun WKWebView.historySnapshot(): BrowserHistory {
    val list = backForwardList
    val current = list.currentItem ?: return BrowserHistory()
    val back = list.backList.filterIsInstance<WKBackForwardListItem>()
    val forward = list.forwardList.filterIsInstance<WKBackForwardListItem>()
    return BrowserHistory(
        (back + current + forward).map { BrowserHistoryEntry(it.URL.absoluteString.orEmpty(), it.title.orEmpty()) },
        back.size,
    )
}

private const val BRIDGE = "sniffer"
private const val STOP_SCANNER =
    "if (window.__sniffSubPlayerSetActive) window.__sniffSubPlayerSetActive(false);"
