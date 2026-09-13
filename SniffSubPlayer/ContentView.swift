import SwiftUI
import UIKit
import WebKit

struct ContentView: View {
    private enum Route: Hashable {
        case settings
        case bookmarks
        case adBlock
    }

    @StateObject private var store = BrowserStore()
    @StateObject private var adBlockManager = AdBlockManager()
    @State private var navigationPath = NavigationPath()
    @State private var webView: WKWebView?
    @State private var selectedCandidate: MediaCandidate?
    @State private var showBookmarkHUD = false
    @State private var bookmarkHUDText = ""
    @State private var bookmarkHUDTask: Task<Void, Never>?

    var body: some View {
        NavigationStack(path: $navigationPath) {
            VStack(spacing: 0) {
                addressBar
                browserProgressBar
                BrowserView(store: store, adBlockManager: adBlockManager) { webView = $0 }
                candidatePanel
            }
            .navigationTitle(store.title.isEmpty ? "SniffSubPlayer" : store.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        bookmarkCurrentPage()
                    } label: {
                        Image(systemName: store.isBookmarked(url: currentPageURL) ? "bookmark.fill" : "bookmark")
                    }
                    .disabled(currentPageURL.isEmpty)
                    .accessibilityLabel("收藏当前网页")
                }

                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        navigationPath.append(Route.settings)
                    } label: {
                        Image(systemName: "gearshape")
                    }
                    .accessibilityLabel("设置")
                }
            }
            .fullScreenCover(item: $selectedCandidate) { candidate in
                PlayerView(candidate: candidate)
            }
            .overlay {
                if showBookmarkHUD {
                    BookmarkSavedHUD(text: bookmarkHUDText)
                        .transition(.scale(scale: 0.94).combined(with: .opacity))
                }
            }
            .onDisappear {
                bookmarkHUDTask?.cancel()
            }
            .navigationDestination(for: Route.self) { route in
                switch route {
                case .settings:
                    SettingsView(
                        store: store,
                        adBlockManager: adBlockManager,
                        showBookmarks: { navigationPath.append(Route.bookmarks) },
                        showAdBlock: { navigationPath.append(Route.adBlock) }
                    )
                case .bookmarks:
                    BookmarkListView(store: store) { bookmark in
                        store.address = bookmark.url
                        BrowserView.load(bookmark.url, in: webView)
                        navigationPath.removeLast(navigationPath.count)
                    }
                case .adBlock:
                    AdBlockSettingsView(manager: adBlockManager)
                }
            }
        }
        .task {
            await adBlockManager.refreshIfNeeded()
        }
    }

    private var currentPageURL: String {
        (webView?.url?.absoluteString ?? store.address).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func bookmarkCurrentPage() {
        let isNowBookmarked = store.toggleBookmark(title: webView?.title, url: currentPageURL)
        bookmarkHUDText = isNowBookmarked ? "已收藏" : "已取消收藏"
        UINotificationFeedbackGenerator().notificationOccurred(.success)

        bookmarkHUDTask?.cancel()
        withAnimation(.spring(response: 0.22, dampingFraction: 0.86)) {
            showBookmarkHUD = true
        }

        bookmarkHUDTask = Task {
            try? await Task.sleep(nanoseconds: 1_250_000_000)
            guard !Task.isCancelled else { return }
            await MainActor.run {
                withAnimation(.easeOut(duration: 0.18)) {
                    showBookmarkHUD = false
                }
            }
        }
    }

    private var addressBar: some View {
        HStack(spacing: 8) {
            Button {
                webView?.goBack()
            } label: {
                Image(systemName: "chevron.left")
            }
            .disabled(!(webView?.canGoBack ?? false))

            Button {
                webView?.goForward()
            } label: {
                Image(systemName: "chevron.right")
            }
            .disabled(!(webView?.canGoForward ?? false))

            TextField("网址或搜索", text: $store.address)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.URL)
                .textFieldStyle(.roundedBorder)
                .onSubmit { BrowserView.load(store.address, in: webView) }

            Button {
                BrowserView.load(store.address, in: webView)
            } label: {
                Image(systemName: "arrow.clockwise")
            }
        }
        .padding(10)
        .background(.bar)
    }

    private var browserProgressBar: some View {
        ProgressView(value: store.loadProgress, total: 1)
            .progressViewStyle(.linear)
            .tint(PlayerAccentColor.color(from: store.playerAccentColorID))
            .opacity(store.isLoading ? 1 : 0)
            .frame(height: 2)
            .clipped()
            .accessibilityLabel("网页加载进度")
    }

    private var candidatePanel: some View {
        VStack(spacing: 0) {
            HStack {
                Text("嗅探到 \(store.candidates.count) 个媒体资源")
                    .font(.headline)
                Spacer()
                if store.isLoading {
                    ProgressView()
                }
                Button {
                    BrowserView.enableSniffer(in: webView) { succeeded in
                        Task { @MainActor in
                            store.isSnifferEnabled = succeeded
                        }
                    }
                } label: {
                    Label(
                        store.isSnifferEnabled ? "已启用" : "启用嗅探",
                        systemImage: store.isSnifferEnabled ? "waveform.badge.checkmark" : "waveform"
                    )
                    .font(.subheadline)
                }
                .disabled(store.isLoading || currentPageURL.isEmpty || store.isSnifferEnabled)
                Button {
                    store.clearCandidates()
                } label: {
                    Image(systemName: "trash")
                }
                .disabled(store.candidates.isEmpty)
                .accessibilityLabel("清空媒体候选")
            }
            .padding(.horizontal)
            .padding(.vertical, 8)

            if store.candidates.isEmpty {
                Text(store.isSnifferEnabled
                     ? "嗅探已启用，播放视频后会收集 m3u8 / mp4 / blob / 分片候选"
                     : "页面加载完成后，点击“启用嗅探”再播放视频")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal)
                    .padding(.bottom, 10)
            } else {
                List(store.candidates.prefix(30)) { candidate in
                    Button {
                        selectedCandidate = candidate
                    } label: {
                        CandidateRow(candidate: candidate)
                    }
                    .buttonStyle(.plain)
                }
                .listStyle(.plain)
                .frame(height: 210)
            }
        }
        .background(.regularMaterial)
    }
}

