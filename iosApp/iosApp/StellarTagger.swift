import Foundation
import UIKit
import Shared
#if canImport(OnnxRuntimeBindings)
import OnnxRuntimeBindings
#endif

/// AI Tagging on iOS: runs the same model file Android uses
/// (Z3D-E621-Convnext, an ONNX model) with ONNX Runtime, entirely on the
/// device. The Kotlin app decides what to tag and keeps the results; this
/// class only downloads the model, loads it, and runs it on one picture at
/// a time.
final class StellarTagger: NSObject, IosTagger, URLSessionDownloadDelegate {
    private let inputSize = 448
    /// Loading and running the model happen here, one job at a time.
    private let work = DispatchQueue(label: "stellar.tagger", qos: .userInitiated)

    #if canImport(OnnxRuntimeBindings)
    private var env: ORTEnv?
    private var session: ORTSession?
    private var inputName = ""
    private var outputName = ""
    #endif
    private var loaded = false

    func isAvailable() -> Bool {
        #if canImport(OnnxRuntimeBindings)
        return true
        #else
        return false
        #endif
    }

    // MARK: - Downloading the model

    private struct Job {
        let destination: String
        let progress: (KotlinLong, KotlinLong) -> Void
        let done: (String?) -> Void
    }

    private var jobs: [Int: Job] = [:]
    private let jobsLock = NSLock()
    private lazy var downloads: URLSession = {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 60
        config.timeoutIntervalForResource = 60 * 60 * 6
        return URLSession(configuration: config, delegate: self, delegateQueue: nil)
    }()

    private func takeJob(_ id: Int) -> Job? {
        jobsLock.lock()
        defer { jobsLock.unlock() }
        return jobs.removeValue(forKey: id)
    }

    private func peekJob(_ id: Int) -> Job? {
        jobsLock.lock()
        defer { jobsLock.unlock() }
        return jobs[id]
    }

    func download(url: String, toPath: String, onProgress: @escaping (KotlinLong, KotlinLong) -> Void, onDone: @escaping (String?) -> Void) {
        guard let address = URL(string: url) else { onDone("Bad address"); return }
        let task = downloads.downloadTask(with: address)
        jobsLock.lock()
        jobs[task.taskIdentifier] = Job(destination: toPath, progress: onProgress, done: onDone)
        jobsLock.unlock()
        task.resume()
    }

    func cancelDownloads() {
        jobsLock.lock()
        jobs.removeAll()
        jobsLock.unlock()
        downloads.getAllTasks { tasks in tasks.forEach { $0.cancel() } }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64, totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        guard let job = peekJob(downloadTask.taskIdentifier) else { return }
        job.progress(KotlinLong(longLong: totalBytesWritten), KotlinLong(longLong: max(totalBytesExpectedToWrite, 0)))
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        guard let job = takeJob(downloadTask.taskIdentifier) else { return }
        if let http = downloadTask.response as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
            job.done("HTTP \(http.statusCode)")
            return
        }
        // (The temporary file is gone once this method returns, so it's
        // moved into place right here.)
        var destination = URL(fileURLWithPath: job.destination)
        do {
            let files = FileManager.default
            try files.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
            if files.fileExists(atPath: destination.path) { try files.removeItem(at: destination) }
            try files.moveItem(at: location, to: destination)
            // It can always be downloaded again: kept out of iCloud backups.
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try? destination.setResourceValues(values)
            job.done(nil)
        } catch {
            job.done(error.localizedDescription)
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let error = error, let job = takeJob(task.taskIdentifier) else { return }
        job.done(error.localizedDescription)
    }

    // MARK: - The model

    func isLoaded() -> Bool { loaded }

