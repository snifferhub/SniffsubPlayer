import ComposeApp
import SwiftUI
import WebKit

struct KmpAppView: UIViewControllerRepresentable {
    @ObservedObject var adBlockManager: AdBlockManager
    let isActive: Bool
    let onOpenCandidate: (ComposeApp.MediaCandidate) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(parent: self) }

    func makeUIViewController(context: Context) -> UIViewController {
        let coordinator = context.coordinator
        coordinator.host.callbacks = coordinator
        coordinator.update(self)
        return coordinator.host.createViewController()
    }

    func updateUIViewController(_ controller: UIViewController, context: Context) {
        context.coordinator.update(self)
    }

    static func dismantleUIViewController(_ controller: UIViewController, coordinator: Coordinator) {
        coordinator.host.close()
    }

    @MainActor
    final class Coordinator: NSObject, @preconcurrency IosBrowserCallbacks {
        let host = IosBrowserHost()
        var parent: KmpAppView

        init(parent: KmpAppView) { self.parent = parent }

        func update(_ parent: KmpAppView) {
            self.parent = parent
            host.setActive(value: parent.isActive)
            let manager = parent.adBlockManager
            let error: String?
            if case .failed(let message) = manager.updateState { error = message } else { error = nil }
            host.updateAdBlockState(state: ComposeApp.AdBlockState(
                isEnabled: manager.isEnabled,
                automaticUpdatesEnabled: manager.automaticUpdatesEnabled,
                subscriptions: manager.subscriptions.map { subscription in
                    ComposeApp.AdBlockSubscription(
                        id: subscription.id.uuidString, name: subscription.name,
                        url: subscription.urlString, enabled: subscription.isEnabled, builtIn: false
                    )
                },
                isUpdating: manager.isUpdating,
                configurationNeedsUpdate: manager.configurationNeedsUpdate,
                sourceRulesCount: Int32(manager.sourceRulesCount),
                convertedRulesCount: Int32(manager.safariRulesCount),
                discardedRulesCount: Int32(manager.discardedRulesCount),
                lastUpdatedAtEpochMs: manager.lastUpdatedAt.map {
                    KotlinLong(value: Int64($0.timeIntervalSince1970 * 1000))
                },
                errorMessage: error,
                rulesRevision: Int64(manager.ruleRevision)
            ))
        }

        func openCandidate(candidate: ComposeApp.MediaCandidate) {
            parent.onOpenCandidate(candidate)
        }

        func configureWebView(view: WKWebView, onReady: @escaping () -> Void) {
            parent.adBlockManager.applyRules(to: view.configuration.userContentController, completion: onReady)
        }

        func setAdBlockEnabled(enabled: Bool) { parent.adBlockManager.setEnabled(enabled) }
        func setAutomaticUpdatesEnabled(enabled: Bool) {
            parent.adBlockManager.setAutomaticUpdatesEnabled(enabled)
        }
        func setSubscriptionEnabled(id: String, enabled: Bool) {
            guard let uuid = UUID(uuidString: id) else { return }
            parent.adBlockManager.setSubscriptionEnabled(id: uuid, enabled: enabled)
        }
        func addSubscription(url: String) -> String? {
            do {
                try parent.adBlockManager.addSubscription(urlString: url)
                return nil
            } catch { return error.localizedDescription }
        }
        func removeSubscription(id: String) {
            guard let index = parent.adBlockManager.subscriptions.firstIndex(where: { $0.id.uuidString == id }) else { return }
            parent.adBlockManager.removeSubscriptions(at: IndexSet(integer: index))
        }
        func refreshRules() { Task { await parent.adBlockManager.updateRules() } }
    }
}