struct SettingsView: View {
    @ObservedObject var store: BrowserStore
    @ObservedObject var adBlockManager: AdBlockManager
    let showBookmarks: () -> Void
    let showAdBlock: () -> Void

    var body: some View {
        Form {
            Section {
                Picker("User Agent", selection: userAgentSelection) {
                    ForEach(BrowserStore.userAgentOptions) { option in
                        Text(option.name).tag(option.id)
                    }
                }
            } header: {
                Text("浏览器")
            } footer: {
                Text("设备原生 UA 与当前系统 WebKit 保持一致，兼容性最好。切换 UA 后刷新页面生效。")
            }

            Section {
                ColorPicker("进度条颜色", selection: playerColorSelection, supportsOpacity: false)

                Button("恢复默认") {
                    store.playerAccentColorID = PlayerAccentColor.defaultValue
                }
            } header: {
                Text("播放器")
            } footer: {
                Text("点击颜色可使用系统色轮自由调节，颜色同时用于进度条和字幕调节滑杆。")
            }

            Section("收藏夹") {
                Button(action: showBookmarks) {
                    HStack {
                        Label("收藏夹", systemImage: "bookmark")
                        Spacer()
                        Text("\(store.bookmarks.count)")
                            .foregroundStyle(.secondary)
                    }
                }
                .foregroundStyle(.primary)
            }

            Section("网页内容") {
                Button(action: showAdBlock) {
                    HStack {
                        Label("广告屏蔽", systemImage: "shield.lefthalf.filled")
                        Spacer()
                        Text(adBlockManager.isEnabled ? "已开启" : "已关闭")
                            .foregroundStyle(.secondary)
                    }
                }
                .foregroundStyle(.primary)
            }
        }
        .navigationTitle("设置")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var userAgentSelection: Binding<String> {
        Binding(
            get: { store.selectedUserAgentOptionID },
            set: { store.selectedUserAgentOptionID = $0 }
        )
    }

    private var playerColorSelection: Binding<Color> {
        Binding(
            get: { PlayerAccentColor.color(from: store.playerAccentColorID) },
            set: { store.playerAccentColorID = PlayerAccentColor.storageValue(from: $0) }
        )
    }
}

struct AdBlockSettingsView: View {
    @ObservedObject var manager: AdBlockManager
    @State private var newSubscriptionURL = ""
    @State private var validationMessage = ""

    var body: some View {
        Form {
            Section {
                Toggle("广告屏蔽", isOn: enabledBinding)
                Toggle("每天自动更新", isOn: automaticUpdatesBinding)
            } footer: {
                Text("规则只应用于 App 内的网页。开关变化后，当前网页会自动刷新。")
            }

            Section {
                TextField("https://…/filter.txt", text: $newSubscriptionURL, axis: .vertical)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.URL)

                Button {
                    addSubscription()
                } label: {
                    Label("添加订阅", systemImage: "plus")
                }
                .disabled(newSubscriptionURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)

                if !validationMessage.isEmpty {
                    Text(validationMessage)
                        .font(.footnote)
                        .foregroundStyle(.red)
                }

                ForEach(manager.subscriptions) { subscription in
                    HStack(spacing: 12) {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(subscription.name)
                                .foregroundStyle(.primary)
                            Text(subscription.urlString)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(2)
                        }
                        Spacer()
                        Toggle("", isOn: subscriptionEnabledBinding(subscription.id))
                            .labelsHidden()
                    }
                }
                .onDelete(perform: manager.removeSubscriptions)
            } header: {
                Text("规则订阅")
            } footer: {
                Text("请粘贴 AdGuard/EasyList 文本规则的 HTTPS raw 链接，而不是 GitHub 文件预览页面。左滑可以删除订阅。")
            }

