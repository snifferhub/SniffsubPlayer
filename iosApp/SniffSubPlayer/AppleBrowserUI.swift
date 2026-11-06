import ComposeApp
import SwiftUI
import WebKit

/// Observes shared Kotlin state; all Apple controls and navigation are rendered by SwiftUI.
@MainActor
final class AppleBrowserModel: ObservableObject {
    let host: IosBrowserHost
    @Published private(set) var state: BrowserUiState
    @Published private(set) var tabs: BrowserTabsState
    @Published private(set) var preferences: AppPreferences
    @Published var errorMessage: String?
    private weak var adBlockManager: AdBlockManager?
    private let callbacks = BrowserCallbacks()

    init(adBlockManager: AdBlockManager) {
        let host = IosBrowserHost()
        self.host = host
        state = host.currentState()
        tabs = host.currentTabs()
        preferences = host.currentPreferences()
        self.adBlockManager = adBlockManager
        callbacks.owner = self
        host.start(callbacks: callbacks)
    }

    deinit { host.close() }

    var currentURL: String { state.currentPageUrl.isEmpty ? state.address : state.currentPageUrl }
    var isBookmarked: Bool { preferences.bookmarks.contains { $0.url == currentURL } }

    @MainActor
    private final class BrowserCallbacks: NSObject, @preconcurrency IosBrowserCallbacks {
        weak var owner: AppleBrowserModel?

        func stateChanged(state: BrowserUiState, tabs: BrowserTabsState, preferences: AppPreferences) {
            owner?.state = state
            owner?.tabs = tabs
            owner?.preferences = preferences
        }

        func reportError(message: String) { owner?.errorMessage = message }

        func configureWebView(view: WKWebView, onReady: @escaping () -> Void) {
            guard let manager = owner?.adBlockManager else { onReady(); return }
            manager.applyRules(to: view.configuration.userContentController, completion: onReady)
        }
    }
}

private struct NativeBrowserView: UIViewControllerRepresentable {
    let host: IosBrowserHost

    func makeUIViewController(context: Context) -> UIViewController { host.createViewController() }
    func updateUIViewController(_ controller: UIViewController, context: Context) {}
    // The model retains the workspace across tab switches and modal presentations.
}

struct AppleBrowserScreen: View {
    @ObservedObject var model: AppleBrowserModel
    let onOpenCandidate: (ComposeApp.MediaCandidate) -> Void
    @State private var address = ""
    @State private var sheet: BrowserSheet?
    @State private var pendingCandidate: ComposeApp.MediaCandidate?
    @FocusState private var addressFocused: Bool

    private enum BrowserSheet: String, Identifiable {
        case tabs, bookmarks
        var id: String { rawValue }
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                addressBar
                ZStack {
                    NativeBrowserView(host: model.host)
                    if model.currentURL.isEmpty { startPage }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            .background(Color(uiColor: .systemBackground))
            .navigationTitle("")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { browserToolbar }
            .overlay(alignment: .top) {
                if let message = model.state.transientMessage {
                    Text(message)
                        .font(.callout)
                        .padding(.horizontal, 16).padding(.vertical, 10)
                        .background(.regularMaterial, in: Capsule())
                        .padding(.top, 68)
                        .allowsHitTesting(false)
                        .task(id: model.state.transientMessageRevision) {
                            do { try await Task.sleep(nanoseconds: 2_000_000_000) }
                            catch { return }
                            model.host.clearMessage()
                        }
                }
            }
            .onAppear { if !addressFocused { address = model.currentURL } }
            .onDisappear { addressFocused = false }
            .onChange(of: model.currentURL) { value in
                if !addressFocused { address = value }
            }
            .onChange(of: addressFocused) { focused in
                if focused { address = model.currentURL }
            }
            .sheet(item: $sheet) { destination in
                switch destination {
                case .tabs: tabList
                case .bookmarks:
                    AppleBookmarksScreen(model: model, isModal: true) { bookmark in
                        sheet = nil
                        model.host.loadAddress(address: bookmark.url)
                    }
                }
            }
            .sheet(isPresented: Binding(
                get: { model.state.candidateSheetVisible },
                set: { if !$0 { model.host.dismissCandidates() } }
            ), onDismiss: {
                if let candidate = pendingCandidate {
                    pendingCandidate = nil
                    onOpenCandidate(candidate)
                }
            }) {
                candidateList
                    .presentationDetents([.medium, .large])
                    .presentationDragIndicator(.visible)
            }
        }
    }

