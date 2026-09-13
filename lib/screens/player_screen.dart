import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;
import 'dart:ui';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart' show Divider;
import 'package:flutter/services.dart';
import 'package:flutter_inappwebview/flutter_inappwebview.dart';
import 'package:screen_brightness/screen_brightness.dart';
import 'package:video_player/video_player.dart';
import 'package:volume_controller/volume_controller.dart';
import 'package:wakelock_plus/wakelock_plus.dart';

import '../models/media_candidate.dart';
import '../models/subtitle.dart';
import '../services/subtitle_service.dart';

class PlayerScreen extends StatefulWidget {
  const PlayerScreen({
    super.key,
    required this.candidate,
    required this.accentColor,
  });

  final MediaCandidate candidate;
  final Color accentColor;

  @override
  State<PlayerScreen> createState() => _PlayerScreenState();
}

class _PlayerScreenState extends State<PlayerScreen> {
  VideoPlayerController? controller;
  Timer? hideTimer;
  Timer? feedbackTimer;
  bool showControls = true;
  bool showSubtitleSettings = false;
  bool showFeedback = false;
  bool isScrubbing = false;
  bool horizontalDrag = false;
  bool verticalDrag = false;
  bool volumeDrag = false;
  double dragStartSeconds = 0;
  double verticalStart = 0;
  double subtitleOffset = 0;
  double subtitleSize = 30;
  double subtitleBottom = 38;
  String subtitleSource = '';
  String subtitleText = '';
  String status = '';
  String feedbackText = '';
  IconData feedbackIcon = CupertinoIcons.goforward_10;
  Offset? lastDoubleTapPosition;
  List<SubtitleCue> cues = const [];

  Duration get position => controller?.value.position ?? Duration.zero;
  Duration get duration => controller?.value.duration ?? Duration.zero;
  bool get isPlaying => controller?.value.isPlaying ?? false;

  @override
  void initState() {
    super.initState();
    _enterPlayer();
  }

  Future<void> _enterPlayer() async {
    await SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
    await SystemChrome.setPreferredOrientations(const [
      DeviceOrientation.landscapeLeft,
      DeviceOrientation.landscapeRight,
    ]);
    await WakelockPlus.enable();
    VolumeController.instance.showSystemUI = false;
    if (widget.candidate.kind == MediaKind.blob) {
      setState(() {
        status = 'Blob 链接不能离开原网页直接播放，请选择 m3u8/mp4 候选';
      });
      return;
    }
    try {
      final headers = <String, String>{
        if (widget.candidate.userAgent.isNotEmpty)
          'User-Agent': widget.candidate.userAgent,
        if (widget.candidate.referer.isNotEmpty)
          'Referer': widget.candidate.referer,
      };
      final videoController = VideoPlayerController.networkUrl(
        Uri.parse(widget.candidate.url),
        httpHeaders: headers,
      );
      controller = videoController;
      videoController.addListener(_videoChanged);
      await videoController.initialize();
      await videoController.play();
      if (mounted) {
        setState(() {});
        _scheduleAutoHide();
      }
    } catch (error) {
      if (mounted) setState(() => status = '视频加载失败: $error');
    }
  }

  @override
  void dispose() {
    hideTimer?.cancel();
    feedbackTimer?.cancel();
    controller?.removeListener(_videoChanged);
    controller?.dispose();
    WakelockPlus.disable();
    ScreenBrightness.instance.resetApplicationScreenBrightness();
    SystemChrome.setPreferredOrientations(const [
      DeviceOrientation.portraitUp,
      DeviceOrientation.landscapeLeft,
      DeviceOrientation.landscapeRight,
    ]);
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
    super.dispose();
  }

  void _videoChanged() {
    if (!mounted || isScrubbing) return;
    final current = controller?.value.position ?? Duration.zero;
    final active = cues.cast<SubtitleCue?>().firstWhere(
      (cue) => cue != null && current >= cue.start && current <= cue.end,
      orElse: () => null,
    );
    final nextText = active?.text ?? '';
    if (nextText != subtitleText || controller!.value.hasError) {
      setState(() {
        subtitleText = nextText;
        if (controller!.value.hasError) {
          status = controller!.value.errorDescription ?? '播放失败';
        }
      });
    } else {
      setState(() {});
    }
  }

  void _toggleControls() {
    setState(() => showControls = !showControls);
    if (showControls) {
      _scheduleAutoHide();
    } else {
      hideTimer?.cancel();
    }
  }

  void _keepControlsVisible() {
    if (!showControls) setState(() => showControls = true);
    _scheduleAutoHide();
  }

