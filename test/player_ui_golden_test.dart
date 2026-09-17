import 'package:flutter/cupertino.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sniff_sub_player/screens/player_screen.dart';

void main() {
  testWidgets('landscape player chrome matches the expected composition', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(844, 390);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(
      CupertinoApp(
        debugShowCheckedModeBanner: false,
        home: CupertinoPageScaffold(
          backgroundColor: CupertinoColors.black,
          child: PlayerControlsOverlay(
            accentColor: const Color(0xFFE8A7BF),
            status: '已载入 128 条字幕 (demo.srt)',
            hasSubtitles: true,
            isPlaying: true,
            position: const Duration(minutes: 12, seconds: 34),
            duration: const Duration(hours: 1, minutes: 42, seconds: 8),
            onClose: () {},
            onSubtitle: () {},
            onPlayPause: () {},
            onBackward: () {},
            onForward: () {},
            onSeekStart: () {},
            onSeek: (_) {},
            onSeekEnd: (_) {},
          ),
        ),
      ),
    );

    final overlay = tester.getRect(find.byType(PlayerControlsOverlay));
    final subtitle = tester.getRect(
      find.byKey(const Key('player_subtitle_button')),
    );
    expect(subtitle.size, const Size(44, 38));
    expect(subtitle.right, overlay.right - 22);
    expect(subtitle.top, overlay.top + 14);

    await expectLater(
      find.byType(PlayerControlsOverlay),
      matchesGoldenFile('goldens/player_controls.png'),
    );
  });
}
