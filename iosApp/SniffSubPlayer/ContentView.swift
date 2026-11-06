import ComposeApp
import SwiftUI

@MainActor
struct ContentView: View {
    private enum AppTab: Hashable { case browser, settings }
    private struct PlayerPresentation: Identifiable {
        let id = UUID()
        let candidate: ComposeApp.MediaCandidate
    }

    @StateObject private var adBlockManager: AdBlockManager
    @StateObject private var browser: AppleBrowserModel
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var selectedTab = AppTab.browser
    @State private var selectedCandidate: PlayerPresentation?

    init() {
        let manager = AdBlockManager()
        _adBlockManager = StateObject(wrappedValue: manager)
        _browser = StateObject(wrappedValue: AppleBrowserModel(adBlockManager: manager))
    }

    var body: some View {
        // The system supplies Liquid Glass on iOS 26+, and the native tab bar on older OSes.
        // Keep the requested bottom placement on iPad, restoring the real size class in each page.
        TabView(selection: $selectedTab) {
            AppleBrowserScreen(model: browser) { candidate in
                browser.host.dismissCandidates()
                browser.host.stopSniffing()
                selectedCandidate = PlayerPresentation(candidate: candidate)
            }
            .environment(\.horizontalSizeClass, sizeClass)
            .tabItem { Label("浏览器", systemImage: "globe") }
            .tag(AppTab.browser)

            AppleSettingsScreen(model: browser, adBlockManager: adBlockManager) { bookmark in
                browser.host.loadAddress(address: bookmark.url)
                selectedTab = .browser
            }
            .environment(\.horizontalSizeClass, sizeClass)
            .tabItem { Label("设置", systemImage: "gearshape") }
            .tag(AppTab.settings)
        }
        .environment(\.horizontalSizeClass, .compact)
        .fullScreenCover(item: $selectedCandidate) { presentation in
            PlayerView(candidate: presentation.candidate)
        }
        .onAppear {
            updateBrowserActivity()
        }
        .onChange(of: selectedTab) { _ in updateBrowserActivity() }
        .onChange(of: scenePhase) { _ in updateBrowserActivity() }
        .onChange(of: selectedCandidate?.id) { _ in updateBrowserActivity() }
        .onReceive(adBlockManager.$ruleRevision) { revision in
            browser.host.setFilterRevision(value: Int64(revision))
        }
        .task { await adBlockManager.refreshIfNeeded() }
        .alert("操作失败", isPresented: Binding(
            get: { browser.errorMessage != nil },
            set: { if !$0 { browser.errorMessage = nil } }
        )) {
            Button("确定", role: .cancel) { browser.errorMessage = nil }
        } message: {
            Text(browser.errorMessage ?? "")
        }
    }

    private func updateBrowserActivity() {
        browser.host.setActive(value: scenePhase == .active && selectedTab == .browser && selectedCandidate == nil)
        if selectedTab != .browser { browser.host.dismissCandidates() }
    }
}
