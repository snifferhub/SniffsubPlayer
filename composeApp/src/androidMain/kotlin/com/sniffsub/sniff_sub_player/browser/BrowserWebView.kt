package com.sniffsub.sniff_sub_player.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sniffsub.sniff_sub_player.adblock.cosmeticFilteringScript
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.Collections
import java.util.WeakHashMap

class BrowserWebViewController internal constructor() {
    private val webViewSlot = BrowserWebViewReleaseCoordinator<WebView>()
    private val sniffRequests = WeakHashMap<WebView, Long>()
    private val activeViews = WeakHashMap<WebView, Boolean>()
    private var active = false
    private var snifferEnabled = false
    private var visualRequest = 0L

    internal fun setSnifferEnabled(value: Boolean) {
        if (snifferEnabled == value) return
        snifferEnabled = value
        if (!value) webViewSlot.withAttached { view ->
            view.evaluateJavascript("if (window.__sniffSubPlayerSetActive) window.__sniffSubPlayerSetActive(false);", null)
        }
    }

    internal fun setActive(value: Boolean) {
        active = value
        webViewSlot.withAttached(::applyActiveState)
    }

    private fun applyActiveState(view: WebView) {
        if (activeViews[view] == active) return
        activeViews[view] = active
        view.visibility = if (active) View.VISIBLE else View.INVISIBLE
        if (active) {
            view.onResume()
            view.requestLayout()
            view.invalidate()
            // Ask Chromium for a fresh drawable frame after a hidden video/browser surface.
            view.postVisualStateCallback(++visualRequest, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    webViewSlot.withAttached { current ->
                        if (current === view && active) current.postInvalidateOnAnimation()
                    }
                }
            })
        } else {
            view.clearFocus()
            view.onPause()
        }
        // onPause does not stop JS. Suspend only our scanner, not every WebView's timers.
        view.evaluateJavascript("if (window.__sniffSubPlayerSetActive) window.__sniffSubPlayerSetActive(${active && snifferEnabled});", null)
        Log.d("BrowserWebView", "active=$active generationView=${System.identityHashCode(view)}")
    }

    internal fun applySnifferScript(view: WebView, revision: Long, script: String) {
        if (sniffRequests[view] == revision) return
        sniffRequests[view] = revision
        view.evaluateJavascript("window.__sniffSubPlayerRequest = $revision;\n$script", null)
    }

    private val cosmeticScripts = WeakHashMap<WebView, String>()

    internal fun applyCosmeticScript(view: WebView, script: String) {
        if (cosmeticScripts[view] == script) return
        cosmeticScripts[view] = script
        view.evaluateJavascript(script, null)
    }

    fun loadUrl(url: String) {
        webViewSlot.withAttached { it.loadUrl(url) }
    }

    fun goBack() {
        webViewSlot.withAttached { webView -> webView.takeIf(WebView::canGoBack)?.goBack() }
    }

    fun goForward() {
        webViewSlot.withAttached { webView -> webView.takeIf(WebView::canGoForward)?.goForward() }
    }

    fun reload() {
        webViewSlot.withAttached(WebView::reload)
    }

    internal fun isAttached(view: WebView): Boolean = webViewSlot.withAttached { it === view } == true

    internal fun attach(view: WebView) {
        webViewSlot.attach(view)
        applyActiveState(view)
    }

    internal fun release(view: WebView, rendererGone: Boolean = false) {
        activeViews.remove(view)
        sniffRequests.remove(view)
        cosmeticScripts.remove(view)
        webViewSlot.release(view, if (rendererGone) DeadBrowserWebViewReleaseOperations else AndroidBrowserWebViewReleaseOperations)
    }
}

interface BrowserWebViewReleaseOperations<T : Any> {
    fun stopLoading(target: T)
    fun removeJavascriptBridge(target: T)
    fun clearClients(target: T)
    fun destroy(target: T)
}

class BrowserWebViewReleaseCoordinator<T : Any> {
    private var attached: T? = null
    private val released = Collections.newSetFromMap(WeakHashMap<T, Boolean>())

    fun attach(target: T) {
        attached = target
    }

    fun <R> withAttached(block: (T) -> R): R? = attached?.let(block)

    fun release(target: T, operations: BrowserWebViewReleaseOperations<T>) {
        if (!released.add(target)) return
        operations.stopLoading(target)
        operations.removeJavascriptBridge(target)
        operations.clearClients(target)
        if (attached === target) attached = null
        operations.destroy(target)
    }
}

