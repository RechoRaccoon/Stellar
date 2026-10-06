import SwiftUI
import UIKit
import WidgetKit
import Shared

@main
struct iOSApp: App {
    @Environment(\.scenePhase) private var scenePhase

    init() {
        // Notifications while Stellar is closed: iOS only accepts the
        // background-refresh handler while the app is still launching.
        IosNotificationsKt.registerIosBackgroundRefresh()
    }

    var body: some Scene {
        WindowGroup {
            ComposeView()
                .ignoresSafeArea()
                // Apple's on-device translation needs a SwiftUI view to
                // hang its session on (iOS 18+; nothing happens below).
                .stellarTranslationHost()
                // A home-screen widget was tapped (stellar://dm/<id>, …).
                .onOpenURL { url in
                    IosBridgesKt.handleIosOpenUrl(url: url.absoluteString)
                }
        }
        // Leaving the app: ask iOS for the next background check. Coming
        // back: its notifications have done their job.
        .onChange(of: scenePhase) { phase in
            if phase == .background {
                IosNotificationsKt.iosAppDidEnterBackground()
            } else if phase == .active {
                IosNotificationsKt.iosAppDidBecomeActive()
            }
        }
    }
}

/// Hosts the shared Compose UI (Kotlin: MainViewController()).
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        // The things only Swift can do, handed to the Kotlin app before it
        // starts: translation, redrawing the widgets, the tagger, GIFs.
        StellarBridges.install()
        return MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

enum StellarBridges {
    private static var installed = false

    static func install() {
        if installed { return }
        installed = true
        IosBridgesKt.registerIosWidgetReloader {
            WidgetCenter.shared.reloadAllTimelines()
        }
        IosBridgesKt.registerIosTranslator(translator: StellarTranslator())
        // AI Tagging (the same model as Android) and Save as GIF.
        IosBridgesKt.registerIosTagger(tagger: StellarTagger())
        IosBridgesKt.registerIosMediaTools(tools: StellarMediaTools())
        // VRM mode's face tracking (ARKit).
        IosBridgesKt.registerIosFaceTracker(tracker: StellarFaceTracker())
    }
}