    private var addressBar: some View {
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                Image(systemName: "magnifyingglass")
                    .foregroundStyle(.secondary)
                TextField("搜索或输入网址", text: $address)
                    .keyboardType(.webSearch)
                    .textContentType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.go)
                    .focused($addressFocused)
                    .onSubmit(submitAddress)
                    .accessibilityLabel("网址或搜索关键词")
                if addressFocused && !address.isEmpty {
                    Button { address = "" } label: { Image(systemName: "xmark.circle.fill") }
                        .foregroundStyle(.secondary)
                        .accessibilityLabel("清空地址")
                } else if !model.currentURL.isEmpty {
                    Button {
                        if model.state.loading { model.host.stopLoading() }
                        else { model.host.reload() }
                    } label: {
                        Image(systemName: model.state.loading ? "xmark" : "arrow.clockwise")
                    }
                    .foregroundStyle(.primary)
                    .accessibilityLabel(model.state.loading ? "停止加载" : "重新加载")
                }
            }
            .padding(.horizontal, 12).padding(.vertical, 11)
            .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 12))
            .padding(.horizontal, 16).padding(.vertical, 8)
            if model.state.loading {
                ProgressView(value: model.state.progress)
                    .tint(.secondary)
                    .accessibilityLabel("网页加载进度")
            } else {
                Divider()
            }
        }
        .background(Color(uiColor: .systemGroupedBackground))
    }

    @ToolbarContentBuilder
    private var browserToolbar: some ToolbarContent {
        ToolbarItemGroup(placement: .navigationBarLeading) {
            Button { addressFocused = false; model.host.goBack() } label: {
                Image(systemName: "chevron.backward")
            }
            .disabled(!model.state.canGoBack)
            .accessibilityLabel("后退")
            Button { addressFocused = false; model.host.goForward() } label: {
                Image(systemName: "chevron.forward")
            }
            .disabled(!model.state.canGoForward)
            .accessibilityLabel("前进")
        }
        ToolbarItemGroup(placement: .navigationBarTrailing) {
            Button {
                addressFocused = false
                model.host.requestSniff()
            } label: {
                Image(systemName: "waveform.path")
            }
            .disabled(model.currentURL.isEmpty || model.state.loading)
            .accessibilityLabel("嗅探媒体")
            Button { addressFocused = false; sheet = .tabs } label: {
                Image(systemName: "square.on.square")
            }
            .accessibilityLabel("标签页，\(model.tabs.tabs.count) 个")
            Menu {
                Button { model.host.toggleBookmark() } label: {
                    Label(model.isBookmarked ? "取消收藏" : "收藏网页",
                          systemImage: model.isBookmarked ? "bookmark.slash" : "bookmark")
                }
                .disabled(model.currentURL.isEmpty)
                Button { sheet = .bookmarks } label: { Label("收藏夹", systemImage: "book") }
                if let url = URL(string: model.currentURL), !model.currentURL.isEmpty {
                    ShareLink(item: url) { Label("分享", systemImage: "square.and.arrow.up") }
                }
                Button { model.host.addTab() } label: { Label("新建标签页", systemImage: "plus") }
            } label: {
                Image(systemName: "ellipsis.circle")
            }
            .accessibilityLabel("更多")
        }
    }

    private var startPage: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                Text("收藏夹")
                    .font(.largeTitle.bold())
                    .foregroundStyle(.primary)
                if model.preferences.bookmarks.isEmpty {
                    Image(systemName: "book")
                        .font(.system(size: 40, weight: .light))
                        .foregroundStyle(.tertiary)
                        .frame(maxWidth: .infinity, minHeight: 160)
                        .accessibilityLabel("暂无收藏")
                } else {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 100, maximum: 160))], spacing: 24) {
                        ForEach(model.preferences.bookmarks, id: \.id) { bookmark in
                            Button { model.host.loadAddress(address: bookmark.url) } label: {
                                VStack(spacing: 10) {
                                    Image(systemName: "globe")
                                        .font(.title2)
                                        .frame(width: 60, height: 60)
                                        .background(Color(uiColor: .tertiarySystemFill), in: RoundedRectangle(cornerRadius: 14))
                                    Text(bookmark.title).font(.subheadline).lineLimit(2)
                                }
                                .frame(maxWidth: .infinity)
                            }
                            .buttonStyle(.plain)
                            .contextMenu {
                                Button(role: .destructive) { model.host.removeBookmark(id: bookmark.id) } label: {
                                    Label("删除收藏", systemImage: "trash")
                                }
                            }
                        }
                    }
                }
            }
            .frame(maxWidth: 820, alignment: .leading)
            .padding(24)
            .frame(maxWidth: .infinity)
        }
        .background(Color(uiColor: .systemBackground))
    }

    private var tabList: some View {
        NavigationStack {
            List {
                ForEach(model.tabs.tabs, id: \.id) { tab in
                    Button {
                        model.host.selectTab(id: tab.id)
                        sheet = nil
                    } label: {
                        HStack {
                            Image(systemName: "globe").foregroundStyle(.secondary)
                            Text(tab.title.isEmpty ? (tab.url.isEmpty ? "新标签页" : tab.url) : tab.title)
                                .lineLimit(2).foregroundStyle(.primary)
                            Spacer()
                            if tab.id == model.tabs.selectedId {
                                Image(systemName: "checkmark").accessibilityLabel("当前标签页")
                            }
                        }
                        .padding(.vertical, 6)
                    }
                }
                .onDelete { offsets in
                    let ids = offsets.map { model.tabs.tabs[$0].id }
                    ids.forEach { model.host.closeTab(id: $0) }
                }
            }
            .navigationTitle("标签页")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("完成") { sheet = nil } }
                ToolbarItem(placement: .primaryAction) {
                    Button { model.host.addTab(); sheet = nil } label: { Image(systemName: "plus") }
                        .disabled(model.tabs.tabs.count >= 12)
                        .accessibilityLabel("新建标签页")
                }
            }
        }
    }

    private var candidateList: some View {
        NavigationStack {
            List {
                if model.state.snifferEnabled {
                    HStack { ProgressView(); Text("正在嗅探") }
                }
                if model.state.candidates.isEmpty && !model.state.snifferEnabled {
                    Text("暂无媒体资源").foregroundStyle(.secondary)
                }
                ForEach(model.state.candidates, id: \.url) { candidate in
                    Button {
                        pendingCandidate = candidate
                        model.host.dismissCandidates()
                    } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "play.circle").font(.title2)
                            Text(candidate.url).font(.subheadline).lineLimit(3).foregroundStyle(.primary)
                        }
                        .padding(.vertical, 4)
                    }
                    .contextMenu {
                        ShareLink(item: candidate.url) { Label("分享链接", systemImage: "square.and.arrow.up") }
                    }
                }
            }
            .navigationTitle("媒体资源")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("完成") { model.host.dismissCandidates() }
                }
                ToolbarItem(placement: .primaryAction) {
                    Button("清空", role: .destructive) { model.host.clearCandidates() }
                        .disabled(model.state.candidates.isEmpty)
                }
            }
        }
    }

    private func submitAddress() {
        guard !address.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        addressFocused = false
        model.host.loadAddress(address: address)
    }
}

