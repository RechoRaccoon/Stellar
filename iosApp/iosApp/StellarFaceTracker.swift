import Foundation
import UIKit
import ARKit
import Vision
import ImageIO
import Shared

/// VRM mode's eyes: ARKit face tracking (the TrueDepth / Face ID camera).
/// Each time ARKit updates the face, the head's position and turn and the
/// 52 blend-shape coefficients (blink, jaw, smile…) are handed to the
/// Kotlin app as plain numbers. Nothing is recorded or sent anywhere, and
/// the camera picture itself never leaves ARKit.
///
/// With body or hand tracking switched on in VRM Settings, the same camera
/// picture is also shown to Apple's Vision framework (on the phone, like
/// everything else here), which finds your shoulders, elbows, wrists and
/// finger joints. Those go to Kotlin as plain numbers too.
final class StellarFaceTracker: NSObject, IosFaceTracker, ARSessionDelegate {
    private var session: ARSession?
    private var listener: IosFaceListener?

    // What Vision should look for, and how (set from Kotlin, main thread).
    private var visionBody = false
    private var visionHands = false
    private var visionOrientation: CGImagePropertyOrientation = .right
    private var visionIntervalMs: Int = 66
    private var visionBusy = false
    private var lastVisionTime: TimeInterval = 0
    private var focalSent: Float = 0
    private let visionQueue = DispatchQueue(label: "rechoraccoon.stellar.vision", qos: .userInitiated)

    private static let bodyJoints: [VNHumanBodyPoseObservation.JointName] = [
        .nose, .leftEye, .rightEye, .leftEar, .rightEar,
        .leftShoulder, .rightShoulder, .leftElbow, .rightElbow, .leftWrist, .rightWrist,
        .leftHip, .rightHip, .leftKnee, .rightKnee, .leftAnkle, .rightAnkle,
        .neck, .root
    ]
    private static let handJoints: [VNHumanHandPoseObservation.JointName] = [
        .wrist,
        .thumbCMC, .thumbMP, .thumbIP, .thumbTip,
        .indexMCP, .indexPIP, .indexDIP, .indexTip,
        .middleMCP, .middlePIP, .middleDIP, .middleTip,
        .ringMCP, .ringPIP, .ringDIP, .ringTip,
        .littleMCP, .littlePIP, .littleDIP, .littleTip
    ]

    func isSupported() -> Bool {
        return ARFaceTrackingConfiguration.isSupported
    }

    func start(listener: IosFaceListener) {
        guard ARFaceTrackingConfiguration.isSupported else { return }
        stop()
        self.listener = listener
        let configuration = ARFaceTrackingConfiguration()
        configuration.isLightEstimationEnabled = false
        let session = ARSession()
        // (No queue given: updates arrive on the main thread.)
        session.delegate = self
        self.session = session
        session.run(configuration, options: [.resetTracking, .removeExistingAnchors])
    }

    func stop() {
        session?.pause()
        session?.delegate = nil
        session = nil
        listener = nil
        focalSent = 0
    }

    func setVision(body: Bool, hands: Bool, orientation: Int32, intervalMs: Int32) {
        visionBody = body
        visionHands = hands
        visionOrientation = CGImagePropertyOrientation(rawValue: UInt32(max(1, orientation))) ?? .right
        visionIntervalMs = Int(intervalMs)
    }

