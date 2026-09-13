class Bookmark {
  const Bookmark({
    required this.title,
    required this.url,
    required this.createdAt,
  });

  final String title;
  final String url;
  final DateTime createdAt;

  factory Bookmark.fromJson(Map<String, dynamic> json) => Bookmark(
    title: json['title'] as String? ?? '',
    url: json['url'] as String? ?? '',
    createdAt:
        DateTime.tryParse(json['createdAt'] as String? ?? '') ?? DateTime.now(),
  );

  Map<String, dynamic> toJson() => {
    'title': title,
    'url': url,
    'createdAt': createdAt.toIso8601String(),
  };
}