struct AppleSettingsScreen: View {
    @ObservedObject var model: AppleBrowserModel
    @ObservedObject var adBlockManager: AdBlockManager
    let onOpenBookmark: (ComposeApp.Bookmark) -> Void

    var body: some View {
        NavigationStack {
            Form {
                Section("浏览器") {
                    NavigationLink("User Agent") { userAgentSettings }
                    NavigationLink {
                        AppleBookmarksScreen(model: model, isModal: false, onOpen: onOpenBookmark)
                    } label: { Label("收藏夹", systemImage: "book") }
                    NavigationLink {
                        AppleAdBlockScreen(manager: adBlockManager)
                    } label: { Label("广告屏蔽", systemImage: "hand.raised") }
                }
                Section("播放器") {
                    ColorPicker("进度条颜色", selection: playerColor, supportsOpacity: false)
                    Button("恢复默认颜色") { model.host.resetPlayerAccent() }
                }
            }
            .navigationTitle("设置")
        }
    }

    private var userAgentSettings: some View {
        List {
            ForEach(model.host.userAgentOptions(), id: \.id) { option in
                Button { model.host.setUserAgent(value: option.value) } label: {
                    HStack {
                        Text(option.name).foregroundStyle(.primary)
                        Spacer()
                        if model.preferences.userAgent == option.value {
                            Image(systemName: "checkmark")
                        }
                    }
                }
                .accessibilityAddTraits(model.preferences.userAgent == option.value ? .isSelected : [])
            }
        }
        .navigationTitle("User Agent")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var playerColor: Binding<Color> {
        Binding(get: {
            let rgb = model.preferences.accentArgb
            return Color(red: Double((rgb >> 16) & 255) / 255,
                         green: Double((rgb >> 8) & 255) / 255,
                         blue: Double(rgb & 255) / 255)
        }, set: { color in
            var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, alpha: CGFloat = 0
            guard UIColor(color).getRed(&red, green: &green, blue: &blue, alpha: &alpha) else { return }
            let rgb = (Int64((red * 255).rounded()) << 16) |
                (Int64((green * 255).rounded()) << 8) | Int64((blue * 255).rounded())
            model.host.setPlayerAccent(value: rgb)
        })
    }
}

struct AppleBookmarksScreen: View {
    @ObservedObject var model: AppleBrowserModel
    let isModal: Bool
    let onOpen: (ComposeApp.Bookmark) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        if isModal {
            NavigationStack { bookmarkList }
        } else {
            bookmarkList
        }
    }

