class SubtitleCue {
  const SubtitleCue({
    required this.start,
    required this.end,
    required this.text,
  });

  final Duration start;
  final Duration end;
  final String text;
}

class OnlineSubtitle {
  const OnlineSubtitle({required this.name, required this.url, this.extraName});

  final String name;
  final String? extraName;
  final String url;

  String get displayName =>
      extraName == null || extraName!.isEmpty ? name : '$name ($extraName)';

  factory OnlineSubtitle.fromJson(Map<String, dynamic> json) => OnlineSubtitle(
    name: json['name'] as String? ?? '',
    extraName: json['extra_name'] as String?,
    url: json['url'] as String? ?? '',
  );
}
