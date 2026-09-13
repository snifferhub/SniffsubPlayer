import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter_inappwebview/flutter_inappwebview.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';
import 'package:uuid/uuid.dart';

class AdBlockSubscription {
  const AdBlockSubscription({
    required this.id,
    required this.name,
    required this.url,
    this.isEnabled = true,
    this.isBuiltIn = false,
  });

  final String id;
  final String name;
  final String url;
  final bool isEnabled;
  final bool isBuiltIn;

  AdBlockSubscription copyWith({bool? isEnabled}) => AdBlockSubscription(
    id: id,
    name: name,
    url: url,
    isEnabled: isEnabled ?? this.isEnabled,
    isBuiltIn: isBuiltIn,
  );

  factory AdBlockSubscription.fromJson(Map<String, dynamic> json) =>
      AdBlockSubscription(
        id: json['id'] as String? ?? const Uuid().v4(),
        name: json['name'] as String? ?? '规则订阅',
        url: json['url'] as String? ?? '',
        isEnabled: json['isEnabled'] as bool? ?? true,
        isBuiltIn: json['isBuiltIn'] as bool? ?? false,
      );

  Map<String, dynamic> toJson() => {
    'id': id,
    'name': name,
    'url': url,
    'isEnabled': isEnabled,
    'isBuiltIn': isBuiltIn,
  };
}

class AdBlockService extends ChangeNotifier {
  static const int _maximumRules = 15000;
  static const int _maximumDownloadSize = 12 * 1024 * 1024;
  static const Duration _downloadTimeout = Duration(seconds: 45);

  static const List<AdBlockSubscription> builtInSubscriptions = [
    AdBlockSubscription(
      id: 'builtin-adguard-adservers',
      name: 'AdGuard 通用广告服务器',
      url:
          'https://raw.githubusercontent.com/AdguardTeam/AdguardFilters/'
          'master/BaseFilter/sections/adservers.txt',
      isBuiltIn: true,
    ),
    AdBlockSubscription(
      id: 'builtin-easylist-china',
      name: 'EasyList China 中文规则',
      url:
          'https://raw.githubusercontent.com/easylist/easylistchina/'
          'master/easylistchina.txt',
      isBuiltIn: true,
    ),
    AdBlockSubscription(
      id: 'builtin-adguard-tracking',
      name: 'AdGuard 隐私追踪防护',
      url:
          'https://raw.githubusercontent.com/AdguardTeam/AdguardFilters/'
          'master/SpywareFilter/sections/tracking_servers.txt',
      isBuiltIn: true,
    ),
  ];

  bool isEnabled = false;
  bool automaticUpdatesEnabled = true;
  bool isUpdating = false;
  bool configurationNeedsUpdate = false;
  DateTime? lastCheckedAt;
  DateTime? lastUpdatedAt;
  int sourceRulesCount = 0;
  int convertedRulesCount = 0;
  int discardedRulesCount = 0;
  String? errorMessage;
  final List<AdBlockSubscription> _subscriptions = [];
  final List<ContentBlocker> _contentBlockers = [];

  List<AdBlockSubscription> get subscriptions =>
      List.unmodifiable(_subscriptions);
  List<ContentBlocker> get contentBlockers =>
      isEnabled ? List.unmodifiable(_contentBlockers) : const [];
  int get enabledSubscriptionsCount =>
      _subscriptions.where((item) => item.isEnabled).length;
  String get statusText {
    if (isUpdating) return '正在下载并转换规则…';
    if (errorMessage != null) return errorMessage!;
    if (_contentBlockers.isEmpty) return '尚未生成规则';
    return isEnabled ? '已应用 $convertedRulesCount 条跨平台规则' : '规则已就绪（当前关闭）';
  }

  Future<void> restore() async {
    final preferences = await SharedPreferences.getInstance();
    isEnabled = preferences.getBool('adblock.enabled') ?? false;
    automaticUpdatesEnabled = preferences.getBool('adblock.autoUpdate') ?? true;
    final raw = preferences.getString('adblock.subscriptions');
    final restored = <AdBlockSubscription>[];
    if (raw != null) {
      try {
        final decoded = jsonDecode(raw) as List<dynamic>;
        restored.addAll(
          decoded.whereType<Map>().map(
            (item) =>
                AdBlockSubscription.fromJson(Map<String, dynamic>.from(item)),
          ),
        );
      } catch (_) {}
    }
    _subscriptions
      ..clear()
      ..addAll(_mergeBuiltInSubscriptions(restored));
    await _saveSubscriptions();
    notifyListeners();
    if (isEnabled && automaticUpdatesEnabled && _subscriptions.isNotEmpty) {
      await updateRules();
    }
  }

  Future<void> setEnabled(bool value) async {
    isEnabled = value;
    final preferences = await SharedPreferences.getInstance();
    await preferences.setBool('adblock.enabled', value);
    notifyListeners();
    if (value && _contentBlockers.isEmpty && enabledSubscriptionsCount > 0) {
      await updateRules();
    }
  }

  Future<void> setAutomaticUpdatesEnabled(bool value) async {
    automaticUpdatesEnabled = value;
    final preferences = await SharedPreferences.getInstance();
    await preferences.setBool('adblock.autoUpdate', value);
    notifyListeners();
  }