private object AndroidBrowserWebViewReleaseOperations : BrowserWebViewReleaseOperations<WebView> {
    override fun stopLoading(target: WebView) {
        target.stopLoading()
    }

    override fun removeJavascriptBridge(target: WebView) {
        target.removeJavascriptInterface(SNIFFER_BRIDGE_NAME)
    }

    override fun clearClients(target: WebView) {
        target.webViewClient = WebViewClient()
        target.webChromeClient = null
    }

    override fun destroy(target: WebView) {
        (target.parent as? ViewGroup)?.removeView(target)
        target.destroy()
    }
}

// A dead renderer must not receive loading/JavaScript/client operations during cleanup.
private object DeadBrowserWebViewReleaseOperations : BrowserWebViewReleaseOperations<WebView> {
    override fun stopLoading(target: WebView) = Unit
    override fun removeJavascriptBridge(target: WebView) = Unit
    override fun clearClients(target: WebView) = Unit
    override fun destroy(target: WebView) = AndroidBrowserWebViewReleaseOperations.destroy(target)
}

@Composable
fun rememberBrowserWebViewController(): BrowserWebViewController = remember {
    BrowserWebViewController()
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserWebView(
    state: BrowserUiState,
    viewModel: BrowserSession,
    contentFilter: AndroidWebContentFilter,
    modifier: Modifier = Modifier,
    controller: BrowserWebViewController = rememberBrowserWebViewController(),
    isActive: Boolean = true,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(controller, lifecycle, isActive) {
        val observer = LifecycleEventObserver { _, _ ->
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) viewModel.stopSniff()
            controller.setActive(isActive && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
        lifecycle.addObserver(observer)
        controller.setActive(isActive && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose {
            lifecycle.removeObserver(observer)
            controller.setActive(false)
        }
    }
    SideEffect {
        controller.setSnifferEnabled(state.snifferEnabled)
    }
    val rulesRevision by contentFilter.rulesRevision.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val defaultUserAgent = remember(context) { WebSettings.getDefaultUserAgent(context) }
    val snifferScript by produceState<String?>(null, context) {
        value = withContext(Dispatchers.IO) {
            context.assets.open("composeResources/com.sniffsub.composeapp.resources/files/sniffer.js")
                .bufferedReader().use { it.readText() }
        }
    }

    val cosmeticScript = remember(rulesRevision, state.currentPageUrl) {
        cosmeticFilteringScript(contentFilter.selectors(state.currentPageUrl))
    }

    key(state.webViewGeneration) {
        AndroidView(
            modifier = modifier,
            factory = { factoryContext ->
                createBrowserWebView(
                    context = factoryContext,
                    state = state,
                    viewModel = viewModel,
                    contentFilter = contentFilter,
                    defaultUserAgent = defaultUserAgent,
                    controller = controller,
                ).also { webView ->
                    controller.attach(webView)
                    if (state.address.isNotBlank()) webView.loadUrl(state.address)
                }
            },
            update = { webView ->
                controller.attach(webView)
                applyUserAgent(webView, state.userAgent, defaultUserAgent)
                val requestedAddress = state.address.trim()
                if (
                    requestedAddress.isNotEmpty() &&
                    requestedAddress != webView.url &&
                    (webView.url.isNullOrEmpty() || requestedAddress != state.currentPageUrl)
                ) {
                    webView.loadUrl(requestedAddress)
                }
                // Observe the repository so toggles/updates also remove or replace existing CSS.
                controller.applyCosmeticScript(webView, cosmeticScript)
                if (isActive && state.snifferEnabled) {
                    snifferScript?.let { script ->
                        controller.applySnifferScript(webView, state.sniffRequestRevision, script)
                    }
                }
            },
            onReset = null,
            onRelease = { webView -> releaseBrowserWebView(webView, controller) },
        )
    }
}

internal fun releaseBrowserWebView(
    webView: WebView,
    controller: BrowserWebViewController,
) {
    controller.release(webView)
}

fun shouldBlockWebRequest(
    url: String,
    blocks: (String) -> Boolean,
): Boolean {
    val scheme = runCatching { URI(url).scheme?.lowercase() }.getOrNull()
    return (scheme == "http" || scheme == "https") && blocks(url)
}

@SuppressLint("SetJavaScriptEnabled")
private fun createBrowserWebView(
    context: Context,
    state: BrowserUiState,
    viewModel: BrowserSession,
    contentFilter: AndroidWebContentFilter,
    defaultUserAgent: String,
    controller: BrowserWebViewController,
): WebView = WebView(context).apply {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.mediaPlaybackRequiresUserGesture = false
    applyUserAgent(this, state.userAgent, defaultUserAgent)

    addJavascriptInterface(
        SniffJavascriptBridge(this, viewModel::acceptSnifferPayload),
        SNIFFER_BRIDGE_NAME,
    )

    val pageUrl = java.util.concurrent.atomic.AtomicReference(state.currentPageUrl)

    webViewClient = object : WebViewClient() {
        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            if (!controller.isAttached(view)) return
            controller.setSnifferEnabled(false)
            pageUrl.set(url)
            viewModel.updatePage(url = url, started = true)
            viewModel.setHistory(view.historySnapshot())
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (!controller.isAttached(view) || url != view.url) return
            view.evaluateJavascript(cosmeticFilteringScript(contentFilter.selectors(url)), null)
            viewModel.updatePage(url = url, pageTitle = view.title)
            viewModel.setLoading(loading = false, progress = 1.0)
            viewModel.setHistory(view.historySnapshot())
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            if (!controller.isAttached(view)) return
            pageUrl.set(url)
            view.evaluateJavascript(cosmeticFilteringScript(contentFilter.selectors(url)), null)
            viewModel.updatePage(url = url)
            viewModel.setHistory(view.historySnapshot())
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            if (controller.isAttached(view) && request.isForMainFrame) {
                viewModel.setLoading(loading = false, progress = 0.0)
                viewModel.setTransientMessage("加载失败: ${error.description}")
            }
        }

        override fun onRenderProcessGone(
            view: WebView,
            detail: RenderProcessGoneDetail,
        ): Boolean {
            Log.w("BrowserWebView", "rendererGone crashed=${detail.didCrash()}")
            val wasAttached = controller.isAttached(view)
            controller.release(view, rendererGone = true)
            if (wasAttached) viewModel.handleRendererGone()
            return true
        }

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? {
            val url = request.url.toString()
            if (!shouldBlockWebRequest(url) {
                    contentFilter.blocks(request, pageUrl.get())
                }) return null
            return WebResourceResponse(
                "text/plain",
                Charsets.UTF_8.name(),
                ByteArrayInputStream(ByteArray(0)),
            )
        }
    }

    webChromeClient = object : WebChromeClient() {
        override fun onReceivedTitle(view: WebView, title: String?) {
            if (!controller.isAttached(view)) return
            viewModel.updatePage(url = view.url, pageTitle = title.orEmpty())
        }

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            if (!controller.isAttached(view)) return
            viewModel.setProgress(newProgress / 100.0)
        }
    }
}

private fun applyUserAgent(webView: WebView, selectedUserAgent: String, defaultUserAgent: String) {
    val desired = selectedUserAgent.ifBlank { defaultUserAgent }
    if (webView.settings.userAgentString != desired) {
        webView.settings.userAgentString = desired
    }
}

private class SniffJavascriptBridge(
    private val webView: WebView,
    private val reportPayload: (String) -> Boolean,
) {
    @JavascriptInterface
    fun report(json: String) {
        if (json.length > MAXIMUM_BRIDGE_MESSAGE_LENGTH) return
        webView.post {
            if (webView.visibility == View.VISIBLE) reportPayload(json)
        }
    }
}

private const val SNIFFER_BRIDGE_NAME = "SniffBridge"
private const val MAXIMUM_BRIDGE_MESSAGE_LENGTH = 20_000


interface AndroidWebContentFilter {
    val rulesRevision: StateFlow<Long>
    fun selectors(pageUrl: String): List<String>
    fun blocks(request: WebResourceRequest, pageUrl: String): Boolean
}

private fun WebView.historySnapshot(): BrowserHistory {
    val list = copyBackForwardList()
    if (list.size == 0) return BrowserHistory()
    return BrowserHistory(
        List(list.size) { index ->
            val entry = list.getItemAtIndex(index)
            BrowserHistoryEntry(entry.url.orEmpty(), entry.title.orEmpty())
        },
        list.currentIndex,
    )
}
