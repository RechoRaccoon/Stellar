import Foundation
import UIKit
import AVFoundation
import ImageIO
import Shared

/// "Save as GIF" on iOS, with Apple's own frameworks: AVFoundation reads a
/// video's frames and ImageIO writes the GIF. The Kotlin app downloads the
/// source file and saves the finished GIF to Photos.
final class StellarMediaTools: NSObject, IosMediaTools {
    private let gifType = "com.compuserve.gif" as CFString
    /// The most frames one GIF gets (a longer video keeps its length and
    /// gets fewer frames a second instead).
    private let maxFrames = 600
    /// The longest side of the GIF, in pixels.
    private let maxSide: CGFloat = 540

    func gifFromVideo(videoPath: String, outPath: String, onDone: @escaping (String?) -> Void) {
        DispatchQueue.global(qos: .userInitiated).async {
            let asset = AVURLAsset(url: URL(fileURLWithPath: videoPath))
            let seconds = CMTimeGetSeconds(asset.duration)
            guard seconds.isFinite, seconds > 0 else { onDone("This video can't be read"); return }
            guard let track = asset.tracks(withMediaType: .video).first else { onDone("This file has no video"); return }

            // The video's own frame rate, up to 25 a second (a GIF's frame
            // time is counted in hundredths of a second, and players slow
            // down anything faster than about that).
            let sourceRate = Double(track.nominalFrameRate)
            var rate = min(max(sourceRate > 0 ? sourceRate : 25, 5), 25)
            var count = Int((seconds * rate).rounded(.up))
            if count > self.maxFrames {
                count = self.maxFrames
                rate = Double(count) / seconds
            }
            count = max(count, 1)

            let generator = AVAssetImageGenerator(asset: asset)
            generator.appliesPreferredTrackTransform = true
            generator.maximumSize = CGSize(width: self.maxSide, height: self.maxSide)
            let tolerance = CMTime(seconds: 0.5 / rate, preferredTimescale: 600)
            generator.requestedTimeToleranceBefore = tolerance
            generator.requestedTimeToleranceAfter = tolerance

            let outUrl = URL(fileURLWithPath: outPath)
            try? FileManager.default.removeItem(at: outUrl)
            guard let destination = CGImageDestinationCreateWithURL(outUrl as CFURL, self.gifType, count, nil) else {
                onDone("Couldn't start the GIF")
                return
            }
            let loop = [kCGImagePropertyGIFDictionary as String: [kCGImagePropertyGIFLoopCount as String: 0]]
            CGImageDestinationSetProperties(destination, loop as CFDictionary)
            let delay = 1.0 / rate
            let frameProperties = [
                kCGImagePropertyGIFDictionary as String: [
                    kCGImagePropertyGIFDelayTime as String: delay,
                    kCGImagePropertyGIFUnclampedDelayTime as String: delay
                ]
            ] as CFDictionary

            // Frames go straight into the file one by one (never all held
            // in memory). The file was promised `count` frames, so a frame
            // that can't be read is filled with the one before it.
            var last: CGImage?
            for index in 0..<count {
                autoreleasepool {
                    let time = CMTime(seconds: Double(index) / rate, preferredTimescale: 600)
                    if let frame = try? generator.copyCGImage(at: time, actualTime: nil) { last = frame }
                    if let frame = last { CGImageDestinationAddImage(destination, frame, frameProperties) }
                }
                if last == nil { break }
            }
            guard last != nil else { onDone("This video's frames can't be read"); return }
            onDone(CGImageDestinationFinalize(destination) ? nil : "Couldn't finish the GIF")
        }
    }

    func gifFromImage(imagePath: String, outPath: String, onDone: @escaping (String?) -> Void) {
        DispatchQueue.global(qos: .userInitiated).async {
            guard let image = UIImage(contentsOfFile: imagePath) else { onDone("This picture can't be read"); return }
            // Drawn once so a rotated photo comes out the right way up.
            let format = UIGraphicsImageRendererFormat()
            format.scale = 1
            let upright = UIGraphicsImageRenderer(size: image.size, format: format).image { _ in
                image.draw(in: CGRect(origin: .zero, size: image.size))
            }
            guard let cgImage = upright.cgImage else { onDone("This picture can't be read"); return }
            let outUrl = URL(fileURLWithPath: outPath)
            try? FileManager.default.removeItem(at: outUrl)
            guard let destination = CGImageDestinationCreateWithURL(outUrl as CFURL, self.gifType, 1, nil) else {
                onDone("Couldn't start the GIF")
                return
            }
            CGImageDestinationAddImage(destination, cgImage, nil)
            onDone(CGImageDestinationFinalize(destination) ? nil : "Couldn't finish the GIF")
        }
    }
}