  Future<void> addSubscription(String rawUrl) async {
    final uri = Uri.tryParse(rawUrl.trim());
    if (uri == null || uri.scheme != 'https' || uri.host.isEmpty) {
      throw const FormatException('请输入有效的 HTTPS raw 规则链接');
    }
    if (_subscriptions.any((item) => item.url == uri.toString())) {
      throw const FormatException('该订阅已经存在');
    }
    _subscriptions.add(
      AdBlockSubscription(
        id: const Uuid().v4(),
        name: uri.host.replaceFirst('raw.', ''),
        url: uri.toString(),
      ),
    );
    configurationNeedsUpdate = true;
    await _saveSubscriptions();
    notifyListeners();
  }

  Future<void> setSubscriptionEnabled(String id, bool value) async {
    final index = _subscriptions.indexWhere((item) => item.id == id);
    if (index < 0) return;
    _subscriptions[index] = _subscriptions[index].copyWith(isEnabled: value);
    configurationNeedsUpdate = true;
    await _saveSubscriptions();
    notifyListeners();
  }

  Future<void> removeSubscription(String id) async {
    _subscriptions.removeWhere((item) => item.id == id && !item.isBuiltIn);
    configurationNeedsUpdate = true;
    await _saveSubscriptions();
    notifyListeners();
  }

  Future<void> updateRules() async {
    final enabled = _subscriptions.where((item) => item.isEnabled).toList();
    if (enabled.isEmpty || isUpdating) return;
    isUpdating = true;
    errorMessage = null;
    notifyListeners();
    final client = http.Client();
    try {
      final allLines = <String>[];
      for (final subscription in enabled) {
        final response = await client
            .get(
              Uri.parse(subscription.url),
              headers: {'User-Agent': 'SniffSubPlayer/1.0 Flutter'},
            )
            .timeout(_downloadTimeout);
        if (response.statusCode < 200 || response.statusCode >= 300) {
          throw Exception('${subscription.name}: HTTP ${response.statusCode}');
        }
        if (response.bodyBytes.length > _maximumDownloadSize) {
          throw Exception('${subscription.name}: 规则文件超过 12 MB');
        }
        allLines.addAll(
          utf8.decode(response.bodyBytes, allowMalformed: true).split('\n'),
        );
      }
      final converted = _convert(allLines);
      _contentBlockers
        ..clear()
        ..addAll(converted);
      sourceRulesCount = allLines.length;
      convertedRulesCount = converted.length;
      lastCheckedAt = DateTime.now();
      lastUpdatedAt = lastCheckedAt;
      configurationNeedsUpdate = false;
    } catch (error) {
      errorMessage = '规则更新失败: $error';
      lastCheckedAt = DateTime.now();
    } finally {
      client.close();
      isUpdating = false;
      notifyListeners();
    }
  }

  List<ContentBlocker> _convert(List<String> lines) {
    final blockers = <ContentBlocker>[];
    final seenFilters = <String>{};
    var discarded = 0;
    for (final raw in lines) {
      if (blockers.length >= _maximumRules) {
        discarded++;
        continue;
      }
      final line = raw.trim();
      if (line.isEmpty || line.startsWith('!') || line.startsWith('[')) {
        continue;
      }
      if (line.startsWith('@@') ||
          line.contains('##') ||
          line.contains('#@#') ||
          line.contains(r'$')) {
        discarded++;
        continue;
      }
      final regex = _ruleToRegex(line);
      if (regex == null || !seenFilters.add(regex)) {
        discarded++;
        continue;
      }
      blockers.add(
        ContentBlocker(
          trigger: ContentBlockerTrigger(urlFilter: regex),
          action: ContentBlockerAction(type: ContentBlockerActionType.BLOCK),
        ),
      );
    }
    discardedRulesCount = discarded;
    return blockers;
  }

  String? _ruleToRegex(String rule) {
    if (rule.length > 500) return null;
    if (rule.startsWith('/') && rule.endsWith('/') && rule.length > 2) {
      return rule.substring(1, rule.length - 1);
    }
    if (rule.startsWith('||')) {
      final domain = rule
          .substring(2)
          .split('^')
          .first
          .replaceAll(RegExp(r'[^a-zA-Z0-9._-]'), '');
      if (domain.isEmpty) return null;
      final escaped = RegExp.escape(domain);
      return '^https?://([^/]+\\.)?$escaped([/:]|\$)';
    }
    final normalized = rule.startsWith('|') ? rule.substring(1) : rule;
    final escaped = RegExp.escape(
      normalized,
    ).replaceAll(r'\*', '.*').replaceAll(r'\^', r'[^a-zA-Z0-9_.%-]');
    return escaped.isEmpty ? null : escaped;
  }

  Future<void> _saveSubscriptions() async {
    final preferences = await SharedPreferences.getInstance();
    await preferences.setString(
      'adblock.subscriptions',
      jsonEncode(_subscriptions.map((item) => item.toJson()).toList()),
    );
  }

  List<AdBlockSubscription> _mergeBuiltInSubscriptions(
    List<AdBlockSubscription> restored,
  ) {
    final merged = <AdBlockSubscription>[];
    for (final builtIn in builtInSubscriptions) {
      final saved = restored.cast<AdBlockSubscription?>().firstWhere(
        (item) =>
            item != null &&
            (item.id == builtIn.id ||
                item.url.toLowerCase() == builtIn.url.toLowerCase()),
        orElse: () => null,
      );
      merged.add(builtIn.copyWith(isEnabled: saved?.isEnabled));
    }
    merged.addAll(
      restored.where(
        (item) => !builtInSubscriptions.any(
          (builtIn) =>
              builtIn.id == item.id ||
              builtIn.url.toLowerCase() == item.url.toLowerCase(),
        ),
      ),
    );
    return merged;
  }
}