    private var bookmarkList: some View {
        List {
            if model.preferences.bookmarks.isEmpty {
                Text("暂无收藏").foregroundStyle(.secondary)
            }
            ForEach(model.preferences.bookmarks, id: \.id) { bookmark in
                Button {
                    onOpen(bookmark)
                    if !isModal { dismiss() }
                } label: {
                    Label(bookmark.title, systemImage: "globe").foregroundStyle(.primary)
                }
            }
            .onDelete { offsets in
                let ids = offsets.map { model.preferences.bookmarks[$0].id }
                ids.forEach { model.host.removeBookmark(id: $0) }
            }
        }
        .navigationTitle("收藏夹")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if isModal {
                ToolbarItem(placement: .cancellationAction) { Button("完成") { dismiss() } }
            }
            ToolbarItem(placement: .primaryAction) { EditButton().disabled(model.preferences.bookmarks.isEmpty) }
        }
    }
}

private struct AppleAdBlockScreen: View {
    @ObservedObject var manager: AdBlockManager
    @State private var addingSubscription = false
    @State private var subscriptionURL = ""
    @State private var errorMessage: String?

    var body: some View {
        Form {
            Section {
                Toggle("广告屏蔽", isOn: Binding(get: { manager.isEnabled }, set: manager.setEnabled))
                Toggle("自动更新", isOn: Binding(get: { manager.automaticUpdatesEnabled }, set: manager.setAutomaticUpdatesEnabled))
            }
            Section("订阅") {
                ForEach(manager.subscriptions) { subscription in
                    Toggle(subscription.name, isOn: Binding(
                        get: { manager.subscriptions.first { $0.id == subscription.id }?.isEnabled ?? false },
                        set: { manager.setSubscriptionEnabled(id: subscription.id, enabled: $0) }
                    ))
                    .contextMenu {
                        ShareLink(item: subscription.urlString) { Label("分享链接", systemImage: "square.and.arrow.up") }
                    }
                }
                .onDelete(perform: manager.removeSubscriptions)
                Button { subscriptionURL = ""; addingSubscription = true } label: {
                    Label("添加订阅", systemImage: "plus")
                }
            }
            Section {
                Button {
                    Task { await manager.updateRules() }
                } label: {
                    HStack {
                        Text("更新规则")
                        Spacer()
                        if manager.isUpdating { ProgressView() }
                    }
                }
                .disabled(manager.isUpdating || manager.subscriptions.allSatisfy { !$0.isEnabled })
                if manager.safariRulesCount > 0 {
                    HStack { Text("规则"); Spacer(); Text(manager.safariRulesCount.formatted()).foregroundStyle(.secondary) }
                }
                if let date = manager.lastUpdatedAt {
                    HStack { Text("上次更新"); Spacer(); Text(date, style: .date).foregroundStyle(.secondary) }
                }
                if manager.configurationNeedsUpdate { Text("待更新").foregroundStyle(.secondary) }
                if case .failed(let message) = manager.updateState {
                    Text(message).foregroundStyle(.red)
                }
            }
        }
        .navigationTitle("广告屏蔽")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $addingSubscription) { addSubscriptionSheet }
    }

    private var addSubscriptionSheet: some View {
        NavigationStack {
            Form {
                TextField("HTTPS 订阅地址", text: $subscriptionURL)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
            }
            .navigationTitle("添加订阅")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { addingSubscription = false } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("添加") {
                        do {
                            try manager.addSubscription(urlString: subscriptionURL)
                            addingSubscription = false
                        } catch {
                            errorMessage = error.localizedDescription
                        }
                    }
                    .disabled(subscriptionURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
        .presentationDetents([.medium, .large])
        .alert("添加失败", isPresented: Binding(
            get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } }
        )) {
            Button("确定", role: .cancel) { errorMessage = nil }
        } message: { Text(errorMessage ?? "") }
    }
}
