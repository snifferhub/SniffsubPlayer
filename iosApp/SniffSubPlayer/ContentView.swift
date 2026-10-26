import ComposeApp
import SwiftUI

struct ContentView: View {
    private struct PlayerPresentation: Identifiable {
        let id = UUID()
        let candidate: ComposeApp.MediaCandidate
    }

    @StateObject private var adBlockManager = AdBlockManager()
    @Environment(\.scenePhase) private var scenePhase
    @State private var selectedCandidate: PlayerPresentation?

    var body: some View {
        KmpAppView(
            adBlockManager: adBlockManager,
            isActive: scenePhase == .active && selectedCandidate == nil,
            onOpenCandidate: { selectedCandidate = PlayerPresentation(candidate: $0) }
        )
        .ignoresSafeArea()
        .fullScreenCover(item: $selectedCandidate) { presentation in
            PlayerView(candidate: presentation.candidate)
        }
        .task { await adBlockManager.refreshIfNeeded() }
    }
}
