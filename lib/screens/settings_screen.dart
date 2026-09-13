import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart' show Divider;

import '../models/bookmark.dart';
import '../services/ad_block_service.dart';
import '../state/browser_state.dart';

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key, required this.state, required this.adBlock});

  final BrowserState state;
  final AdBlockService adBlock;

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  @override
  void initState() {
    super.initState();
    widget.state.addListener(_changed);
    widget.adBlock.addListener(_changed);
  }

  @override
  void dispose() {
    widget.state.removeListener(_changed);
    widget.adBlock.removeListener(_changed);
    super.dispose();
  }

  void _changed() {
    if (mounted) setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    final selectedUserAgent = BrowserState.userAgentOptions.firstWhere(
      (option) => option.value == widget.state.userAgent,
      orElse: () => BrowserState.userAgentOptions.first,
    );
    return CupertinoPageScaffold(
      navigationBar: const CupertinoNavigationBar(middle: Text('设置')),
      child: SafeArea(
        child: ListView(
          padding: const EdgeInsets.only(bottom: 36),
          children: [
            _Section(
              title: '浏览器',
              footer: '设备原生 UA 与当前系统 WebView 保持一致，兼容性最好。切换 UA 后刷新页面生效。',
              children: [
                _SettingsRow(
                  label: 'User Agent',
                  value: selectedUserAgent.name,
                  onTap: _pickUserAgent,
                ),
              ],
            ),
            _Section(
              title: '播放器',
              footer: '颜色同时用于网页加载进度、播放进度和字幕调节滑杆。',
              children: [
                _SettingsRow(
                  label: '进度条颜色',
                  trailing: Container(
                    width: 28,
                    height: 28,
                    decoration: BoxDecoration(
                      color: Color(widget.state.accentColorValue),
                      shape: BoxShape.circle,
                      border: Border.all(
                        color: CupertinoColors.separator,
                        width: .5,
                      ),
                    ),
                  ),
                  onTap: _pickAccentColor,
                ),
                _SettingsRow(
                  label: '恢复默认',
                  labelColor: CupertinoColors.activeBlue,
                  onTap: () =>
                      widget.state.setAccentColor(BrowserState.defaultAccent),
                ),
              ],
            ),
            _Section(
              title: '收藏夹',
              children: [
                _SettingsRow(
                  label: '收藏夹',
                  icon: CupertinoIcons.bookmark,
                  value: '${widget.state.bookmarks.length}',
                  onTap: _openBookmarks,
                ),
              ],
            ),
            _Section(
              title: '网页内容',
              children: [
                _SettingsRow(
                  label: '广告屏蔽',
                  icon: CupertinoIcons.shield_lefthalf_fill,
                  value: widget.adBlock.isEnabled ? '已开启' : '已关闭',
                  onTap: () => Navigator.of(context).push(
                    CupertinoPageRoute(
                      builder: (_) =>
                          AdBlockSettingsScreen(service: widget.adBlock),
                    ),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  void _pickUserAgent() {
    showCupertinoModalPopup<void>(
      context: context,
      builder: (context) => CupertinoActionSheet(
        title: const Text('User Agent'),
        message: const Text('切换后返回浏览器刷新页面生效'),
        actions: BrowserState.userAgentOptions
            .map(
              (option) => CupertinoActionSheetAction(
                isDefaultAction: widget.state.userAgent == option.value,
                onPressed: () {
                  widget.state.setUserAgent(option.value);
                  Navigator.pop(context);
                },
                child: Text(option.name),
              ),
            )
            .toList(),
        cancelButton: CupertinoActionSheetAction(
          onPressed: () => Navigator.pop(context),
          child: const Text('取消'),
        ),
      ),
    );
  }

  Future<void> _openBookmarks() async {
    final bookmark = await Navigator.of(context).push<Bookmark>(
      CupertinoPageRoute(
        builder: (_) => BookmarkListScreen(state: widget.state),
      ),
    );
    if (bookmark != null && mounted) Navigator.pop(context, bookmark.url);
  }

  void _pickAccentColor() {
    showCupertinoModalPopup<void>(
      context: context,
      builder: (context) => _ColorSheet(
        selected: widget.state.accentColorValue,
        onSelected: widget.state.setAccentColor,
      ),
    );
  }
}

class BookmarkListScreen extends StatefulWidget {
  const BookmarkListScreen({super.key, required this.state});
  final BrowserState state;

  @override
  State<BookmarkListScreen> createState() => _BookmarkListScreenState();
}

class _BookmarkListScreenState extends State<BookmarkListScreen> {
  @override
  void initState() {
    super.initState();
    widget.state.addListener(_changed);
  }

  @override
  void dispose() {
    widget.state.removeListener(_changed);
    super.dispose();
  }

  void _changed() {
    if (mounted) setState(() {});
  }

  @override
  Widget build(BuildContext context) => CupertinoPageScaffold(
    navigationBar: const CupertinoNavigationBar(middle: Text('收藏夹')),
    child: SafeArea(
      child: widget.state.bookmarks.isEmpty
          ? const Center(
              child: Text(
                '暂无收藏',
                style: TextStyle(color: CupertinoColors.secondaryLabel),
              ),
            )
          : ListView.separated(
              itemCount: widget.state.bookmarks.length,
              separatorBuilder: (_, __) =>
                  const Divider(height: .5, indent: 58),
              itemBuilder: (context, index) {
                final bookmark = widget.state.bookmarks[index];
                return Dismissible(
                  key: ValueKey(bookmark.url),
                  direction: DismissDirection.endToStart,
                  background: Container(
                    color: CupertinoColors.systemRed,
                    alignment: Alignment.centerRight,
                    padding: const EdgeInsets.only(right: 24),
                    child: const Icon(
                      CupertinoIcons.delete,
                      color: CupertinoColors.white,
                    ),
                  ),
                  onDismissed: (_) => widget.state.removeBookmark(bookmark),
                  child: CupertinoButton(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 16,
                      vertical: 10,
                    ),
                    onPressed: () => Navigator.pop(context, bookmark),
                    child: Row(
                      children: [
                        const Icon(CupertinoIcons.bookmark, size: 22),
                        const SizedBox(width: 14),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                bookmark.title,
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: const TextStyle(
                                  color: CupertinoColors.label,
                                  fontSize: 16,
                                ),
                              ),
                              const SizedBox(height: 4),
                              Text(
                                bookmark.url,
                                maxLines: 2,
                                overflow: TextOverflow.ellipsis,
                                style: const TextStyle(
                                  color: CupertinoColors.secondaryLabel,
                                  fontSize: 12,
                                ),
                              ),
                            ],
                          ),
                        ),
                      ],
                    ),
                  ),
                );
              },
            ),
    ),
  );
}

class AdBlockSettingsScreen extends StatefulWidget {
  const AdBlockSettingsScreen({super.key, required this.service});
  final AdBlockService service;

  @override
  State<AdBlockSettingsScreen> createState() => _AdBlockSettingsScreenState();
}

class _AdBlockSettingsScreenState extends State<AdBlockSettingsScreen> {
  String? validationMessage;

  @override
  void initState() {
    super.initState();
    widget.service.addListener(_changed);
  }

  @override
  void dispose() {
    widget.service.removeListener(_changed);
    super.dispose();
  }

  void _changed() {
    if (mounted) setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    final service = widget.service;
    return CupertinoPageScaffold(
      navigationBar: const CupertinoNavigationBar(middle: Text('广告屏蔽')),
      child: SafeArea(
        child: ListView(
          padding: const EdgeInsets.only(bottom: 36),
          children: [
            _Section(
              footer: '规则只应用于 App 内的网页。开关变化后返回网页刷新生效。',
              children: [
                _SwitchRow(
                  label: '广告屏蔽',
                  value: service.isEnabled,
                  onChanged: service.setEnabled,
                ),
                _SwitchRow(
                  label: '每天自动更新',
                  value: service.automaticUpdatesEnabled,
                  onChanged: service.setAutomaticUpdatesEnabled,
                ),
              ],
            ),
            _Section(
              title: '规则订阅',
              footer:
                  '已内置 GitHub 上的 AdGuard 与 EasyList China 规则。'
                  '内置规则可以关闭，手动添加的订阅可向左滑删除。',
              children: [
                _SettingsRow(
                  label: '添加订阅',
                  icon: CupertinoIcons.add,
                  labelColor: CupertinoColors.activeBlue,
                  onTap: _addSubscription,
                ),
                if (validationMessage != null)
                  Padding(
                    padding: const EdgeInsets.all(12),
                    child: Text(
                      validationMessage!,
                      style: const TextStyle(
                        color: CupertinoColors.systemRed,
                        fontSize: 13,
                      ),
                    ),
                  ),
                ...service.subscriptions.map(
                  (subscription) => Dismissible(
                    key: ValueKey(subscription.id),
                    direction: subscription.isBuiltIn
                        ? DismissDirection.none
                        : DismissDirection.endToStart,
                    background: Container(color: CupertinoColors.systemRed),
                    onDismissed: (_) =>
                        service.removeSubscription(subscription.id),
                    child: Padding(
                      padding: const EdgeInsets.fromLTRB(16, 10, 12, 10),
                      child: Row(
                        children: [
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Row(
                                  children: [
                                    Flexible(child: Text(subscription.name)),
                                    if (subscription.isBuiltIn) ...[
                                      const SizedBox(width: 7),
                                      Container(
                                        padding: const EdgeInsets.symmetric(
                                          horizontal: 6,
                                          vertical: 2,
                                        ),
                                        decoration: BoxDecoration(
                                          color: CupertinoColors.systemGrey5,
                                          borderRadius: BorderRadius.circular(
                                            5,
                                          ),
                                        ),
                                        child: const Text(
                                          '内置',
                                          style: TextStyle(
                                            color:
                                                CupertinoColors.secondaryLabel,
                                            fontSize: 11,
                                          ),
                                        ),
                                      ),
                                    ],
                                  ],
                                ),
                                const SizedBox(height: 4),
                                Text(
                                  subscription.url,
                                  maxLines: 2,
                                  overflow: TextOverflow.ellipsis,
                                  style: const TextStyle(
                                    color: CupertinoColors.secondaryLabel,
                                    fontSize: 12,
                                  ),
                                ),
                              ],
                            ),
                          ),
                          CupertinoSwitch(
                            value: subscription.isEnabled,
                            onChanged: (value) => service
                                .setSubscriptionEnabled(subscription.id, value),
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ],
            ),
            _Section(
              title: '规则状态',
              footer: '下载或转换失败时会继续保留上一次成功的规则。',
              children: [
                _SettingsRow(
                  label: '立即更新规则',
                  icon: CupertinoIcons.refresh_thick,
                  labelColor: CupertinoColors.activeBlue,
                  trailing: service.isUpdating
                      ? const CupertinoActivityIndicator()
                      : null,
                  onTap:
                      service.isUpdating ||
                          service.enabledSubscriptionsCount == 0
                      ? null
                      : service.updateRules,
                ),
                _SettingsRow(label: '状态', value: service.statusText),
                if (service.lastCheckedAt != null)
                  _SettingsRow(
                    label: '上次检查',
                    value: _formatDate(service.lastCheckedAt!),
                  ),
                if (service.lastUpdatedAt != null)
                  _SettingsRow(
                    label: '上次生效',
                    value: _formatDate(service.lastUpdatedAt!),
                  ),
                if (service.sourceRulesCount > 0)
                  _SettingsRow(
                    label: '源规则',
                    value: '${service.sourceRulesCount}',
                  ),
                if (service.convertedRulesCount > 0)
                  _SettingsRow(
                    label: '跨平台规则',
                    value: '${service.convertedRulesCount}',
                  ),
                if (service.discardedRulesCount > 0)
                  _SettingsRow(
                    label: '跳过或超限',
                    value: '${service.discardedRulesCount}',
                  ),
                if (service.configurationNeedsUpdate)
                  const Padding(
                    padding: EdgeInsets.all(12),
                    child: Text(
                      '订阅配置已变化，请更新规则后生效',
                      style: TextStyle(
                        color: CupertinoColors.systemOrange,
                        fontSize: 13,
                      ),
                    ),
                  ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _addSubscription() async {
    final controller = TextEditingController();
    final value = await showCupertinoDialog<String>(
      context: context,
      builder: (context) => CupertinoAlertDialog(
        title: const Text('添加规则订阅'),
        content: Padding(
          padding: const EdgeInsets.only(top: 12),
          child: CupertinoTextField(
            controller: controller,
            placeholder: 'https://…/filter.txt',
            keyboardType: TextInputType.url,
            minLines: 1,
            maxLines: 3,
          ),
        ),
        actions: [
          CupertinoDialogAction(
            onPressed: () => Navigator.pop(context),
            child: const Text('取消'),
          ),
          CupertinoDialogAction(
            isDefaultAction: true,
            onPressed: () => Navigator.pop(context, controller.text),
            child: const Text('添加'),
          ),
        ],
      ),
    );
    controller.dispose();
    if (value == null || value.trim().isEmpty) return;
    try {
      await widget.service.addSubscription(value);
      setState(() => validationMessage = null);
    } catch (error) {
      setState(
        () => validationMessage = error.toString().replaceFirst(
          'FormatException: ',
          '',
        ),
      );
    }
  }

  String _formatDate(DateTime date) =>
      '${date.year}-${date.month.toString().padLeft(2, '0')}-'
      '${date.day.toString().padLeft(2, '0')} '
      '${date.hour.toString().padLeft(2, '0')}:'
      '${date.minute.toString().padLeft(2, '0')}';
}

class _ColorSheet extends StatelessWidget {
  const _ColorSheet({required this.selected, required this.onSelected});
  final int selected;
  final ValueChanged<int> onSelected;

  static const colors = <int>[
    0xFFE8A7BF,
    0xFFFF2D55,
    0xFFFF9500,
    0xFFFFCC00,
    0xFF34C759,
    0xFF00C7BE,
    0xFF007AFF,
    0xFF5856D6,
    0xFFAF52DE,
    0xFFFFFFFF,
    0xFF8E8E93,
    0xFF1C1C1E,
  ];

  @override
  Widget build(BuildContext context) => CupertinoPopupSurface(
    child: SafeArea(
      top: false,
      child: Padding(
        padding: const EdgeInsets.all(22),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Text(
              '进度条颜色',
              style: TextStyle(fontSize: 18, fontWeight: FontWeight.w600),
            ),
            const SizedBox(height: 20),
            Wrap(
              spacing: 16,
              runSpacing: 16,
              children: colors.map((value) {
                final isSelected = selected == value;
                return CupertinoButton(
                  minimumSize: const Size.square(44),
                  padding: EdgeInsets.zero,
                  onPressed: () {
                    onSelected(value);
                    Navigator.pop(context);
                  },
                  child: Container(
                    width: 44,
                    height: 44,
                    decoration: BoxDecoration(
                      color: Color(value),
                      shape: BoxShape.circle,
                      border: Border.all(
                        color: isSelected
                            ? CupertinoColors.activeBlue
                            : CupertinoColors.separator,
                        width: isSelected ? 3 : 1,
                      ),
                    ),
                  ),
                );
              }).toList(),
            ),
            const SizedBox(height: 18),
            CupertinoButton(
              onPressed: () => Navigator.pop(context),
              child: const Text('取消'),
            ),
          ],
        ),
      ),
    ),
  );
}

class _Section extends StatelessWidget {
  const _Section({required this.children, this.title, this.footer});
  final String? title;
  final String? footer;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.fromLTRB(16, 22, 16, 0),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (title != null)
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 0, 7),
            child: Text(
              title!.toUpperCase(),
              style: const TextStyle(
                color: CupertinoColors.secondaryLabel,
                fontSize: 13,
              ),
            ),
          ),
        ClipRRect(
          borderRadius: BorderRadius.circular(10),
          child: Container(
            color: CupertinoDynamicColor.resolve(
              CupertinoColors.secondarySystemGroupedBackground,
              context,
            ),
            child: Column(
              children: [
                for (var index = 0; index < children.length; index++) ...[
                  children[index],
                  if (index != children.length - 1)
                    const Divider(height: .5, indent: 16),
                ],
              ],
            ),
          ),
        ),
        if (footer != null)
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 7, 12, 0),
            child: Text(
              footer!,
              style: const TextStyle(
                color: CupertinoColors.secondaryLabel,
                fontSize: 13,
                height: 1.25,
              ),
            ),
          ),
      ],
    ),
  );
}

class _SettingsRow extends StatelessWidget {
  const _SettingsRow({
    required this.label,
    this.icon,
    this.value,
    this.trailing,
    this.onTap,
    this.labelColor,
  });
  final String label;
  final IconData? icon;
  final String? value;
  final Widget? trailing;
  final VoidCallback? onTap;
  final Color? labelColor;

  @override
  Widget build(BuildContext context) => CupertinoButton(
    padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 11),
    onPressed: onTap,
    child: Row(
      children: [
        if (icon != null) ...[Icon(icon, size: 21), const SizedBox(width: 12)],
        Expanded(
          child: Text(
            label,
            style: TextStyle(
              color: labelColor ?? CupertinoColors.label,
              fontSize: 16,
            ),
          ),
        ),
        if (value != null)
          Flexible(
            child: Text(
              value!,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              textAlign: TextAlign.end,
              style: const TextStyle(
                color: CupertinoColors.secondaryLabel,
                fontSize: 15,
              ),
            ),
          ),
        if (trailing != null) trailing!,
        if (onTap != null) ...[
          const SizedBox(width: 7),
          const Icon(
            CupertinoIcons.chevron_forward,
            size: 16,
            color: CupertinoColors.tertiaryLabel,
          ),
        ],
      ],
    ),
  );
}

class _SwitchRow extends StatelessWidget {
  const _SwitchRow({
    required this.label,
    required this.value,
    required this.onChanged,
  });
  final String label;
  final bool value;
  final ValueChanged<bool> onChanged;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
    child: Row(
      children: [
        Expanded(child: Text(label, style: const TextStyle(fontSize: 16))),
        CupertinoSwitch(value: value, onChanged: onChanged),
      ],
    ),
  );
}
