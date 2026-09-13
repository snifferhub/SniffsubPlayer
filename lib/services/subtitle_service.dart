import 'dart:convert';

import 'package:http/http.dart' as http;

import '../models/subtitle.dart';

class SubtitleParser {
  static List<SubtitleCue> parse(String rawText, {double offsetSeconds = 0}) {
    final normalized = rawText
        .replaceAll('\ufeff', '')
        .replaceAll('\r', '')
        .replaceFirst(RegExp(r'^WEBVTT[^\n]*\n+', caseSensitive: false), '');
    final blocks = normalized.split(RegExp(r'\n{2,}'));
    final cues = <SubtitleCue>[];

    for (final block in blocks) {
      final lines = block
          .split('\n')
          .map((line) => line.trim())
          .where((line) => line.isNotEmpty)
          .toList();
      final timeIndex = lines.indexWhere((line) => line.contains('-->'));
      if (timeIndex < 0) continue;
      final parts = lines[timeIndex].split('-->');
      if (parts.length < 2) continue;
      final start = _timestamp(parts[0].trim());
      final end = _timestamp(parts[1].trim().split(RegExp(r'\s+')).first);
      if (start == null || end == null || end <= start) continue;
      final text = lines
          .skip(timeIndex + 1)
          .join('\n')
          .replaceAll(RegExp(r'<[^>]+>'), '')
          .trim();
      if (text.isEmpty) continue;
      final offset = Duration(milliseconds: (offsetSeconds * 1000).round());
      final shiftedStart = start + offset;
      final shiftedEnd = end + offset;
      cues.add(
        SubtitleCue(
          start: shiftedStart.isNegative ? Duration.zero : shiftedStart,
          end: shiftedEnd.isNegative ? Duration.zero : shiftedEnd,
          text: text,
        ),
      );
    }
    cues.sort((a, b) => a.start.compareTo(b.start));
    return cues;
  }

  static Duration? _timestamp(String value) {
    final pieces = value.replaceAll(',', '.').split(':');
    if (pieces.length < 2) return null;
    final seconds = double.tryParse(pieces.removeLast());
    final minutes = int.tryParse(pieces.removeLast());
    final hours = pieces.isEmpty ? 0 : int.tryParse(pieces.last);
    if (seconds == null || minutes == null || hours == null) return null;
    return Duration(
      milliseconds: ((hours * 3600 + minutes * 60 + seconds) * 1000).round(),
    );
  }
}

class VideoIdExtractor {
  static String extract(Iterable<String> values) {
    final patterns = <RegExp>[
      RegExp(r'([a-z]{2,6})[-_]?(\d{2,5})', caseSensitive: false),
      RegExp(r'/([a-z0-9]+)-([a-z0-9]+)(?:[/?#]|$)', caseSensitive: false),
    ];
    for (final value in values) {
      for (final pattern in patterns) {
        final match = pattern.firstMatch(value);
        if (match != null && match.groupCount >= 2) {
          return '${match.group(1)!.toUpperCase()}-${match.group(2)!}';
        }
      }
    }
    return '';
  }
}

class SubtitleService {
  const SubtitleService({http.Client? client}) : _client = client;
  final http.Client? _client;

  Future<List<OnlineSubtitle>> search(String query) async {
    final client = _client ?? http.Client();
    try {
      final uri = Uri.https('api-shoulei-ssl.xunlei.com', '/oracle/subtitle', {
        'name': query,
      });
      final response = await client.get(
        uri,
        headers: {'Accept': 'application/json, text/plain, */*'},
      );
      if (response.statusCode < 200 || response.statusCode >= 300) {
        throw Exception('HTTP ${response.statusCode}');
      }
      final body = jsonDecode(utf8.decode(response.bodyBytes));
      final data = body is Map<String, dynamic> ? body['data'] : null;
      if (data is! List) return [];
      return data
          .whereType<Map>()
          .map(
            (item) => OnlineSubtitle.fromJson(Map<String, dynamic>.from(item)),
          )
          .where((item) {
            final lower = item.url.toLowerCase();
            return lower.contains('.srt') || lower.contains('.vtt');
          })
          .toList();
    } finally {
      if (_client == null) client.close();
    }
  }

  Future<String> download(String url) async {
    final client = _client ?? http.Client();
    try {
      final response = await client.get(
        Uri.parse(url),
        headers: {'Accept': 'text/plain,*/*'},
      );
      if (response.statusCode < 200 || response.statusCode >= 300) {
        throw Exception('HTTP ${response.statusCode}');
      }
      return utf8.decode(response.bodyBytes, allowMalformed: true);
    } finally {
      if (_client == null) client.close();
    }
  }
}
