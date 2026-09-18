package com.sniffsub.sniff_sub_player.subtitle

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val SUBTITLE_DOCUMENT_MIME_TYPES = listOf(
    "application/x-subrip",
    "text/vtt",
    "text/plain",
)

fun buildSubtitlecatUrl(query: String): String {
    val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name()).replace("+", "%20")
    return "https://subtitlecat.com/index.php?search=$encoded"
}

fun decodeSubtitleBytes(bytes: ByteArray): String = bytes.toString(Charsets.UTF_8)

fun isSafeSubtitlecatNavigation(url: String): Boolean {
    val uri = try {
        URI(url)
    } catch (_: Exception) {
        return false
    }
    val host = uri.host?.lowercase() ?: return false
    val trustedHost = host == SUBTITLECAT_HOST || host.endsWith(".$SUBTITLECAT_HOST")
    return uri.scheme.equals("https", ignoreCase = true) && trustedHost
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtitleSearchScreen(
    initialQuery: String,
    repository: SubtitleRepository,
    onClose: () -> Unit,
    onSubtitleSelected: (text: String, label: String) -> Unit,
    modifier: Modifier = Modifier,
    initialPasteText: String = "",
) {
    var query by remember(initialQuery) { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf<List<OnlineSubtitle>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var downloadingUrl by remember { mutableStateOf<String?>(null) }
    var showPasteEditor by remember { mutableStateOf(false) }
    var showSubtitlecat by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    BackHandler(enabled = showPasteEditor || showSubtitlecat) {
        when {
            showPasteEditor -> showPasteEditor = false
            showSubtitlecat -> showSubtitlecat = false
        }
    }

    val documentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            captureSubtitleImport { readSubtitleDocument(context, uri) }
                .onSuccess { text -> onSubtitleSelected(text, uri.lastPathSegment ?: "字幕文件") }
                .onFailure { error -> status = "字幕导入失败: ${error.message ?: "无法读取文件"}" }
        }
    }

    if (showPasteEditor) {
        PasteSubtitleScreen(
            initialText = initialPasteText,
            onCancel = { showPasteEditor = false },
            onApply = { text -> onSubtitleSelected(text, "文本") },
            modifier = modifier,
        )
        return
    }
    if (showSubtitlecat) {
        SubtitlecatScreen(
            query = query,
            onBack = { showSubtitlecat = false },
            modifier = modifier,
        )
        return
    }

    fun search() {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) {
            status = "请输入番号或关键词后再搜索"
            return
        }
        searching = true
        status = "正在搜索字幕: $normalizedQuery"
        scope.launch {
            repository.search(normalizedQuery)
                .onSuccess { found ->
                    results = found
                    status = if (found.isEmpty()) "未找到在线字幕" else "找到 ${found.size} 个字幕"
                }
                .onFailure { error ->
                    status = "在线字幕搜索失败: ${error.message ?: error::class.simpleName.orEmpty()}"
                }
            searching = false
        }
    }

    fun download(item: OnlineSubtitle) {
        if (downloadingUrl != null) return
        downloadingUrl = item.url
        status = "正在下载 ${item.displayName}"
        scope.launch {
            repository.download(item.url)
                .onSuccess { text -> onSubtitleSelected(text, item.name.ifBlank { item.displayName }) }
                .onFailure { error ->
                    status = "字幕下载失败: ${error.message ?: error::class.simpleName.orEmpty()}"
                }
            downloadingUrl = null
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("在线字幕") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "关闭")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { documentLauncher.launch(SUBTITLE_DOCUMENT_MIME_TYPES.toTypedArray()) },
                    ) {
                        Icon(Icons.Default.FileOpen, contentDescription = "导入字幕文件")
                    }
                    IconButton(onClick = { showPasteEditor = true }) {
                        Icon(Icons.Default.ContentPaste, contentDescription = "粘贴字幕")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("番号或关键词") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() }),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = ::search, enabled = !searching) {
                    Icon(Icons.Default.Search, contentDescription = "字幕搜索")
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { showSubtitlecat = true }) {
                    Text("打开 Subtitlecat")
                }
                if (searching) {
                    Spacer(Modifier.size(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }

            if (status.isNotEmpty()) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }

            if (results.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("暂无字幕结果", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "可先在线搜索，或打开 Subtitlecat 自行下载后导入字幕文件。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(results) { item ->
                        ListItem(
                            headlineContent = {
                                Text(item.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = {
                                Text(item.url, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            },
                            trailingContent = {
                                if (downloadingUrl == item.url) {
                                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                                } else {
                                    TextButton(
                                        onClick = { download(item) },
                                        enabled = downloadingUrl == null,
                                    ) {
                                        Text("载入")
                                    }
                                }
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasteSubtitleScreen(
    initialText: String,
    onCancel: () -> Unit,
    onApply: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember(initialText) { mutableStateOf(initialText) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("粘贴 SRT / VTT") },
                navigationIcon = {
                    TextButton(onClick = onCancel) { Text("取消") }
                },
                actions = {
                    TextButton(onClick = { onApply(text) }) { Text("应用") }
                },
            )
        },
    ) { innerPadding ->
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("在这里粘贴 SRT 或 VTT 字幕…") },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SubtitlecatScreen(
    query: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val url = remember(query) { buildSubtitlecatUrl(query) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Subtitlecat") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            factory = { context -> createSubtitlecatWebView(context, url) },
            update = {},
            onReset = null,
            onRelease = ::releaseSubtitlecatWebView,
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createSubtitlecatWebView(context: Context, url: String): WebView = WebView(context).apply {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.javaScriptCanOpenWindowsAutomatically = false
    settings.setSupportMultipleWindows(false)
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
    settings.safeBrowsingEnabled = true
    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            !isSafeSubtitlecatNavigation(request.url.toString())

    }
    loadUrl(url)
}

private fun releaseSubtitlecatWebView(webView: WebView) {
    webView.stopLoading()
    webView.webViewClient = WebViewClient()
    webView.webChromeClient = null
    webView.removeAllViews()
    webView.destroy()
}

private suspend fun readSubtitleDocument(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        readSubtitleStream(input, MAXIMUM_IMPORT_BYTES)
    } ?: throw IOException("无法读取文件")
    decodeSubtitleBytes(bytes)
}

internal suspend fun readSubtitleStream(
    input: InputStream,
    maximumBytes: Int,
): ByteArray {
    require(maximumBytes >= 0) { "maximumBytes must not be negative" }
    val output = ByteArrayOutputStream(minOf(maximumBytes, DEFAULT_BUFFER_SIZE))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer)
        currentCoroutineContext().ensureActive()
        if (count < 0) break
        total += count
        if (total > maximumBytes) throw IOException("字幕文件超过 12 MiB")
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

internal suspend inline fun <T> captureSubtitleImport(
    crossinline block: suspend () -> T,
): Result<T> = try {
    Result.success(block())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (error: Exception) {
    Result.failure(error)
}

private const val MAXIMUM_IMPORT_BYTES = 12 * 1024 * 1024
private const val SUBTITLECAT_HOST = "subtitlecat.com"
