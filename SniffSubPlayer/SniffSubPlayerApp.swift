import SwiftUI

@main
struct SniffSubPlayerApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(OrientationState.shared)
        }
    }
}
