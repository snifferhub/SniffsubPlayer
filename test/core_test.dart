import 'package:flutter_test/flutter_test.dart';
import 'package:sniff_sub_player/models/media_candidate.dart';
import 'package:sniff_sub_player/services/subtitle_service.dart';
import 'package:sniff_sub_player/state/browser_state.dart';

void main() {
  group('media classification', () {
    test('matches the supported media resource categories', () {
      expect(MediaKind.classify('https://cdn.test/master.m3u8'), MediaKind.hls);
      expect(
        MediaKind.classify('https://cdn.test/video.mp4?q=1'),
        MediaKind.mp4,
      );
      expect(
        MediaKind.classify('https://cdn.test/manifest.mpd'),
        MediaKind.dash,
      );
      expect(
        MediaKind.classify('https://cdn.test/chunk.m4s'),
        MediaKind.segment,
      );
      expect(MediaKind.classify('blob:https://test/123'), MediaKind.blob);
    });
  });

  group('address normalization', () {
    test('keeps URLs, adds https, and turns words into a search', () {
      expect(
        BrowserState.normalizeAddress('https://example.com')?.toString(),
        'https://example.com',
      );
      expect(
        BrowserState.normalizeAddress('example.com')?.toString(),
        'https://example.com',
      );
      expect(
        BrowserState.normalizeAddress('测试 视频')?.queryParameters['q'],
        '测试 视频',
      );
    });
  });

  group('subtitles', () {
    test('parses SRT and applies timing offset', () {
      const source = '''
1
00:00:01,000 --> 00:00:03,500
<b>第一行</b>

2
00:00:04.000 --> 00:00:06.000
Second line
''';
      final cues = SubtitleParser.parse(source, offsetSeconds: .5);
      expect(cues, hasLength(2));
      expect(cues.first.start, const Duration(milliseconds: 1500));
      expect(cues.first.end, const Duration(milliseconds: 4000));
      expect(cues.first.text, '第一行');
    });

    test('extracts the video id used as subtitle search query', () {
      expect(
        VideoIdExtractor.extract(['https://example.com/watch/abc-123']),
        'ABC-123',
      );
    });
  });
}
