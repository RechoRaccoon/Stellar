import Foundation
import AVFoundation
import Shared

/// Hands the Kotlin audio rig a way to wire and start Apple's audio engine
/// without an Objective-C exception closing the app (see StellarTry.m).
/// A refused wiring comes back as a message, and the recording carries on
/// without the microphone.
final class StellarAudioGuard: NSObject, IosAudioGuard {
    func connect(engine: AVAudioEngine, from: AVAudioNode, to: AVAudioNode, format: AVAudioFormat?) -> String? {
        return StellarTry.connect(in: engine, from: from, to: to, format: format)
    }

    func start(engine: AVAudioEngine) -> String? {
        return StellarTry.start(engine: engine)
    }
}