  void _scheduleAutoHide() {
    hideTimer?.cancel();
    if (!isPlaying) return;
    hideTimer = Timer(const Duration(seconds: 4), () {
      if (mounted && isPlaying && !showSubtitleSettings && !isScrubbing) {
        setState(() => showControls = false);
      }
    });
  }

  Future<void> _togglePlay() async {
    final videoController = controller;
    if (videoController == null || !videoController.value.isInitialized) return;
    if (videoController.value.isPlaying) {
      await videoController.pause();
      hideTimer?.cancel();
      setState(() => showControls = true);
    } else {
      await videoController.play();
      _scheduleAutoHide();
    }
    setState(() {});
  }

  Future<void> _seekBy(double seconds, {bool withFeedback = false}) async {
    final videoController = controller;
    if (videoController == null) return;
    final total = duration.inMilliseconds;
    final target = (position.inMilliseconds + seconds * 1000).round().clamp(
      0,
      total > 0 ? total : 1 << 31,
    );
    await videoController.seekTo(Duration(milliseconds: target));
    if (withFeedback) {
      HapticFeedback.lightImpact();
      _showFeedback(
        seconds > 0 ? '快进 10 秒' : '后退 10 秒',
        seconds > 0
            ? CupertinoIcons.goforward_10
            : CupertinoIcons.gobackward_10,
        const Duration(milliseconds: 700),
      );
    }
  }

  void _handleDoubleTap() {
    final width = MediaQuery.sizeOf(context).width;
    final forward = (lastDoubleTapPosition?.dx ?? width) >= width / 2;
    _seekBy(forward ? 10 : -10, withFeedback: true);
  }

  void _showFeedback(String text, IconData icon, Duration duration) {
    feedbackTimer?.cancel();
    setState(() {
      feedbackText = text;
      feedbackIcon = icon;
      showFeedback = true;
    });
    feedbackTimer = Timer(duration, () {
      if (mounted) setState(() => showFeedback = false);
    });
  }

  void _horizontalStart(DragStartDetails details) {
    horizontalDrag = true;
    dragStartSeconds = position.inMilliseconds / 1000;
    isScrubbing = true;
    hideTimer?.cancel();
    _showFeedback(
      '${_formatTime(position)} / ${_formatTime(duration)}',
      CupertinoIcons.arrow_left_right,
      const Duration(days: 1),
    );
  }

  void _horizontalUpdate(DragUpdateDetails details) {
    if (!horizontalDrag || duration == Duration.zero) return;
    final width = math.max(MediaQuery.sizeOf(context).width, 1).toDouble();
    final seekRange = math
        .min(math.max(duration.inSeconds * .1, 60), 600)
        .toDouble();
    dragStartSeconds += details.delta.dx / width * seekRange;
    final next = dragStartSeconds
        .clamp(0, duration.inMilliseconds / 1000)
        .toDouble();
    dragStartSeconds = next;
    setState(() {
      feedbackText =
          '${_formatTime(Duration(milliseconds: (next * 1000).round()))} / '
          '${_formatTime(duration)}';
    });
  }

  Future<void> _horizontalEnd(DragEndDetails details) async {
    if (!horizontalDrag) return;
    horizontalDrag = false;
    await controller?.seekTo(
      Duration(milliseconds: (dragStartSeconds * 1000).round()),
    );
    isScrubbing = false;
    HapticFeedback.lightImpact();
    _showFeedback(
      feedbackText,
      CupertinoIcons.arrow_left_right,
      const Duration(milliseconds: 450),
    );
  }

  Future<void> _verticalStart(DragStartDetails details) async {
    verticalDrag = true;
    volumeDrag =
        details.globalPosition.dx >= MediaQuery.sizeOf(context).width / 2;
    try {
      verticalStart = volumeDrag
          ? await VolumeController.instance.getVolume()
          : await ScreenBrightness.instance.application;
    } catch (_) {
      verticalStart = .5;
    }
    _showFeedback(
      '${(verticalStart * 100).round()}%',
      volumeDrag ? CupertinoIcons.volume_up : CupertinoIcons.sun_max_fill,
      const Duration(days: 1),
    );
  }

  Future<void> _verticalUpdate(DragUpdateDetails details) async {
    if (!verticalDrag) return;
    final height = math.max(MediaQuery.sizeOf(context).height, 1);
    verticalStart = (verticalStart - details.delta.dy / height).clamp(0, 1);
    try {
      if (volumeDrag) {
        await VolumeController.instance.setVolume(verticalStart);
      } else {
        await ScreenBrightness.instance.setApplicationScreenBrightness(
          verticalStart,
        );
      }
    } catch (_) {}
    if (mounted) {
      setState(() => feedbackText = '${(verticalStart * 100).round()}%');
    }
  }