    func load(modelPath: String, onDone: @escaping (String?) -> Void) {
        #if canImport(OnnxRuntimeBindings)
        work.async {
            if self.loaded { onDone(nil); return }
            do {
                let env = try ORTEnv(loggingLevel: ORTLoggingLevel.warning)
                let options = try ORTSessionOptions()
                // One core is left for the app itself.
                let threads = max(2, min(6, ProcessInfo.processInfo.activeProcessorCount - 1))
                try options.setIntraOpNumThreads(Int32(threads))
                try options.setGraphOptimizationLevel(ORTGraphOptimizationLevel.all)
                let session = try ORTSession(env: env, modelPath: modelPath, sessionOptions: options)
                guard let input = try session.inputNames().first, let output = try session.outputNames().first else {
                    onDone("The model has no input or output")
                    return
                }
                self.env = env
                self.session = session
                self.inputName = input
                self.outputName = output
                self.loaded = true
                onDone(nil)
            } catch {
                onDone(error.localizedDescription)
            }
        }
        #else
        onDone("This build doesn't include the tagger")
        #endif
    }

    func unload() {
        #if canImport(OnnxRuntimeBindings)
        work.async {
            self.loaded = false
            self.session = nil
            self.env = nil
        }
        #endif
    }

    func tag(imagePath: String, minScore: Float, onResult: @escaping ([KotlinInt]?, [KotlinFloat]?) -> Void) {
        #if canImport(OnnxRuntimeBindings)
        work.async {
            guard let session = self.session,
                  let image = UIImage(contentsOfFile: imagePath),
                  let input = self.modelInput(image) else {
                onResult(nil, nil)
                return
            }
            do {
                let size = NSNumber(value: self.inputSize)
                let tensor = try ORTValue(
                    tensorData: NSMutableData(data: input),
                    elementType: ORTTensorElementDataType.float,
                    shape: [1, size, size, 3]
                )
                let outputs = try session.run(withInputs: [self.inputName: tensor], outputNames: [self.outputName], runOptions: nil)
                guard let output = outputs[self.outputName] else { onResult(nil, nil); return }
                let raw = try output.tensorData() as Data
                var indices: [KotlinInt] = []
                var scores: [KotlinFloat] = []
                raw.withUnsafeBytes { (buffer: UnsafeRawBufferPointer) in
                    let values = buffer.bindMemory(to: Float32.self)
                    for i in 0..<values.count where values[i] >= minScore {
                        indices.append(KotlinInt(int: Int32(i)))
                        scores.append(KotlinFloat(float: values[i]))
                    }
                }
                onResult(indices, scores)
            } catch {
                onResult(nil, nil)
            }
        }
        #else
        onResult(nil, nil)
        #endif
    }

    /// The picture as the model wants it, identical to Android's
    /// preparation: scaled to fit a white 448×448 square (never stretched),
    /// then every pixel as three raw 0–255 numbers in blue, green, red
    /// order, row by row from the top.
    private func modelInput(_ image: UIImage) -> Data? {
        let side = CGFloat(inputSize)
        guard image.size.width > 0, image.size.height > 0 else { return nil }
        let scale = min(side / image.size.width, side / image.size.height)
        let width = max(1, (image.size.width * scale).rounded(.down))
        let height = max(1, (image.size.height * scale).rounded(.down))
        let target = CGRect(x: ((side - width) / 2).rounded(.down), y: ((side - height) / 2).rounded(.down), width: width, height: height)

        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let square = UIGraphicsImageRenderer(size: CGSize(width: side, height: side), format: format).image { context in
            UIColor.white.setFill()
            context.fill(CGRect(x: 0, y: 0, width: side, height: side))
            image.draw(in: target)
        }
        guard let cgImage = square.cgImage else { return nil }

        var pixels = [UInt8](repeating: 255, count: inputSize * inputSize * 4)
        let drawn: Bool = pixels.withUnsafeMutableBytes { buffer in
            guard let context = CGContext(
                data: buffer.baseAddress, width: inputSize, height: inputSize,
                bitsPerComponent: 8, bytesPerRow: inputSize * 4,
                space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
            ) else { return false }
            context.draw(cgImage, in: CGRect(x: 0, y: 0, width: side, height: side))
            return true
        }
        guard drawn else { return nil }

        var floats = [Float32](repeating: 0, count: inputSize * inputSize * 3)
        var out = 0
        var i = 0
        while i < pixels.count {
            floats[out] = Float32(pixels[i + 2])      // blue
            floats[out + 1] = Float32(pixels[i + 1])  // green
            floats[out + 2] = Float32(pixels[i])      // red
            out += 3
            i += 4
        }
        return floats.withUnsafeBufferPointer { Data(buffer: $0) }
    }
}
