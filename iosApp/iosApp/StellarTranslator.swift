import SwiftUI
import Shared
#if canImport(Translation)
import Translation
#endif

/// Apple's Translation framework (iOS 18+), wrapped for the Kotlin app.
/// Everything runs on the device; Apple shows its own sheet the first time
/// a language has to be downloaded.
final class StellarTranslator: NSObject, IosTranslator {
    func isAvailable() -> Bool {
        #if canImport(Translation)
        if #available(iOS 18.0, *) { return true }
        #endif
        return false
    }

    func translate(text: String, source: String, target: String, onResult: @escaping (String?) -> Void) {
        #if canImport(Translation)
        if #available(iOS 18.0, *) {
            Task { @MainActor in
                TranslationBroker.shared.request(text: text, source: source, target: target, done: onResult)
            }
            return
        }
        #endif
        onResult(nil)
    }
}

extension View {
    /// Attaches the translation session to the app's root view.
    @ViewBuilder
    func stellarTranslationHost() -> some View {
        #if canImport(Translation)
        if #available(iOS 18.0, *) {
            self.modifier(TranslationHost())
        } else {
            self
        }
        #else
        self
        #endif
    }
}

#if canImport(Translation)
/// Stellar's language tags → the ones Apple's framework lists.
private func appleLanguage(_ tag: String) -> Locale.Language {
    switch tag {
    case "zh": return Locale.Language(identifier: "zh-Hans")
    case "pt": return Locale.Language(identifier: "pt-BR")
    default: return Locale.Language(identifier: tag)
    }
}

/// One translation at a time, in the order they were asked for. Apple only
/// hands out a session through a SwiftUI view (`translationTask`), and one
/// session is for one language pair, so jobs wait here until the view's
/// session for their pair is ready.
@available(iOS 18.0, *)
@MainActor
final class TranslationBroker: ObservableObject {
    static let shared = TranslationBroker()

    struct Job {
        let text: String
        let source: String
        let target: String
        let done: (String?) -> Void
    }

    @Published var configuration: TranslationSession.Configuration?
    private var queue: [Job] = []
    private var current: Job?

    func request(text: String, source: String, target: String, done: @escaping (String?) -> Void) {
        queue.append(Job(text: text, source: source, target: target, done: done))
        startNext()
    }

    private func startNext() {
        guard current == nil, !queue.isEmpty else { return }
        let job = queue.removeFirst()
        current = job
        Task {
            // Not a pair Apple translates: answer "can't" straight away.
            let status = await LanguageAvailability().status(from: appleLanguage(job.source), to: appleLanguage(job.target))
            if status == .unsupported {
                job.done(nil)
                self.current = nil
                self.startNext()
                return
            }
            let wanted = TranslationSession.Configuration(source: appleLanguage(job.source), target: appleLanguage(job.target))
            if var existing = self.configuration, existing.source == wanted.source, existing.target == wanted.target {
                // Same pair as last time: ask the view to run its task again.
                existing.invalidate()
                self.configuration = existing
            } else {
                self.configuration = wanted
            }
        }
    }

    /// Called by the view each time a session is ready.
    func run(_ session: TranslationSession) async {
        while let job = current {
            do {
                let response = try await session.translate(job.text)
                job.done(response.targetText)
            } catch {
                job.done(nil)
            }
            current = nil
            // More waiting for the very same pair: this session does them too.
            if let next = queue.first, next.source == job.source, next.target == job.target {
                current = queue.removeFirst()
            }
        }
        startNext()
    }
}

@available(iOS 18.0, *)
private struct TranslationHost: ViewModifier {
    @ObservedObject private var broker = TranslationBroker.shared

    func body(content: Content) -> some View {
        content.translationTask(broker.configuration) { session in
            await broker.run(session)
        }
    }
}
#endif
