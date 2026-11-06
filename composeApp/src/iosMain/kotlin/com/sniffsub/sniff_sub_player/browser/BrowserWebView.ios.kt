@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

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
    private var appliedUserAgent: String? = null
    private var rendererTerminated = false
    private var automaticRecoveryAttempts = 0
    private var automaticRecoveryBlocked = false
    private var viewCreations = 0L
    private var navigationStarts = 0L
    private var userAgentReloads = 0L

    fun prepareForUserNavigation() {
        automaticRecoveryAttempts = 0
        automaticRecoveryBlocked = false
    }

    fun loadUrl(url: String) {
        if (!ready) { pendingUrl = url; return }
        NSURL(string = url)?.let { view?.loadRequest(NSURLRequest(uRL = it)) }
    }
    fun goBack() { view?.takeIf { it.canGoBack }?.goBack() }
    fun goForward() { view?.takeIf { it.canGoForward }?.goForward() }
    fun reload() { if (ready) view?.reload() }
    fun stopLoading() {
        view?.stopLoading()
        session.setLoading(false)
    }

    fun sampleNavigation() {
        if (!ready || rendererTerminated) return
        view?.let { webView ->
            session.updatePage(webView.URL?.absoluteString, webView.title)
            session.setLoading(webView.loading, webView.estimatedProgress)
            session.setHistory(webView.historySnapshot())
        }
    }

    fun update(parent: UIView, state: BrowserUiState, active: Boolean, filterRevision: Long, script: String?) {
        if (automaticRecoveryBlocked) {
            // The failed view must still hide when another tab becomes active.
            view?.hidden = !active
            view?.userInteractionEnabled = false
            delegate?.active = false
            return
        }
        if (view == null || generation != state.webViewGeneration) {
            release()
            generation = state.webViewGeneration
            val initialUserAgent = state.userAgent.ifBlank { null }
            // Record the setting we apply. WebKit may read the default UA back as "" rather
            // than nil, so comparing its getter with null can trigger an endless reload loop.
            appliedUserAgent = initialUserAgent
            rendererTerminated = false
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
                customUserAgent = initialUserAgent
                autoresizingMask = UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
                setFrame(parent.bounds)
            }
            view = created
            viewCreations++
            trace("created")
            parent.addSubview(created)
            rulesRevision = filterRevision
            if (state.address.isNotBlank()) pendingUrl = state.address
            configure(created) {
                if (view === created && !rendererTerminated) {
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
        val userAgentChanged = appliedUserAgent != desiredUserAgent
        if (userAgentChanged) {
            // Commit before causing navigation callbacks or publishing any new session state.
            appliedUserAgent = desiredUserAgent
            webView.customUserAgent = desiredUserAgent
        }
        if (rulesRevision != filterRevision) {
            rulesRevision = filterRevision
            configure(webView) {}
        }
        if (userAgentChanged && ready && webView.URL != null) {
            userAgentReloads++
            trace("UA changed")
            session.prepareReload()
            reload()
            return // The next state invalidates any sniff request from before this reload.
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
            it.navigationDelegate = null
            it.configuration.userContentController.removeScriptMessageHandlerForName(BRIDGE)
            it.stopLoading()
            // Evaluating JS in a terminated view can start another content process during cleanup.
            if (!rendererTerminated && sniffRevision != null) it.evaluateJavaScript(STOP_SCANNER, null)
            it.removeFromSuperview()
        }
        view = null
        delegate = null
        ready = false
        pendingUrl = null
        sniffRevision = null
        rulesRevision = null
    }

    fun handleRendererTermination(webView: WKWebView) {
        if (view !== webView || rendererTerminated) return
        rendererTerminated = true
        ready = false
        pendingUrl = null
        sniffRevision = null
        // One automatic recovery per explicit user navigation. A successful page load does
        // not reset this budget: a page that repeatedly loads then crashes must also stop.
        automaticRecoveryBlocked = automaticRecoveryAttempts >= MAX_AUTOMATIC_RECOVERIES
        if (!automaticRecoveryBlocked) automaticRecoveryAttempts++
        trace("renderer terminated")
        session.handleRendererGone()
        if (automaticRecoveryBlocked) {
            session.setTransientMessage("网页进程连续退出，已停止自动重载，请手动刷新")
        }
    }

    fun recordNavigationStart() {
        navigationStarts++
        if (navigationStarts <= 20 || navigationStarts % 100L == 0L) trace("navigation started")
    }

    private fun trace(event: String) {
        if (kotlin.native.Platform.isDebugBinary) {
            println("SniffSubPlayer WebKit: $event; views=$viewCreations, starts=$navigationStarts, " +
                "UA reloads=$userAgentReloads, recoveries=$automaticRecoveryAttempts, blocked=$automaticRecoveryBlocked")
        }
    }

    private companion object {
        const val MAX_AUTOMATIC_RECOVERIES = 1
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
        controller.recordNavigationStart()
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
        if (!released) controller.handleRendererTermination(webView)
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