  void _verticalEnd(DragEndDetails details) {
    verticalDrag = false;
    HapticFeedback.lightImpact();
    _showFeedback(
      feedbackText,
      feedbackIcon,
      const Duration(milliseconds: 450),
    );
  }

  @override
  Widget build(BuildContext context) {
    return CupertinoPageScaffold(
      backgroundColor: CupertinoColors.black,
      child: Stack(
        fit: StackFit.expand,
        children: [
          _videoLayer(),
          GestureDetector(
            behavior: HitTestBehavior.translucent,
            onTap: _toggleControls,
            onDoubleTapDown: (details) =>
                lastDoubleTapPosition = details.localPosition,
            onDoubleTap: _handleDoubleTap,
            onHorizontalDragStart: _horizontalStart,
            onHorizontalDragUpdate: _horizontalUpdate,
            onHorizontalDragEnd: _horizontalEnd,
            onVerticalDragStart: _verticalStart,
            onVerticalDragUpdate: _verticalUpdate,
            onVerticalDragEnd: _verticalEnd,
          ),
          if (showFeedback)
            IgnorePointer(
              child: Center(
                child: Transform.translate(
                  offset: const Offset(0, 86),
                  child: _GlassCapsule(
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(
                          feedbackIcon,
                          color: CupertinoColors.white,
                          size: 22,
                        ),
                        const SizedBox(width: 8),
                        Text(
                          feedbackText,
                          style: const TextStyle(
                            color: CupertinoColors.white,
                            fontSize: 17,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          if (subtitleText.isNotEmpty)
            Positioned(
              left: 18,
              right: 18,
              bottom: subtitleBottom,
              child: IgnorePointer(
                child: Text(
                  subtitleText,
                  maxLines: 4,
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    color: CupertinoColors.white,
                    fontSize: subtitleSize,
                    fontWeight: FontWeight.bold,
                    shadows: const [
                      Shadow(
                        color: CupertinoColors.black,
                        blurRadius: 4,
                        offset: Offset(0, 2),
                      ),
                      Shadow(color: CupertinoColors.black, blurRadius: 10),
                    ],
                  ),
                ),
              ),
            ),
          AnimatedOpacity(
            duration: const Duration(milliseconds: 220),
            opacity: showControls ? 1 : 0,
            child: IgnorePointer(
              ignoring: !showControls,
              child: PlayerControlsOverlay(
                accentColor: widget.accentColor,
                status: status,
                hasSubtitles: cues.isNotEmpty,
                isPlaying: isPlaying,
                position: isScrubbing
                    ? Duration(milliseconds: (dragStartSeconds * 1000).round())
                    : position,
                duration: duration,
                onClose: () => Navigator.pop(context),
                onSubtitle: () {
                  _keepControlsVisible();
                  setState(() => showSubtitleSettings = true);
                },
                onPlayPause: _togglePlay,
                onBackward: () => _seekBy(-10),
                onForward: () => _seekBy(10),
                onSeekStart: () {
                  hideTimer?.cancel();
                  setState(() => isScrubbing = true);
                },
                onSeek: (value) {
                  dragStartSeconds = value;
                  setState(() {});
                },
                onSeekEnd: (value) async {
                  await controller?.seekTo(
                    Duration(milliseconds: (value * 1000).round()),
                  );
                  setState(() => isScrubbing = false);
                  _scheduleAutoHide();
                },
              ),
            ),
          ),
          if (showSubtitleSettings) _subtitleSettingsOverlay(),
        ],
      ),
    );
  }

  Widget _videoLayer() {
    final videoController = controller;
    if (videoController == null || !videoController.value.isInitialized) {
      return Center(
        child: status.isEmpty
            ? const CupertinoActivityIndicator(color: CupertinoColors.white)
            : Padding(
                padding: const EdgeInsets.all(28),
                child: Text(
                  status,
                  textAlign: TextAlign.center,
                  style: const TextStyle(color: CupertinoColors.white),
                ),
              ),
      );
    }
    final size = videoController.value.size;
    return Center(
      child: AspectRatio(
        aspectRatio: size.width > 0 && size.height > 0
            ? size.width / size.height
            : 16 / 9,
        child: VideoPlayer(videoController),
      ),
    );
  }

  Widget _subtitleSettingsOverlay() => GestureDetector(
    behavior: HitTestBehavior.opaque,
    onTap: () => setState(() => showSubtitleSettings = false),
    child: ColoredBox(
      color: CupertinoColors.black.withValues(alpha: .3),
      child: Center(
        child: GestureDetector(
          onTap: () {},
          child: ClipRRect(
            borderRadius: BorderRadius.circular(22),
            child: BackdropFilter(
              filter: ImageFilter.blur(sigmaX: 28, sigmaY: 28),
              child: Container(
                width: math.min(MediaQuery.sizeOf(context).width - 56, 520),
                padding: const EdgeInsets.all(20),
                decoration: BoxDecoration(
                  color: CupertinoColors.systemBackground.withValues(alpha: .9),
                  borderRadius: BorderRadius.circular(22),
                  border: Border.all(
                    color: CupertinoColors.white.withValues(alpha: .12),
                  ),
                ),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Row(
                      children: [
                        const Text(
                          '字幕设置',
                          style: TextStyle(
                            fontSize: 17,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                        const Spacer(),
                        CupertinoButton(
                          padding: EdgeInsets.zero,
                          minimumSize: const Size.square(28),
                          onPressed: () =>
                              setState(() => showSubtitleSettings = false),
                          child: const Icon(
                            CupertinoIcons.xmark_circle_fill,
                            size: 22,
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 12),
                    Row(
                      children: [
                        _subtitleAction(
                          '在线搜索',
                          CupertinoIcons.search,
                          _openSubtitleSearch,
                        ),
                        const SizedBox(width: 10),
                        _subtitleAction(
                          '字幕文件',
                          CupertinoIcons.doc_append,
                          _importSubtitle,
                        ),
                        const SizedBox(width: 10),
                        _subtitleAction(
                          '查看字幕',
                          CupertinoIcons.doc_on_clipboard,
                          _openPasteSubtitle,
                        ),
                      ],
                    ),
                    const Padding(
                      padding: EdgeInsets.symmetric(vertical: 13),
                      child: Divider(height: .5),
                    ),
                    Row(
                      children: [
                        const SizedBox(width: 52, child: Text('同步')),
                        _borderedButton('−0.5', () => _adjustOffset(-.5)),
                        Expanded(
                          child: Text(
                            '${subtitleOffset >= 0 ? '+' : ''}${subtitleOffset.toStringAsFixed(1)} 秒',
                            textAlign: TextAlign.center,
                            style: const TextStyle(
                              color: CupertinoColors.secondaryLabel,
                              fontSize: 14,
                              fontFeatures: [FontFeature.tabularFigures()],
                            ),
                          ),
                        ),
                        _borderedButton('+0.5', () => _adjustOffset(.5)),
                      ],
                    ),
                    const SizedBox(height: 12),
                    _subtitleSlider(
                      label: '字号',
                      value: subtitleSize,
                      min: 18,
                      max: 56,
                      suffix: '${subtitleSize.round()} pt',
                      onChanged: (value) =>
                          setState(() => subtitleSize = value),
                    ),
                    _subtitleSlider(
                      label: '位置',
                      value: subtitleBottom,
                      min: 8,
                      max: 180,
                      suffix: '${subtitleBottom.round()} pt',
                      onChanged: (value) =>
                          setState(() => subtitleBottom = value),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    ),
  );

  Widget _subtitleAction(String label, IconData icon, VoidCallback onPressed) =>
      Expanded(
        child: CupertinoButton(
          minimumSize: const Size.square(38),
          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 9),
          color: CupertinoColors.systemGrey5,
          onPressed: () {
            setState(() => showSubtitleSettings = false);
            Future<void>.delayed(const Duration(milliseconds: 220), onPressed);
          },
          child: Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(icon, size: 17, color: CupertinoColors.label),
              const SizedBox(width: 5),
              Flexible(
                child: Text(
                  label,
                  style: const TextStyle(
                    color: CupertinoColors.label,
                    fontSize: 14,
                  ),
                ),
              ),
            ],
          ),
        ),
      );

  Widget _borderedButton(String label, VoidCallback onPressed) =>
      CupertinoButton(
        minimumSize: const Size.square(34),
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
        color: CupertinoColors.systemGrey5,
        onPressed: onPressed,
        child: Text(
          label,
          style: const TextStyle(color: CupertinoColors.label, fontSize: 14),
        ),
      );

  Widget _subtitleSlider({
    required String label,
    required double value,
    required double min,
    required double max,
    required String suffix,
    required ValueChanged<double> onChanged,
  }) => Row(
    children: [
      SizedBox(width: 52, child: Text(label)),
      Expanded(
        child: CupertinoSlider(
          value: value,
          min: min,
          max: max,
          divisions: (max - min).round(),
          activeColor: widget.accentColor,
          onChanged: onChanged,
        ),
      ),
      SizedBox(
        width: 54,
        child: Text(
          suffix,
          textAlign: TextAlign.right,
          style: const TextStyle(
            color: CupertinoColors.secondaryLabel,
            fontSize: 12,
            fontFeatures: [FontFeature.tabularFigures()],
          ),
        ),
      ),
    ],
  );

  Future<void> _importSubtitle() async {
    final result = await FilePicker.pickFiles(
      type: FileType.custom,
      allowedExtensions: const ['srt', 'vtt', 'txt'],
      withData: true,
    );
    if (result == null || result.files.isEmpty) return;
    final file = result.files.single;
    try {
      final bytes =
          file.bytes ??
          (file.path == null ? null : await File(file.path!).readAsBytes());
      if (bytes == null) throw Exception('无法读取文件');
      _applySubtitle(utf8AllowMalformed(bytes), file.name);
    } catch (error) {
      if (mounted) setState(() => status = '字幕导入失败: $error');
    }
  }

  String utf8AllowMalformed(List<int> bytes) =>
      const Utf8Decoder(allowMalformed: true).convert(bytes);

  Future<void> _openPasteSubtitle() async {
    final text = await Navigator.of(context).push<String>(
      CupertinoPageRoute(
        fullscreenDialog: true,
        builder: (_) => PasteSubtitleScreen(initialText: subtitleSource),
      ),
    );
    if (text != null) _applySubtitle(text, '文本');
  }

  Future<void> _openSubtitleSearch() async {
    final query = VideoIdExtractor.extract([
      widget.candidate.pageUrl,
      widget.candidate.url,
    ]);
    final result = await Navigator.of(context).push<_SubtitleResult>(
      CupertinoPageRoute(
        fullscreenDialog: true,
        builder: (_) => SubtitleSearchScreen(initialQuery: query),
      ),
    );
    if (result != null) _applySubtitle(result.text, result.label);
  }

  void _applySubtitle(String text, String label) {
    subtitleSource = text;
    cues = SubtitleParser.parse(text, offsetSeconds: subtitleOffset);
    setState(() {
      status = cues.isEmpty ? '没有解析到有效字幕' : '已载入 ${cues.length} 条字幕 ($label)';
    });
  }

  void _adjustOffset(double delta) {
    subtitleOffset += delta;
    if (subtitleSource.isNotEmpty) {
      cues = SubtitleParser.parse(
        subtitleSource,
        offsetSeconds: subtitleOffset,
      );
    }
    setState(() => status = '字幕偏移 ${subtitleOffset.toStringAsFixed(1)}s');
  }
}

class PlayerControlsOverlay extends StatelessWidget {
  const PlayerControlsOverlay({
    super.key,
    required this.accentColor,
    required this.status,
    required this.hasSubtitles,
    required this.isPlaying,
    required this.position,
    required this.duration,
    required this.onClose,
    required this.onSubtitle,
    required this.onPlayPause,
    required this.onBackward,
    required this.onForward,
    required this.onSeekStart,
    required this.onSeek,
    required this.onSeekEnd,
  });

  final Color accentColor;
  final String status;
  final bool hasSubtitles;
  final bool isPlaying;
  final Duration position;
  final Duration duration;
  final VoidCallback onClose;
  final VoidCallback onSubtitle;
  final VoidCallback onPlayPause;
  final VoidCallback onBackward;
  final VoidCallback onForward;
  final VoidCallback onSeekStart;
  final ValueChanged<double> onSeek;
  final ValueChanged<double> onSeekEnd;

  @override
  Widget build(BuildContext context) {
    final durationSeconds = math
        .max(duration.inMilliseconds / 1000, 1)
        .toDouble();
    final positionSeconds = (position.inMilliseconds / 1000)
        .clamp(0, durationSeconds)
        .toDouble();
    return DecoratedBox(
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [Color(0x9E000000), Color(0x00000000), Color(0xC2000000)],
          stops: [0, .48, 1],
        ),
      ),
      child: SafeArea(
        child: Stack(
          children: [
            Positioned(
              left: 22,
              top: 14,
              right: 22,
              child: Row(
                children: [
                  _CircleControl(
                    size: 38,
                    icon: CupertinoIcons.xmark,
                    iconSize: 17,
                    onPressed: onClose,
                  ),
                  if (status.isNotEmpty) ...[
                    const SizedBox(width: 8),
                    Flexible(
                      child: Text(
                        status,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                          color: Color(0xC2FFFFFF),
                          fontSize: 12,
                        ),
                      ),
                    ),
                  ],
                  const Spacer(),
                  _GlassCapsule(
                    padding: EdgeInsets.zero,
                    child: CupertinoButton(
                      minimumSize: const Size.square(38),
                      padding: const EdgeInsets.symmetric(horizontal: 13),
                      onPressed: onSubtitle,
                      child: Icon(
                        hasSubtitles
                            ? CupertinoIcons.captions_bubble_fill
                            : CupertinoIcons.captions_bubble,
                        color: CupertinoColors.white,
                        size: 20,
                      ),
                    ),
                  ),
                ],
              ),
            ),
            Center(
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  _CircleControl(
                    icon: CupertinoIcons.gobackward_10,
                    iconSize: 28,
                    onPressed: onBackward,
                  ),
                  const SizedBox(width: 42),
                  _CircleControl(
                    size: 70,
                    icon: isPlaying
                        ? CupertinoIcons.pause_fill
                        : CupertinoIcons.play_fill,
                    iconSize: 33,
                    onPressed: onPlayPause,
                  ),
                  const SizedBox(width: 42),
                  _CircleControl(
                    icon: CupertinoIcons.goforward_10,
                    iconSize: 28,
                    onPressed: onForward,
                  ),
                ],
              ),
            ),
            Positioned(
              left: 24,
              right: 24,
              bottom: 18,
              child: Row(
                children: [
                  SizedBox(
                    width: 54,
                    child: Text(_formatTime(position), style: _timeStyle),
                  ),
                  Expanded(
                    child: PlayerProgressBar(
                      value: positionSeconds,
                      max: durationSeconds,
                      accentColor: accentColor,
                      onStart: onSeekStart,
                      onChanged: onSeek,
                      onEnd: onSeekEnd,
                    ),
                  ),
                  SizedBox(
                    width: 54,
                    child: Text(
                      _formatTime(duration),
                      textAlign: TextAlign.right,
                      style: _timeStyle,
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  static const _timeStyle = TextStyle(
    color: Color(0xE6FFFFFF),
    fontSize: 12,
    fontFeatures: [FontFeature.tabularFigures()],
  );
}

class PlayerProgressBar extends StatefulWidget {
  const PlayerProgressBar({
    super.key,
    required this.value,
    required this.max,
    required this.accentColor,
    required this.onStart,
    required this.onChanged,
    required this.onEnd,
  });
  final double value;
  final double max;
  final Color accentColor;
  final VoidCallback onStart;
  final ValueChanged<double> onChanged;
  final ValueChanged<double> onEnd;

  @override
  State<PlayerProgressBar> createState() => _PlayerProgressBarState();
}

class _PlayerProgressBarState extends State<PlayerProgressBar> {
  double? dragValue;

  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, constraints) {
      final width = math.max(constraints.maxWidth, 1).toDouble();
      final displayValue = dragValue ?? widget.value;
      final fraction = (displayValue / math.max(widget.max, 1))
          .clamp(0, 1)
          .toDouble();
      double valueAt(double dx) => (dx / width).clamp(0, 1) * widget.max;
      return GestureDetector(
        behavior: HitTestBehavior.opaque,
        onHorizontalDragStart: (_) {
          dragValue = widget.value;
          widget.onStart();
        },
        onHorizontalDragUpdate: (details) {
          final next = valueAt(details.localPosition.dx);
          setState(() => dragValue = next);
          widget.onChanged(next);
        },
        onHorizontalDragEnd: (_) {
          final next = dragValue ?? widget.value;
          setState(() => dragValue = null);
          widget.onEnd(next);
        },
        onTapDown: (details) {
          widget.onStart();
          final next = valueAt(details.localPosition.dx);
          widget.onChanged(next);
          widget.onEnd(next);
        },
        child: SizedBox(
          height: 30,
          child: Stack(
            alignment: Alignment.centerLeft,
            children: [
              Container(
                height: 4,
                decoration: BoxDecoration(
                  color: CupertinoColors.white.withValues(alpha: .28),
                  borderRadius: BorderRadius.circular(2),
                ),
              ),
              Container(
                width: width * fraction,
                height: 4,
                decoration: BoxDecoration(
                  color: widget.accentColor,
                  borderRadius: BorderRadius.circular(2),
                ),
              ),
              Positioned(
                left: math.max(0, math.min(width - 14, width * fraction - 7)),
                child: Container(
                  width: 14,
                  height: 14,
                  decoration: const BoxDecoration(
                    color: CupertinoColors.white,
                    shape: BoxShape.circle,
                    boxShadow: [
                      BoxShadow(
                        color: Color(0x47000000),
                        blurRadius: 3,
                        offset: Offset(0, 1),
                      ),
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
      );
    },
  );
}

class _CircleControl extends StatelessWidget {
  const _CircleControl({
    required this.icon,
    required this.iconSize,
    required this.onPressed,
    this.size = 54,
  });
  final IconData icon;
  final double iconSize;
  final double size;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) => ClipOval(
    child: BackdropFilter(
      filter: ImageFilter.blur(sigmaX: 18, sigmaY: 18),
      child: CupertinoButton(
        minimumSize: Size.square(size),
        padding: EdgeInsets.zero,
        color: CupertinoColors.white.withValues(alpha: .14),
        onPressed: onPressed,
        child: Icon(icon, size: iconSize, color: CupertinoColors.white),
      ),
    ),
  );
}

class _GlassCapsule extends StatelessWidget {
  const _GlassCapsule({
    required this.child,
    this.padding = const EdgeInsets.symmetric(horizontal: 20, vertical: 14),
  });
  final Widget child;
  final EdgeInsets padding;

  @override
  Widget build(BuildContext context) => ClipRRect(
    borderRadius: BorderRadius.circular(999),
    child: BackdropFilter(
      filter: ImageFilter.blur(sigmaX: 18, sigmaY: 18),
      child: Container(
        padding: padding,
        color: CupertinoColors.black.withValues(alpha: .68),
        child: child,
      ),
    ),
  );
}

class PasteSubtitleScreen extends StatefulWidget {
  const PasteSubtitleScreen({super.key, required this.initialText});
  final String initialText;

  @override
  State<PasteSubtitleScreen> createState() => _PasteSubtitleScreenState();
}

class _PasteSubtitleScreenState extends State<PasteSubtitleScreen> {
  late final TextEditingController controller = TextEditingController(
    text: widget.initialText,
  );

  @override
  void dispose() {
    controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => CupertinoPageScaffold(
    navigationBar: CupertinoNavigationBar(
      middle: const Text('粘贴 SRT / VTT'),
      leading: CupertinoButton(
        padding: EdgeInsets.zero,
        onPressed: () => Navigator.pop(context),
        child: const Text('取消'),
      ),
      trailing: CupertinoButton(
        padding: EdgeInsets.zero,
        onPressed: () => Navigator.pop(context, controller.text),
        child: const Text('应用'),
      ),
    ),
    child: SafeArea(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: CupertinoTextField(
          controller: controller,
          expands: true,
          minLines: null,
          maxLines: null,
          textAlignVertical: TextAlignVertical.top,
          style: const TextStyle(fontFamily: 'monospace'),
          placeholder: '在这里粘贴 SRT 或 VTT 字幕…',
        ),
      ),
    ),
  );
}

class SubtitleSearchScreen extends StatefulWidget {
  const SubtitleSearchScreen({super.key, required this.initialQuery});
  final String initialQuery;

  @override
  State<SubtitleSearchScreen> createState() => _SubtitleSearchScreenState();
}

class _SubtitleSearchScreenState extends State<SubtitleSearchScreen> {
  final service = const SubtitleService();
  late final TextEditingController queryController = TextEditingController(
    text: widget.initialQuery,
  );
  List<OnlineSubtitle> results = const [];
  bool searching = false;
  String status = '';

  @override
  void dispose() {
    queryController.dispose();
    super.dispose();
  }

  Future<void> _search() async {
    final query = queryController.text.trim();
    if (query.isEmpty) {
      setState(() => status = '请输入番号或关键词后再搜索');
      return;
    }
    setState(() {
      searching = true;
      status = '正在搜索字幕: $query';
    });
    try {
      final found = await service.search(query);
      if (mounted) {
        setState(() {
          results = found;
          status = found.isEmpty ? '未找到在线字幕' : '找到 ${found.length} 个字幕';
        });
      }
    } catch (error) {
      if (mounted) setState(() => status = '在线字幕搜索失败: $error');
    } finally {
      if (mounted) setState(() => searching = false);
    }
  }

  Future<void> _load(OnlineSubtitle item) async {
    setState(() => status = '正在下载 ${item.displayName}');
    try {
      final text = await service.download(item.url);
      if (mounted) {
        Navigator.pop(context, _SubtitleResult(text, item.name));
      }
    } catch (error) {
      if (mounted) setState(() => status = '字幕下载失败: $error');
    }
  }

  void _openSubtitlecat() {
    final uri = Uri.https('subtitlecat.com', '/index.php', {
      'search': queryController.text.trim(),
    });
    Navigator.of(
      context,
    ).push(CupertinoPageRoute(builder: (_) => SubtitlecatScreen(url: uri)));
  }

  @override
  Widget build(BuildContext context) => CupertinoPageScaffold(
    navigationBar: CupertinoNavigationBar(
      middle: const Text('在线字幕'),
      leading: CupertinoButton(
        padding: EdgeInsets.zero,
        onPressed: () => Navigator.pop(context),
        child: const Text('关闭'),
      ),
    ),
    child: SafeArea(
      child: Column(
        children: [
          Padding(
            padding: const EdgeInsets.all(12),
            child: Row(
              children: [
                Expanded(
                  child: CupertinoTextField(
                    controller: queryController,
                    placeholder: '关键词',
                    textCapitalization: TextCapitalization.characters,
                    onSubmitted: (_) => _search(),
                  ),
                ),
                const SizedBox(width: 10),
                CupertinoButton.filled(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 15,
                    vertical: 8,
                  ),
                  onPressed: searching ? null : _search,
                  child: const Text('字幕搜索'),
                ),
              ],
            ),
          ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12),
            child: Row(
              children: [
                CupertinoButton(
                  padding: EdgeInsets.zero,
                  onPressed: _openSubtitlecat,
                  child: const Text('打开 Subtitlecat'),
                ),
                if (searching) ...[
                  const SizedBox(width: 12),
                  const CupertinoActivityIndicator(),
                ],
                const Spacer(),
              ],
            ),
          ),
          if (status.isNotEmpty)
            Padding(
              padding: const EdgeInsets.fromLTRB(12, 0, 12, 8),
              child: Align(
                alignment: Alignment.centerLeft,
                child: Text(
                  status,
                  style: const TextStyle(
                    color: CupertinoColors.secondaryLabel,
                    fontSize: 13,
                  ),
                ),
              ),
            ),
          Expanded(
            child: results.isEmpty
                ? const Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      Icon(
                        CupertinoIcons.captions_bubble,
                        color: CupertinoColors.secondaryLabel,
                        size: 44,
                      ),
                      SizedBox(height: 10),
                      Text(
                        '暂无字幕结果',
                        style: TextStyle(
                          fontWeight: FontWeight.w600,
                          fontSize: 17,
                        ),
                      ),
                      SizedBox(height: 6),
                      Text(
                        '可先在线搜索，或打开 Subtitlecat 自行下载后导入字幕文件。',
                        style: TextStyle(
                          color: CupertinoColors.secondaryLabel,
                          fontSize: 13,
                        ),
                      ),
                    ],
                  )
                : ListView.separated(
                    itemCount: results.length,
                    separatorBuilder: (_, __) =>
                        const Divider(height: .5, indent: 16),
                    itemBuilder: (context, index) {
                      final item = results[index];
                      return CupertinoButton(
                        alignment: Alignment.centerLeft,
                        padding: const EdgeInsets.symmetric(
                          horizontal: 16,
                          vertical: 10,
                        ),
                        onPressed: () => _load(item),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              item.displayName,
                              style: const TextStyle(
                                color: CupertinoColors.label,
                              ),
                            ),
                            const SizedBox(height: 4),
                            Text(
                              item.url,
                              maxLines: 2,
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(
                                color: CupertinoColors.secondaryLabel,
                                fontSize: 12,
                              ),
                            ),
                          ],
                        ),
                      );
                    },
                  ),
          ),
        ],
      ),
    ),
  );
}

class SubtitlecatScreen extends StatelessWidget {
  const SubtitlecatScreen({super.key, required this.url});
  final Uri url;

  @override
  Widget build(BuildContext context) => CupertinoPageScaffold(
    navigationBar: const CupertinoNavigationBar(middle: Text('Subtitlecat')),
    child: SafeArea(
      child: InAppWebView(
        initialUrlRequest: URLRequest(url: WebUri.uri(url)),
        initialSettings: InAppWebViewSettings(allowsInlineMediaPlayback: true),
      ),
    ),
  );
}

class _SubtitleResult {
  const _SubtitleResult(this.text, this.label);
  final String text;
  final String label;
}

String _formatTime(Duration value) {
  final total = math.max(0, value.inSeconds);
  final seconds = total % 60;
  final minutes = (total ~/ 60) % 60;
  final hours = total ~/ 3600;
  if (hours > 0) {
    return '${hours.toString().padLeft(2, '0')}:'
        '${minutes.toString().padLeft(2, '0')}:'
        '${seconds.toString().padLeft(2, '0')}';
  }
  return '${minutes.toString().padLeft(2, '0')}:'
      '${seconds.toString().padLeft(2, '0')}';
}
