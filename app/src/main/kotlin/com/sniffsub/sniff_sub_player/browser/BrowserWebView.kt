package com.sniffsub.sniff_sub_player.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
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
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.sniffsub.sniff_sub_player.adblock.AdBlockRepository
import java.io.ByteArrayInputStream
import java.net.URI

class BrowserWebViewController internal constructor() {
    private var webView: WebView? = null

    fun loadUrl(url: String) {
        webView?.loadUrl(url)
    }

    fun goBack() {
        webView?.takeIf(WebView::canGoBack)?.goBack()
    }

    fun goForward() {
        webView?.takeIf(WebView::canGoForward)?.goForward()
    }

    fun reload() {
        webView?.reload()
    }

    internal fun attach(view: WebView) {
        webView = view
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
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val defaultUserAgent = remember(context) { WebSettings.getDefaultUserAgent(context) }
    val snifferScript = remember(context) {
        runCatching {
            context.assets.open(SNIFFER_ASSET_NAME).bufferedReader().use { it.readText() }
        }.getOrNull()
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
                if (state.snifferEnabled) {
                    if (snifferScript == null) {
                        viewModel.setTransientMessage("嗅探脚本读取失败")
                    } else {
                        webView.evaluateJavascript(snifferScript, null)
                    }
                }
            },
        )
    }
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

    webViewClient = object : WebViewClient() {
        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            viewModel.updatePage(url = url, started = true)
            viewModel.setNavigation(view.canGoBack(), view.canGoForward())
        }

        override fun onPageFinished(view: WebView, url: String) {
            viewModel.updatePage(url = url, pageTitle = view.title)
            viewModel.setLoading(loading = false, progress = 1.0)
            viewModel.setNavigation(view.canGoBack(), view.canGoForward())
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
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
            view.destroy()
            viewModel.handleRendererGone()
            return true
        }

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? {
            val url = request.url.toString()
            if (!shouldBlockWebRequest(url, adBlockRepository::blocks)) return null
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
        webView.post { reportPayload(json) }
    }
}

private const val SNIFFER_ASSET_NAME = "sniffer.js"
private const val SNIFFER_BRIDGE_NAME = "SniffBridge"
private const val MAXIMUM_BRIDGE_MESSAGE_LENGTH = 20_000
