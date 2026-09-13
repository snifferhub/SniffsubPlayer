enum MediaKind {
  hls('HLS'),
  mp4('MP4'),
  dash('DASH'),
  segment('Segment'),
  blob('Blob'),
  other('Other');

  const MediaKind(this.label);
  final String label;

  static MediaKind classify(String url) {
    final lower = url.toLowerCase();
    if (lower.startsWith('blob:')) return MediaKind.blob;
    if (lower.contains('.m3u8')) return MediaKind.hls;
    if (lower.contains('.mp4') ||
        lower.contains('.m4v') ||
        lower.contains('.mov')) {
      return MediaKind.mp4;
    }
    if (lower.contains('.mpd')) return MediaKind.dash;
    if (lower.contains('.m4s') ||
        lower.contains('.ts') ||
        lower.contains('.aac')) {
      return MediaKind.segment;
    }
    return MediaKind.other;
  }
}

class MediaCandidate {
  MediaCandidate({
    required this.url,
    required this.source,
    required this.pageUrl,
    required this.referer,
    required this.userAgent,
    DateTime? detectedAt,
  }) : kind = MediaKind.classify(url),
       detectedAt = detectedAt ?? DateTime.now();

  final String url;
  final MediaKind kind;
  final String source;
  final String pageUrl;
  final String referer;
  final String userAgent;
  final DateTime detectedAt;
}
