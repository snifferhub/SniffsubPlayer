import 'dart:async';

import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart' show Divider;
import 'package:flutter/services.dart';
import 'package:flutter_inappwebview/flutter_inappwebview.dart';

import '../models/media_candidate.dart';
import '../services/ad_block_service.dart';
import '../services/sniffer_script.dart';
import '../state/browser_state.dart';
import 'player_screen.dart';
import 'settings_screen.dart';

class BrowserScreen extends StatefulWidget {
  const BrowserScreen({super.key});

  @override
  State<BrowserScreen> createState() => _BrowserScreenState();
}

class _BrowserScreenState extends State<BrowserScreen> {
  final BrowserState state = BrowserState();
  final AdBlockService adBlock = AdBlockService();
  final TextEditingController addressController = TextEditingController();
  InAppWebViewController? webController;
  Timer? hudTimer;
  String? hudText;
  bool canGoBack = false;
  bool canGoForward = false;

  @override
  void initState() {
    super.initState();
    state.addListener(_refresh);
    adBlock.addListener(_adBlockChanged);
    unawaited(state.restore());
    unawaited(adBlock.restore());
  }

  @override
  void dispose() {
    hudTimer?.cancel();
    state.removeListener(_refresh);
    adBlock.removeListener(_adBlockChanged);
    state.dispose();
    adBlock.dispose();
    addressController.dispose();
    super.dispose();
  }

  void _refresh() {
    if (!mounted) return;
    if (!addressController.selection.isValid ||
        addressController.text != state.address) {
      addressController.text = state.address;
      addressController.selection = TextSelection.collapsed(
        offset: addressController.text.length,
      );
    }
    setState(() {});
  }

  Future<void> _adBlockChanged() async {
    if (!mounted) return;
    setState(() {});
    final controller = webController;
    if (controller == null || adBlock.isUpdating) return;
    await controller.setSettings(
      settings: InAppWebViewSettings(
        contentBlockers: adBlock.contentBlockers,
        userAgent: state.userAgent.isEmpty ? null : state.userAgent,
        allowsInlineMediaPlayback: true,
        mediaPlaybackRequiresUserGesture: false,
      ),
    );
  }

  Future<void> _loadAddress([String? value]) async {
    final uri = BrowserState.normalizeAddress(value ?? addressController.text);
    if (uri == null) return;
    FocusManager.instance.primaryFocus?.unfocus();
    await webController?.loadUrl(urlRequest: URLRequest(url: WebUri.uri(uri)));
  }

  Future<void> _updateNavigationState() async {
    final controller = webController;
    if (controller == null || !mounted) return;
    final back = await controller.canGoBack();
    final forward = await controller.canGoForward();
    if (mounted) {
      setState(() {
        canGoBack = back;
        canGoForward = forward;
      });
    }
  }

  Future<void> _enableSniffer() async {
    try {
      await webController?.evaluateJavascript(source: mediaSnifferScript);
      state.setSnifferEnabled(true);
      HapticFeedback.lightImpact();
    } catch (_) {
      state.setSnifferEnabled(false);
    }
  }

  Future<void> _toggleBookmark() async {
    final url = state.currentPageUrl.isEmpty
        ? addressController.text
        : state.currentPageUrl;
    final title = await webController?.getTitle();
    final added = state.toggleBookmark(url: url, pageTitle: title);
    HapticFeedback.mediumImpact();
    hudTimer?.cancel();
    setState(() => hudText = added ? '已收藏' : '已取消收藏');
    hudTimer = Timer(const Duration(milliseconds: 1250), () {
      if (mounted) setState(() => hudText = null);
    });
  }

  Future<void> _openSettings() async {
    final bookmarkUrl = await Navigator.of(context).push<String>(
      CupertinoPageRoute(
        builder: (_) => SettingsScreen(state: state, adBlock: adBlock),
      ),
    );
    if (bookmarkUrl != null) await _loadAddress(bookmarkUrl);
    await _adBlockChanged();
  }