    /// Every camera picture: tells Kotlin the lens's focal length once,
    /// and — a few times a second, when asked to — has Vision look for
    /// the body and hands in it.
    func session(_ session: ARSession, didUpdate frame: ARFrame) {
        guard let listener = listener else { return }
        let buffer = frame.capturedImage
        let width = CVPixelBufferGetWidth(buffer)
        let height = CVPixelBufferGetHeight(buffer)
        let longSide = Float(max(width, height))
        if longSide > 0 {
            let focal = frame.camera.intrinsics.columns.0.x / longSide
            if abs(focal - focalSent) > 0.001 {
                focalSent = focal
                listener.onCamera(focal: focal)
            }
        }
        if !(visionBody || visionHands) || visionBusy { return }
        if frame.timestamp - lastVisionTime < Double(visionIntervalMs) / 1000.0 { return }
        lastVisionTime = frame.timestamp
        visionBusy = true
        let wantBody = visionBody
        let wantHands = visionHands
        let orientation = visionOrientation
        visionQueue.async { [weak self] in
            var body: [KotlinFloat] = []
            var hands: [KotlinFloat] = []
            let handler = VNImageRequestHandler(cvPixelBuffer: buffer, orientation: orientation, options: [:])
            let bodyRequest = VNDetectHumanBodyPoseRequest()
            let handRequest = VNDetectHumanHandPoseRequest()
            handRequest.maximumHandCount = 2
            var requests: [VNRequest] = []
            if wantBody { requests.append(bodyRequest) }
            if wantHands { requests.append(handRequest) }
            try? handler.perform(requests)
            if wantBody, let person = bodyRequest.results?.first {
                body.reserveCapacity(StellarFaceTracker.bodyJoints.count * 3)
                for joint in StellarFaceTracker.bodyJoints {
                    if let point = try? person.recognizedPoint(joint) {
                        body.append(KotlinFloat(float: Float(point.location.x)))
                        body.append(KotlinFloat(float: Float(point.location.y)))
                        body.append(KotlinFloat(float: point.confidence))
                    } else {
                        body.append(KotlinFloat(float: 0))
                        body.append(KotlinFloat(float: 0))
                        body.append(KotlinFloat(float: 0))
                    }
                }
            }
            if wantHands, let found = handRequest.results {
                for hand in found.prefix(2) {
                    for joint in StellarFaceTracker.handJoints {
                        if let point = try? hand.recognizedPoint(joint) {
                            hands.append(KotlinFloat(float: Float(point.location.x)))
                            hands.append(KotlinFloat(float: Float(point.location.y)))
                            hands.append(KotlinFloat(float: point.confidence))
                        } else {
                            hands.append(KotlinFloat(float: 0))
                            hands.append(KotlinFloat(float: 0))
                            hands.append(KotlinFloat(float: 0))
                        }
                    }
                }
            }
            // The picture's shape once it's turned upright.
            let sideways = orientation == .left || orientation == .right
                || orientation == .leftMirrored || orientation == .rightMirrored
            let aspect = sideways ? Float(height) / Float(max(1, width)) : Float(width) / Float(max(1, height))
            DispatchQueue.main.async {
                guard let self = self else { return }
                self.visionBusy = false
                self.listener?.onVision(body: body, hands: hands, aspect: aspect)
            }
        }
    }

    func session(_ session: ARSession, didUpdate anchors: [ARAnchor]) {
        guard let listener = listener else { return }
        var found: ARFaceAnchor? = nil
        for anchor in anchors {
            if let face = anchor as? ARFaceAnchor {
                found = face
                break
            }
        }
        guard let face = found else { return }
        guard face.isTracked, let frame = session.currentFrame else {
            listener.onFaceLost()
            return
        }
        // The head as it appears on screen, whichever way the phone is held.
        let orientation = Self.interfaceOrientation()
        let view = frame.camera.viewMatrix(for: orientation)
        let m = simd_mul(view, face.transform)
        var matrix: [KotlinFloat] = []
        matrix.reserveCapacity(16)
        for column in 0..<4 {
            let c = m[column]
            matrix.append(KotlinFloat(float: c.x))
            matrix.append(KotlinFloat(float: c.y))
            matrix.append(KotlinFloat(float: c.z))
            matrix.append(KotlinFloat(float: c.w))
        }
        var names: [String] = []
        var values: [KotlinFloat] = []
        names.reserveCapacity(face.blendShapes.count)
        values.reserveCapacity(face.blendShapes.count)
        for (key, value) in face.blendShapes {
            names.append(key.rawValue)
            values.append(KotlinFloat(float: value.floatValue))
        }
        listener.onFace(matrix: matrix, names: names, values: values)
    }

    func session(_ session: ARSession, didFailWithError error: Error) {
        listener?.onFaceLost()
    }

    private static func interfaceOrientation() -> UIInterfaceOrientation {
        for scene in UIApplication.shared.connectedScenes {
            if let windowScene = scene as? UIWindowScene {
                return windowScene.interfaceOrientation
            }
        }
        return .portrait
    }
}
