package com.sniffsub.sniff_sub_player.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sniffsub.sniff_sub_player.adblock.AdBlockRepository
import com.sniffsub.sniff_sub_player.adblock.AdRequest
import com.sniffsub.sniff_sub_player.adblock.ResourceType
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
        } else {
            view.clearFocus()
            view.onPause()
        }
        // onPause does not stop JS. Suspend only our scanner, not every WebView's timers.
        view.evaluateJavascript("if (window.__sniffSubPlayerSetActive) window.__sniffSubPlayerSetActive($active);", null)
    }

    internal fun applySnifferScript(view: WebView, revision: Long, script: String) {
        if (sniffRequests[view] == revision) return
        sniffRequests[view] = revision
        view.evaluateJavascript(script, null)
        view.evaluateJavascript("if (window.__sniffSubPlayerSetActive) window.__sniffSubPlayerSetActive($active);", null)
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

    internal fun attach(view: WebView) {
        webViewSlot.attach(view)
        applyActiveState(view)
    }

    internal fun release(view: WebView) {
        webViewSlot.release(view, AndroidBrowserWebViewReleaseOperations)
    }
}

internal interface BrowserWebViewReleaseOperations<T : Any> {
    fun stopLoading(target: T)
    fun removeJavascriptBridge(target: T)
    fun clearClients(target: T)
    fun destroy(target: T)
}

internal class BrowserWebViewReleaseCoordinator<T : Any> {
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
        target.destroy()
    }
}

@Composable
fun rememberBrowserWebViewController(): BrowserWebViewController = remember {
    BrowserWebViewController()
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserWebView(
    state: BrowserUiState,
    viewModel: BrowserViewModel,
    adBlockRepository: AdBlockRepository,
    modifier: Modifier = Modifier,
    controller: BrowserWebViewController = rememberBrowserWebViewController(),
    isActive: Boolean = true,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(controller, lifecycle, isActive) {
        val observer = LifecycleEventObserver { _, _ ->
            controller.setActive(isActive && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
        lifecycle.addObserver(observer)
        controller.setActive(isActive && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose {
            lifecycle.removeObserver(observer)
            controller.setActive(false)
        }
    }
    val adBlockState by adBlockRepository.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val defaultUserAgent = remember(context) { WebSettings.getDefaultUserAgent(context) }
    val snifferScript = remember(context) {
        runCatching {
            context.assets.open(SNIFFER_ASSET_NAME).bufferedReader().use { it.readText() }
        }.getOrNull()
    }

    val cosmeticScript = remember(adBlockState.rulesRevision, state.currentPageUrl) {
        cosmeticFilteringScript(adBlockRepository.selectors(state.currentPageUrl))
    }

    key(state.webViewGeneration) {
        AndroidView(
            modifier = modifier,
            factory = { factoryContext ->
                createBrowserWebView(
                    context = factoryContext,
                    state = state,
                    viewModel = viewModel,
                    adBlockRepository = adBlockRepository,
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
                    if (snifferScript == null) {
                        viewModel.setTransientMessage("嗅探脚本读取失败")
                    } else {
                        controller.applySnifferScript(webView, state.sniffRequestRevision, snifferScript)
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

internal fun shouldBlockWebRequest(
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
    viewModel: BrowserViewModel,
    adBlockRepository: AdBlockRepository,
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
            pageUrl.set(url)
            viewModel.updatePage(url = url, started = true)
            viewModel.setNavigation(view.canGoBack(), view.canGoForward())
        }

        override fun onPageFinished(view: WebView, url: String) {
            view.evaluateJavascript(cosmeticFilteringScript(adBlockRepository.selectors(url)), null)
            viewModel.updatePage(url = url, pageTitle = view.title)
            viewModel.setLoading(loading = false, progress = 1.0)
            viewModel.setNavigation(view.canGoBack(), view.canGoForward())
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            pageUrl.set(url)
            view.evaluateJavascript(cosmeticFilteringScript(adBlockRepository.selectors(url)), null)
            viewModel.updatePage(url = url)
            viewModel.setNavigation(view.canGoBack(), view.canGoForward())
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            if (request.isForMainFrame) {
                viewModel.setLoading(loading = false, progress = 0.0)
                viewModel.setTransientMessage("加载失败: ${error.description}")
            }
        }

        override fun onRenderProcessGone(
            view: WebView,
            detail: RenderProcessGoneDetail,
        ): Boolean {
            releaseBrowserWebView(view, controller)
            viewModel.handleRendererGone()
            return true
        }

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? {
            val url = request.url.toString()
            if (!shouldBlockWebRequest(url) {
                    adBlockRepository.blocks(AdRequest(it, pageUrl.get(), classifyWebResource(request)))
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
            viewModel.updatePage(url = view.url, pageTitle = title.orEmpty())
        }

        override fun onProgressChanged(view: WebView, newProgress: Int) {
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

private const val SNIFFER_ASSET_NAME = "sniffer.js"
private const val SNIFFER_BRIDGE_NAME = "SniffBridge"
private const val MAXIMUM_BRIDGE_MESSAGE_LENGTH = 20_000

internal fun classifyWebResource(request: WebResourceRequest): ResourceType = classifyWebResource(
    request.url.toString(), request.isForMainFrame, request.requestHeaders,
)

internal fun classifyWebResource(url: String, isMainFrame: Boolean, headers: Map<String, String>): ResourceType {
    if (isMainFrame) return ResourceType.DOCUMENT
    fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value.orEmpty().lowercase()
    when (header("Sec-Fetch-Dest")) {
        "script", "worker", "sharedworker", "serviceworker" -> return ResourceType.SCRIPT
        "image" -> return ResourceType.IMAGE
        "style" -> return ResourceType.STYLESHEET
        "font" -> return ResourceType.FONT
        "audio", "video", "track" -> return ResourceType.MEDIA
        "iframe", "frame", "document" -> return ResourceType.SUBDOCUMENT
    }
    if (header("X-Requested-With") == "xmlhttprequest") return ResourceType.XMLHTTPREQUEST
    val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault("")
    val extension = path.substringAfterLast('.', "")
    return when {
        extension in setOf("js", "mjs") -> ResourceType.SCRIPT
        extension == "css" -> ResourceType.STYLESHEET
        extension in setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "avif") || header("Accept").startsWith("image/") -> ResourceType.IMAGE
        extension in setOf("woff", "woff2", "ttf", "otf") -> ResourceType.FONT
        extension in setOf("mp4", "m3u8", "mpd", "webm", "ts", "m4s", "mp3", "aac", "ogg") -> ResourceType.MEDIA
        header("Accept").contains("text/html") -> ResourceType.SUBDOCUMENT
        else -> ResourceType.OTHER
    }
}