  @override
  Widget build(BuildContext context) {
    final currentUrl = state.currentPageUrl.isEmpty
        ? addressController.text
        : state.currentPageUrl;
    return CupertinoPageScaffold(
      navigationBar: CupertinoNavigationBar(
        // Keep the browser chrome fully opaque. A translucent navigation bar
        // makes CupertinoPageScaffold extend its child underneath the bar;
        // on Android that hid the address field behind the page title.
        backgroundColor: CupertinoColors.systemBackground,
        middle: Text(
          state.title.isEmpty ? 'SniffSubPlayer' : state.title,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
        ),
        leading: CupertinoButton(
          padding: EdgeInsets.zero,
          onPressed: currentUrl.trim().isEmpty ? null : _toggleBookmark,
          child: Icon(
            state.isBookmarked(currentUrl)
                ? CupertinoIcons.bookmark_fill
                : CupertinoIcons.bookmark,
            size: 23,
          ),
        ),
        trailing: CupertinoButton(
          padding: EdgeInsets.zero,
          onPressed: _openSettings,
          child: const Icon(CupertinoIcons.settings, size: 23),
        ),
      ),
      child: Stack(
        children: [
          Column(
            children: [
              _AddressBar(
                controller: addressController,
                canGoBack: canGoBack,
                canGoForward: canGoForward,
                onBack: () => webController?.goBack(),
                onForward: () => webController?.goForward(),
                onSubmit: _loadAddress,
              ),
              SizedBox(
                height: 2,
                child: state.isLoading
                    ? LayoutBuilder(
                        builder: (context, constraints) => Align(
                          alignment: Alignment.centerLeft,
                          child: AnimatedContainer(
                            duration: const Duration(milliseconds: 120),
                            width:
                                constraints.maxWidth *
                                state.loadProgress.clamp(.03, 1),
                            color: Color(state.accentColorValue),
                          ),
                        ),
                      )
                    : const SizedBox.shrink(),
              ),
              Expanded(child: _webView()),
              _CandidatePanel(
                state: state,
                onEnableSniffer: _enableSniffer,
                onCandidate: (candidate) {
                  Navigator.of(context).push(
                    CupertinoPageRoute(
                      fullscreenDialog: true,
                      builder: (_) => PlayerScreen(
                        candidate: candidate,
                        accentColor: Color(state.accentColorValue),
                      ),
                    ),
                  );
                },
              ),
            ],
          ),
          if (hudText != null)
            IgnorePointer(
              child: Center(
                child: AnimatedOpacity(
                  duration: const Duration(milliseconds: 180),
                  opacity: hudText == null ? 0 : 1,
                  child: DecoratedBox(
                    decoration: BoxDecoration(
                      color: CupertinoColors.systemGrey.withValues(alpha: .9),
                      borderRadius: BorderRadius.circular(24),
                      boxShadow: const [
                        BoxShadow(
                          color: Color(0x38000000),
                          blurRadius: 18,
                          offset: Offset(0, 8),
                        ),
                      ],
                    ),
                    child: Padding(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 30,
                        vertical: 22,
                      ),
                      child: Text(
                        hudText!,
                        style: const TextStyle(
                          color: CupertinoColors.white,
                          fontWeight: FontWeight.w600,
                          fontSize: 17,
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }

  Widget _webView() => InAppWebView(
    initialSettings: InAppWebViewSettings(
      allowsInlineMediaPlayback: true,
      mediaPlaybackRequiresUserGesture: false,
      allowsBackForwardNavigationGestures: true,
      userAgent: state.userAgent.isEmpty ? null : state.userAgent,
      contentBlockers: adBlock.contentBlockers,
    ),
    onWebViewCreated: (controller) {
      webController = controller;
      controller.addJavaScriptHandler(
        handlerName: 'sniffer',
        callback: (arguments) {
          if (arguments.isEmpty || arguments.first is! Map) return null;
          final body = Map<String, dynamic>.from(arguments.first as Map);
          state.addCandidate(
            url: body['url'] as String? ?? '',
            source: body['source'] as String? ?? 'unknown',
            pageUrl: body['pageURL'] as String?,
            detectedUserAgent: body['userAgent'] as String?,
          );
          return true;
        },
      );
    },
    onLoadStart: (_, url) {
      state.setSnifferEnabled(false);
      state.updateLoading(loading: true, progress: .05);
      state.updatePage(url: url?.toString());
      unawaited(_updateNavigationState());
    },
    onLoadStop: (controller, url) async {
      state.updateLoading(loading: false, progress: 1);
      state.updatePage(
        url: url?.toString(),
        pageTitle: await controller.getTitle() ?? '',
      );
      await _updateNavigationState();
    },
    onReceivedError: (_, __, ___) {
      state.updateLoading(loading: false, progress: 0);
    },
    onProgressChanged: (_, progress) => state.updateProgress(progress / 100),
    onUpdateVisitedHistory: (_, url, __) {
      state.updatePage(url: url?.toString());
      unawaited(_updateNavigationState());
    },
  );
}

class _AddressBar extends StatelessWidget {
  const _AddressBar({
    required this.controller,
    required this.canGoBack,
    required this.canGoForward,
    required this.onBack,
    required this.onForward,
    required this.onSubmit,
  });

  final TextEditingController controller;
  final bool canGoBack;
  final bool canGoForward;
  final VoidCallback onBack;
  final VoidCallback onForward;
  final ValueChanged<String> onSubmit;

  @override
  Widget build(BuildContext context) => Container(
    color: CupertinoDynamicColor.resolve(
      CupertinoColors.systemBackground,
      context,
    ),
    padding: const EdgeInsets.all(10),
    child: Row(
      children: [
        _barButton(CupertinoIcons.chevron_back, canGoBack ? onBack : null),
        const SizedBox(width: 4),
        _barButton(
          CupertinoIcons.chevron_forward,
          canGoForward ? onForward : null,
        ),
        const SizedBox(width: 8),
        Expanded(
          child: CupertinoTextField(
            controller: controller,
            placeholder: '输入网址或搜索',
            keyboardType: TextInputType.url,
            textInputAction: TextInputAction.go,
            textCapitalization: TextCapitalization.none,
            autocorrect: false,
            enableSuggestions: false,
            clearButtonMode: OverlayVisibilityMode.editing,
            decoration: BoxDecoration(
              color: CupertinoDynamicColor.resolve(
                CupertinoColors.systemGrey6,
                context,
              ),
              borderRadius: BorderRadius.circular(10),
              border: Border.all(
                color: CupertinoDynamicColor.resolve(
                  CupertinoColors.separator,
                  context,
                ),
                width: .5,
              ),
            ),
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
            onSubmitted: onSubmit,
          ),
        ),
        const SizedBox(width: 6),
        CupertinoButton(
          minimumSize: const Size(44, 38),
          padding: const EdgeInsets.symmetric(horizontal: 8),
          onPressed: () => onSubmit(controller.text),
          child: const Text('前往'),
        ),
      ],
    ),
  );

  Widget _barButton(IconData icon, VoidCallback? onPressed) => CupertinoButton(
    minimumSize: const Size.square(34),
    padding: const EdgeInsets.symmetric(horizontal: 4),
    onPressed: onPressed,
    child: Icon(icon, size: 21),
  );
}

class _CandidatePanel extends StatelessWidget {
  const _CandidatePanel({
    required this.state,
    required this.onEnableSniffer,
    required this.onCandidate,
  });

  final BrowserState state;
  final VoidCallback onEnableSniffer;
  final ValueChanged<MediaCandidate> onCandidate;

  @override
  Widget build(BuildContext context) => Container(
    color: CupertinoDynamicColor.resolve(
      CupertinoColors.secondarySystemBackground,
      context,
    ).withValues(alpha: .96),
    constraints: const BoxConstraints(maxHeight: 250),
    child: Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 8, 8, 8),
          child: Row(
            children: [
              Text(
                '嗅探到 ${state.candidates.length} 个媒体资源',
                style: const TextStyle(
                  fontSize: 17,
                  fontWeight: FontWeight.w600,
                ),
              ),
              const Spacer(),
              if (state.isLoading)
                const Padding(
                  padding: EdgeInsets.only(right: 8),
                  child: CupertinoActivityIndicator(radius: 8),
                ),
              CupertinoButton(
                padding: const EdgeInsets.symmetric(horizontal: 7),
                minimumSize: const Size.square(32),
                onPressed: state.isLoading || state.isSnifferEnabled
                    ? null
                    : onEnableSniffer,
                child: Row(
                  children: [
                    Icon(
                      state.isSnifferEnabled
                          ? CupertinoIcons.check_mark_circled_solid
                          : CupertinoIcons.waveform,
                      size: 17,
                    ),
                    const SizedBox(width: 4),
                    Text(
                      state.isSnifferEnabled ? '已启用' : '启用嗅探',
                      style: const TextStyle(fontSize: 14),
                    ),
                  ],
                ),
              ),
              CupertinoButton(
                padding: const EdgeInsets.symmetric(horizontal: 7),
                minimumSize: const Size.square(32),
                onPressed: state.candidates.isEmpty
                    ? null
                    : state.clearCandidates,
                child: const Icon(CupertinoIcons.delete, size: 19),
              ),
            ],
          ),
        ),
        if (state.candidates.isEmpty)
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 10),
            child: Align(
              alignment: Alignment.centerLeft,
              child: Text(
                state.isSnifferEnabled
                    ? '嗅探已启用，播放视频后会收集 m3u8 / mp4 / blob / 分片候选'
                    : '页面加载完成后，点击“启用嗅探”再播放视频',
                style: const TextStyle(
                  color: CupertinoColors.secondaryLabel,
                  fontSize: 13,
                ),
              ),
            ),
          )
        else
          Flexible(
            child: ListView.separated(
              padding: EdgeInsets.zero,
              itemCount: state.candidates.take(30).length,
              separatorBuilder: (_, __) =>
                  const Divider(height: .5, indent: 16),
              itemBuilder: (context, index) {
                final candidate = state.candidates[index];
                return CupertinoButton(
                  padding: const EdgeInsets.fromLTRB(16, 7, 16, 7),
                  alignment: Alignment.centerLeft,
                  onPressed: () => onCandidate(candidate),
                  child: _CandidateRow(candidate: candidate),
                );
              },
            ),
          ),
      ],
    ),
  );
}

class _CandidateRow extends StatelessWidget {
  const _CandidateRow({required this.candidate});
  final MediaCandidate candidate;

  @override
  Widget build(BuildContext context) {
    final color = switch (candidate.kind) {
      MediaKind.hls => CupertinoColors.systemGreen,
      MediaKind.mp4 => CupertinoColors.systemBlue,
      MediaKind.dash => CupertinoColors.systemPurple,
      MediaKind.segment => CupertinoColors.systemOrange,
      MediaKind.blob => CupertinoColors.systemRed,
      MediaKind.other => CupertinoColors.systemGrey,
    };
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            DecoratedBox(
              decoration: BoxDecoration(
                color: color.withValues(alpha: .16),
                borderRadius: BorderRadius.circular(10),
              ),
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                child: Text(
                  candidate.kind.label,
                  style: TextStyle(
                    color: color,
                    fontSize: 12,
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ),
            ),
            const SizedBox(width: 8),
            Text(
              candidate.source,
              style: const TextStyle(
                color: CupertinoColors.secondaryLabel,
                fontSize: 12,
              ),
            ),
          ],
        ),
        const SizedBox(height: 5),
        Text(
          candidate.url,
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
          style: const TextStyle(color: CupertinoColors.label, fontSize: 12),
        ),
      ],
    );
  }
}
