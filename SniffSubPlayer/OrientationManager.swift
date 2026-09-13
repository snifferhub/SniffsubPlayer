import SwiftUI
import UIKit

final class OrientationState: ObservableObject {
    static let shared = OrientationState()

    @Published var mask: UIInterfaceOrientationMask = [.portrait, .landscapeLeft, .landscapeRight]
}

final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        supportedInterfaceOrientationsFor window: UIWindow?
    ) -> UIInterfaceOrientationMask {
        OrientationState.shared.mask
    }
}

@MainActor
enum OrientationManager {
    static func lock(_ mask: UIInterfaceOrientationMask, preferred: UIInterfaceOrientation? = nil) {
        OrientationState.shared.mask = mask
        guard let preferred else {
            refreshSupportedOrientations()
            return
        }

        if #available(iOS 16.0, *) {
            guard let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene else {
                return
            }
            let preferences = UIWindowScene.GeometryPreferences.iOS(interfaceOrientations: mask)
            scene.requestGeometryUpdate(preferences)
            scene.windows.first?.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
        } else {
            UIDevice.current.setValue(preferred.rawValue, forKey: "orientation")
            UIViewController.attemptRotationToDeviceOrientation()
        }
    }

    static func unlock() {
        OrientationState.shared.mask = [.portrait, .landscapeLeft, .landscapeRight]
        refreshSupportedOrientations()
    }

    private static func refreshSupportedOrientations() {
        if #available(iOS 16.0, *) {
            let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene
            scene?.windows.first?.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
        } else {
            UIViewController.attemptRotationToDeviceOrientation()
        }
    }
}