            Section {
                Button {
                    Task { await manager.updateRules() }
                } label: {
                    HStack {
                        Label("立即更新规则", systemImage: "arrow.triangle.2.circlepath")
                        Spacer()
                        if manager.isUpdating {
                            ProgressView()
                        }
                    }
                }
                .disabled(manager.isUpdating || manager.enabledSubscriptionsCount == 0)

                LabeledContent("状态", value: manager.statusText)

                if let lastCheckedAt = manager.lastCheckedAt {
                    LabeledContent("上次检查", value: formatted(lastCheckedAt))
                }

                if let lastUpdatedAt = manager.lastUpdatedAt {
                    LabeledContent("上次生效", value: formatted(lastUpdatedAt))
                }

                if manager.sourceRulesCount > 0 {
                    LabeledContent("源规则", value: "\(manager.sourceRulesCount)")
                    LabeledContent("WebKit 规则", value: "\(manager.safariRulesCount)")
                }

                if manager.conversionErrorsCount > 0 {
                    LabeledContent("转换失败", value: "\(manager.conversionErrorsCount)")
                }

                if manager.discardedRulesCount > 0 {
                    LabeledContent("超限丢弃", value: "\(manager.discardedRulesCount)")
                }

                if manager.configurationNeedsUpdate {
                    Label("订阅配置已变化，请更新规则后生效", systemImage: "exclamationmark.circle")
                        .font(.footnote)
                        .foregroundStyle(.orange)
                }
            } header: {
                Text("规则状态")
            } footer: {
                Text("下载、转换或编译失败时会继续使用上一次成功的规则。")
            }
        }
        .navigationTitle("广告屏蔽")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var enabledBinding: Binding<Bool> {
        Binding(
            get: { manager.isEnabled },
            set: { manager.setEnabled($0) }
        )
    }

    private var automaticUpdatesBinding: Binding<Bool> {
        Binding(
            get: { manager.automaticUpdatesEnabled },
            set: { manager.setAutomaticUpdatesEnabled($0) }
        )
    }

    private func subscriptionEnabledBinding(_ id: UUID) -> Binding<Bool> {
        Binding(
            get: { manager.subscriptions.first(where: { $0.id == id })?.isEnabled ?? false },
            set: { manager.setSubscriptionEnabled(id: id, enabled: $0) }
        )
    }

    private func addSubscription() {
        do {
            try manager.addSubscription(urlString: newSubscriptionURL)
            newSubscriptionURL = ""
            validationMessage = ""
        } catch {
            validationMessage = error.localizedDescription
        }
    }

    private func formatted(_ date: Date) -> String {
        date.formatted(date: .abbreviated, time: .shortened)
    }
}

struct BookmarkListView: View {
    @ObservedObject var store: BrowserStore
    let openBookmark: (Bookmark) -> Void

    var body: some View {
        List {
            if store.bookmarks.isEmpty {
                Text("暂无收藏")
                    .foregroundStyle(.secondary)
            } else {
                ForEach(store.bookmarks) { bookmark in
                    Button {
                        openBookmark(bookmark)
                    } label: {
                        BookmarkRow(bookmark: bookmark)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .contentShape(Rectangle())
                    }
                    .tint(.primary)
                }
                .onDelete(perform: store.removeBookmarks)
            }
        }
        .navigationTitle("收藏夹")
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct BookmarkSavedHUD: View {
    let text: String

    var body: some View {
        VStack(spacing: 10) {
            Text(text)
                .font(.headline)
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 30)
        .padding(.vertical, 22)
        .background(.gray.opacity(0.88), in: RoundedRectangle(cornerRadius: 24, style: .continuous))
        .shadow(color: .black.opacity(0.22), radius: 18, y: 8)
        .allowsHitTesting(false)
    }
}

struct BookmarkRow: View {
    let bookmark: Bookmark

    var body: some View {
        Label {
            VStack(alignment: .leading, spacing: 4) {
                Text(bookmark.title)
                    .font(.body)
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                Text(bookmark.url)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
        } icon: {
            Image(systemName: "bookmark")
                .foregroundStyle(.blue)
        }
    }
}

struct CandidateRow: View {
    let candidate: MediaCandidate

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack {
                Text(candidate.kind.rawValue)
                    .font(.caption.bold())
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(kindColor.opacity(0.16), in: Capsule())
                    .foregroundStyle(kindColor)

                Text(candidate.source)
                    .font(.caption)
                    .foregroundStyle(.secondary)

                Spacer()
            }

            Text(candidate.url)
                .font(.caption)
                .lineLimit(2)
                .textSelection(.enabled)
        }
        .padding(.vertical, 4)
    }

    private var kindColor: Color {
        switch candidate.kind {
        case .hls: .green
        case .mp4: .blue
        case .dash: .purple
        case .segment: .orange
        case .blob: .red
        case .other: .gray
        }
    }
}
