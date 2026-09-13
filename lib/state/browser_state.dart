import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../models/bookmark.dart';
import '../models/media_candidate.dart';

class UserAgentOption {
  const UserAgentOption(this.id, this.name, this.value);
  final String id;
  final String name;
  final String value;
}

class BrowserState extends ChangeNotifier {
  static const defaultAccent = 0xFFE8A7BF;
  static const iPadSafariUserAgent =
      'Mozilla/5.0 (iPad; CPU OS 17_0 like Mac OS X) '
      'AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 '
      'Mobile/15E148 Safari/604.1';
  static const macSafariUserAgent =
      'Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) '
      'AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15';
  static const userAgentOptions = <UserAgentOption>[
    UserAgentOption('device-default', '设备原生（推荐）', ''),
    UserAgentOption('ipad-safari', 'iPad Safari', iPadSafariUserAgent),
    UserAgentOption('macos-safari', 'macOS Safari', macSafariUserAgent),
  ];

  String address = '';
  String title = '';
  String currentPageUrl = '';
  bool isLoading = false;
  double loadProgress = 0;
  bool isSnifferEnabled = false;
  String userAgent = '';
  int accentColorValue = defaultAccent;
  final List<MediaCandidate> _candidates = [];
  final List<Bookmark> _bookmarks = [];

  List<MediaCandidate> get candidates => List.unmodifiable(_candidates);
  List<Bookmark> get bookmarks => List.unmodifiable(_bookmarks);

  Future<void> restore() async {
    final preferences = await SharedPreferences.getInstance();
    final savedUserAgent = preferences.getString('browser.userAgent') ?? '';
    if (userAgentOptions.any((option) => option.value == savedUserAgent)) {
      userAgent = savedUserAgent;
    }
    accentColorValue =
        preferences.getInt('player.accentColor') ?? defaultAccent;
    final rawBookmarks = preferences.getString('browser.bookmarks');
    if (rawBookmarks != null) {
      try {
        final decoded = jsonDecode(rawBookmarks) as List<dynamic>;
        _bookmarks
          ..clear()
          ..addAll(
            decoded.whereType<Map>().map(
              (item) => Bookmark.fromJson(Map<String, dynamic>.from(item)),
            ),
          );
      } catch (_) {
        // A corrupt preference must not prevent the browser from opening.
      }
    }
    notifyListeners();
  }

  void updateLoading({required bool loading, double? progress}) {
    isLoading = loading;
    if (progress != null) loadProgress = progress.clamp(0, 1);
    notifyListeners();
  }

  void updateProgress(double progress) {
    loadProgress = progress.clamp(0, 1);
    notifyListeners();
  }

  void updatePage({String? url, String? pageTitle}) {
    if (url != null && url.isNotEmpty) {
      currentPageUrl = url;
      address = url;
    }
    if (pageTitle != null) title = pageTitle;
    notifyListeners();
  }

  void setSnifferEnabled(bool enabled) {
    isSnifferEnabled = enabled;
    notifyListeners();
  }

  void setUserAgent(String value) {
    userAgent = value;
    notifyListeners();
    SharedPreferences.getInstance().then(
      (preferences) => preferences.setString('browser.userAgent', value),
    );
  }

  void setAccentColor(int value) {
    accentColorValue = value;
    notifyListeners();
    SharedPreferences.getInstance().then(
      (preferences) => preferences.setInt('player.accentColor', value),
    );
  }

  void addCandidate({
    required String url,
    required String source,
    String? pageUrl,
    String? detectedUserAgent,
  }) {
    final normalized = url.trim();
    if (normalized.isEmpty || !_isLikelyMediaUrl(normalized)) return;
    if (_candidates.any((candidate) => candidate.url == normalized)) return;
    final resolvedPage = (pageUrl?.isNotEmpty ?? false)
        ? pageUrl!
        : currentPageUrl;
    _candidates.insert(
      0,
      MediaCandidate(
        url: normalized,
        source: source,
        pageUrl: resolvedPage,
        referer: resolvedPage,
        userAgent: (detectedUserAgent?.isNotEmpty ?? false)
            ? detectedUserAgent!
            : userAgent,
      ),
    );
    notifyListeners();
  }

  void clearCandidates() {
    _candidates.clear();
    notifyListeners();
  }

  bool isBookmarked(String url) =>
      url.trim().isNotEmpty && _bookmarks.any((item) => item.url == url.trim());

  bool toggleBookmark({required String url, String? pageTitle}) {
    final normalized = url.trim();
    if (normalized.isEmpty) return false;
    if (_bookmarks.any((item) => item.url == normalized)) {
      _bookmarks.removeWhere((item) => item.url == normalized);
      _saveBookmarks();
      notifyListeners();
      return false;
    }
    final normalizedTitle = (pageTitle ?? title).trim();
    _bookmarks.insert(
      0,
      Bookmark(
        title: normalizedTitle.isEmpty ? normalized : normalizedTitle,
        url: normalized,
        createdAt: DateTime.now(),
      ),
    );
    _saveBookmarks();
    notifyListeners();
    return true;
  }

  void removeBookmark(Bookmark bookmark) {
    _bookmarks.removeWhere((item) => item.url == bookmark.url);
    _saveBookmarks();
    notifyListeners();
  }

  Future<void> _saveBookmarks() async {
    final preferences = await SharedPreferences.getInstance();
    await preferences.setString(
      'browser.bookmarks',
      jsonEncode(_bookmarks.map((bookmark) => bookmark.toJson()).toList()),
    );
  }

  static Uri? normalizeAddress(String input) {
    final value = input.trim();
    if (value.isEmpty) return null;
    if (value.contains('://')) return Uri.tryParse(value);
    if (value.contains('.') && !value.contains(' ')) {
      return Uri.tryParse('https://$value');
    }
    return Uri.https('www.google.com', '/search', {'q': value});
  }

  bool _isLikelyMediaUrl(String url) {
    final lower = url.toLowerCase();
    if (lower.startsWith('blob:')) return true;
    return const [
      '.m3u8',
      '.mp4',
      '.m4v',
      '.mov',
      '.mpd',
      '.m4s',
      '.ts',
      '.aac',
      'application/vnd.apple.mpegurl',
      'video/mp4',
    ].any(lower.contains);
  }
}
