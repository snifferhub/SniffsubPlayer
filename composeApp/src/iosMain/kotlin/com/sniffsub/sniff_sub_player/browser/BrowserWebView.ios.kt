@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.sniffsub.sniff_sub_player.browser

import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.readValue
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.UIKit.UIView
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth
import platform.darwin.NSObject
import platform.WebKit.*

/** A retained WKWebView per shared session, independent of the SwiftUI view lifecycle. */
internal class IosWebViewController(
    private val session: BrowserSession,
    private val configure: (WKWebView, () -> Unit) -> Unit,
) {
    internal var view: WKWebView? = null
    internal var sniffRevision: Long? = null
    private var rulesRevision: Long? = null
    private var generation: Int? = null
    private var ready = false
    private var pendingUrl: String? = null
    private var delegate: BrowserDelegate? = null

    fun loadUrl(url: String) {
        if (!ready) { pendingUrl = url; return }
        NSURL(string = url)?.let { view?.loadRequest(NSURLRequest(uRL = it)) }
    }
    fun goBack() { view?.takeIf { it.canGoBack }?.goBack() }
    fun goForward() { view?.takeIf { it.canGoForward }?.goForward() }
    fun reload() { view?.reload() }
    fun stopLoading() {
        view?.stopLoading()
        session.setLoading(false)
    }

    fun sampleNavigation() {
        view?.let { webView ->
            session.updatePage(webView.URL?.absoluteString, webView.title)
            session.setLoading(webView.loading, webView.estimatedProgress)
            session.setHistory(webView.historySnapshot())
        }
    }

    fun update(parent: UIView, state: BrowserUiState, active: Boolean, filterRevision: Long, script: String?) {
        if (view == null || generation != state.webViewGeneration) {
            release()
            generation = state.webViewGeneration
            val navigation = BrowserDelegate(session, this).also { delegate = it }
            val configuration = WKWebViewConfiguration().apply {
                websiteDataStore = WKWebsiteDataStore.defaultDataStore()
                allowsInlineMediaPlayback = true
                mediaTypesRequiringUserActionForPlayback = WKAudiovisualMediaTypeNone
                userContentController.addScriptMessageHandler(navigation, name = BRIDGE)
            }
            val created = WKWebView(frame = CGRectZero.readValue(), configuration = configuration).apply {
                navigationDelegate = navigation
                allowsBackForwardNavigationGestures = true
                customUserAgent = state.userAgent.ifBlank { null }
                autoresizingMask = UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
                setFrame(parent.bounds)
            }
            view = created
            parent.addSubview(created)
            rulesRevision = filterRevision
            if (state.address.isNotBlank()) pendingUrl = state.address
            configure(created) {
                if (view === created) {
                    ready = true
                    pendingUrl?.let { pendingUrl = null; loadUrl(it) }
                }
            }
        }
        val webView = view ?: return
        delegate?.active = active
        webView.hidden = !active
        webView.userInteractionEnabled = active
        val desiredUserAgent = state.userAgent.ifBlank { null }
        val userAgentChanged = webView.customUserAgent != desiredUserAgent
        webView.customUserAgent = desiredUserAgent
        if (rulesRevision != filterRevision) {
            rulesRevision = filterRevision
            configure(webView) {}
        }
        if (userAgentChanged && ready && webView.URL != null) {
            session.prepareReload()
            reload()
        }
        if (!active || !state.snifferEnabled) {
            if (sniffRevision != null) {
                webView.evaluateJavaScript(STOP_SCANNER, null)
                sniffRevision = null
            }
        } else if (script != null && sniffRevision != state.sniffRequestRevision) {
            sniffRevision = state.sniffRequestRevision
            webView.evaluateJavaScript(
                "window.SniffBridge = { report: function(json) { window.webkit.messageHandlers.$BRIDGE.postMessage(json); } };\n" +
                    "window.__sniffSubPlayerRequest = ${state.sniffRequestRevision};\n$script",
            ) { _, error -> if (error != null) session.setTransientMessage("嗅探脚本执行失败") }
        }
    }

    fun release() {
        delegate?.released = true
        view?.let {
            it.stopLoading()
            it.evaluateJavaScript(STOP_SCANNER, null)
            it.navigationDelegate = null
            it.configuration.userContentController.removeScriptMessageHandlerForName(BRIDGE)
            it.removeFromSuperview()
        }
        view = null
        delegate = null
        ready = false
        pendingUrl = null
        sniffRevision = null
        rulesRevision = null
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
        if (released || withError.code == -999L) return // WebKit cancels superseded navigations normally.
        session.setLoading(false, 0.0)
        session.setTransientMessage(withError.localizedDescription)
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFailNavigation: WKNavigation?, withError: NSError) {
        if (released || withError.code == -999L) return
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
