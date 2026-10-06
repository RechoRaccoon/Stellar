package com.mediaviewer.stream

import com.mediaviewer.platform.Log
import com.mediaviewer.vrm.CaptureLayer
import com.mediaviewer.vrm.PixelFrames
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceDiscoverySession
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureDevicePositionBack
import platform.AVFoundation.AVCaptureDevicePositionFront
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInWideAngleCamera
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureSessionPreset1280x720
import platform.AVFoundation.AVCaptureSessionPreset1920x1080
import platform.AVFoundation.AVCaptureVideoDataOutput
import platform.AVFoundation.AVCaptureVideoDataOutputSampleBufferDelegateProtocol
import platform.AVFoundation.AVCaptureVideoOrientationPortrait
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.requestAccessForMediaType
import platform.CoreFoundation.CFRetain
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGBitmapByteOrder32Little
import platform.CoreMedia.CMSampleBufferGetImageBuffer
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreVideo.CVPixelBufferGetBaseAddress
import platform.CoreVideo.CVPixelBufferGetBytesPerRow
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelBufferPixelFormatTypeKey
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.Foundation.CFBridgingRelease
import platform.QuartzCore.CATransaction
import platform.UIKit.UIColor
import platform.UIKit.UIImage
import platform.UIKit.UIView
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume

/**
 * The Camera page's camera: the phone's own front or back camera shown
 * full screen, with every picture it takes also handed on — to a photo,
 * to a recording ([IosFrameRecorder]) or to a live stream (as its
 * [LiveFeeder]) — with whatever is set to be captured drawn over it.
 *
 * What's captured is the camera's whole picture, the right way round
 * (the screen shows the selfie camera mirrored, like a mirror; captures
 * aren't). Android does the same with CameraX and its own GPU pass.
 */
@OptIn(ExperimentalForeignApi::class)
class IosCameraRig : LiveFeeder {
    private val session = AVCaptureSession()
    private val queue = dispatch_queue_create("rechoraccoon.stellar.camera", null)
    private val frames = dispatch_queue_create("rechoraccoon.stellar.camera.frames", null)
    private var input: AVCaptureDeviceInput? = null
    private val output = AVCaptureVideoDataOutput()
    private var configured = false
    @Volatile private var released = false

    /** The camera on screen. */
    val view: UIView = PreviewView(session)

    val recorder = IosFrameRecorder()
    @Volatile private var streamer: IosLiveStreamer? = null
    @Volatile private var frameWidth = 0
    @Volatile private var frameHeight = 0
    /** Frames seen since the camera (re)started — the page lifts its
     *  cover once this moves. */
    @Volatile var frameCount = 0
        private set
    @Volatile private var photoWanted: ((UIImage?) -> Unit)? = null

    /** Drawn over the camera in everything captured. Their places are
     *  fractions of the SCREEN (see [setViewSize]). */
    @Volatile var captureLayers: List<CaptureLayer> = emptyList()
    @Volatile private var viewAspect = 0.46f

    fun setViewSize(width: Int, height: Int) {
        if (width > 0 && height > 0) viewAspect = width.toFloat() / height
    }

    /**
     * Opens the [front] (selfie) or back camera. [onResult] gets null when
     * it's running, or why it isn't — on the main thread.
     */
    fun start(front: Boolean, onResult: (String?) -> Unit) {
        dispatch_async(queue) {
            val error = runCatching { configure(front) }.getOrElse { Log.e("IosCameraRig", "Camera setup failed", it); "The camera couldn't be started" }
            if (error == null && !released) runCatching { session.startRunning() }
            dispatch_async(dispatch_get_main_queue()) { if (!released) onResult(error) }
        }
    }

    private fun device(front: Boolean): AVCaptureDevice? {
        val position = if (front) AVCaptureDevicePositionFront else AVCaptureDevicePositionBack
        val found = AVCaptureDeviceDiscoverySession.discoverySessionWithDeviceTypes(
            listOf(AVCaptureDeviceTypeBuiltInWideAngleCamera), mediaType = AVMediaTypeVideo, position = position
        ).devices.firstOrNull() as? AVCaptureDevice
        return found ?: AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)
    }

    /** Camera queue. */
    private fun configure(front: Boolean): String? {
        val camera = device(front) ?: return "This device has no camera Stellar can use"
        val newInput = AVCaptureDeviceInput.deviceInputWithDevice(camera, error = null) ?: return "The camera couldn't be opened"
        session.beginConfiguration()
        input?.let { session.removeInput(it) }
        if (!session.canAddInput(newInput)) {
            input?.let { if (session.canAddInput(it)) session.addInput(it) }
            session.commitConfiguration()
            return "The camera couldn't be opened"
        }
        session.addInput(newInput)
        input = newInput
        if (session.canSetSessionPreset(AVCaptureSessionPreset1920x1080)) session.setSessionPreset(AVCaptureSessionPreset1920x1080)
        else if (session.canSetSessionPreset(AVCaptureSessionPreset1280x720)) session.setSessionPreset(AVCaptureSessionPreset1280x720)
        if (!configured) {
            // Frames as plain blue-green-red-alpha pixels, so the overlays
            // can be drawn straight onto them.
            val key = CFBridgingRelease(CFRetain(kCVPixelBufferPixelFormatTypeKey)) as? String
            if (key != null) output.setVideoSettings(mapOf<Any?, Any?>(key to kCVPixelFormatType_32BGRA.toInt()))
            output.setAlwaysDiscardsLateVideoFrames(true)
            output.setSampleBufferDelegate(delegate, queue = frames)
            if (session.canAddOutput(output)) session.addOutput(output)
            configured = true
        }
        // Upright frames, whichever way the sensor is mounted.
        (output.connectionWithMediaType(AVMediaTypeVideo))?.let { c ->
            if (c.isVideoOrientationSupported()) c.setVideoOrientation(AVCaptureVideoOrientationPortrait)
            if (c.isVideoMirroringSupported()) c.setVideoMirrored(false)
        }
        session.commitConfiguration()
        frameCount = 0
        return null
    }

    /** Switches between the selfie and back cameras. */
    fun flip(front: Boolean, onResult: (String?) -> Unit) {
        dispatch_async(queue) {
            val error = runCatching { configure(front) }.getOrElse { "The camera couldn't be switched" }
            dispatch_async(dispatch_get_main_queue()) { if (!released) onResult(error) }
        }
    }

    /** Stops the camera for good. */
    fun release() {
        released = true
        streamer = null
        photoWanted = null
        dispatch_async(queue) {
            runCatching { output.setSampleBufferDelegate(null, queue = null) }
            runCatching { session.stopRunning() }
        }
    }

    /** The camera's picture size (upright), once a frame has arrived. */
    fun frameSize(): Pair<Int, Int>? = if (frameWidth > 0 && frameHeight > 0) frameWidth to frameHeight else null

    /** The next frame as a picture, with the capture overlays on it. */
    suspend fun takePhoto(): UIImage? = suspendCancellableCoroutine { cont ->
        photoWanted = { image -> dispatch_async(dispatch_get_main_queue()) { if (cont.isActive) cont.resume(image) } }
        // (No frame within two seconds: the camera isn't running.)
        platform.darwin.dispatch_after(
            platform.darwin.dispatch_time(platform.darwin.DISPATCH_TIME_NOW, 2_000_000_000L), dispatch_get_main_queue()
        ) {
            if (photoWanted != null) { photoWanted = null; if (cont.isActive) cont.resume(null) }
        }
    }

    // ── live ──

    override fun frameSize(shortSide: Int): Pair<Int, Int>? {
        val w = frameWidth; val h = frameHeight
        if (w <= 0 || h <= 0) return null
        return if (w <= h) shortSide to even(shortSide.toDouble() * h / w) else even(shortSide.toDouble() * w / h) to shortSide
    }

    override fun begin(width: Int, height: Int, streamer: IosLiveStreamer) { this.streamer = streamer }
    override fun end() { streamer = null }

    private fun even(v: Double): Int = ((v.toInt() / 2) * 2).coerceAtLeast(2)

    // ── frames ──

    private val delegate = object : NSObject(), AVCaptureVideoDataOutputSampleBufferDelegateProtocol {
        override fun captureOutput(output: AVCaptureOutput, didOutputSampleBuffer: CMSampleBufferRef?, fromConnection: AVCaptureConnection) {
            if (released) return
            val buffer = CMSampleBufferGetImageBuffer(didOutputSampleBuffer) ?: return
            runCatching { onFrame(buffer) }.onFailure { Log.e("IosCameraRig", "A camera frame couldn't be used", it) }
        }
    }

    /** Frames queue: every picture the camera takes. */
    private fun onFrame(buffer: CVPixelBufferRef) {
        val w = CVPixelBufferGetWidth(buffer).toInt()
        val h = CVPixelBufferGetHeight(buffer).toInt()
        frameWidth = w; frameHeight = h
        frameCount++
        val photo = photoWanted
        val live = streamer?.takeIf { it.wantsFrame() }
        val record = recorder.wantsFrame()
        if (photo == null && live == null && !record) return
        val layers = captureLayers
        if (layers.isNotEmpty()) PixelFrames.addLayers(buffer, placedOnFrame(layers, w, h))
        if (record) recorder.append(buffer)
        live?.submit(buffer)
        if (photo != null) {
            photoWanted = null
            photo(picture(buffer, w, h))
        }
    }

    /**
     * The screen shows the middle of the camera's picture (scaled to
     * cover it), so a place on screen is a slightly different place in
     * the whole picture: this moves each overlay to where it was seen.
     */
    private fun placedOnFrame(layers: List<CaptureLayer>, w: Int, h: Int): List<CaptureLayer> {
        val frame = w.toFloat() / h
        val shown = viewAspect
        // The share of the picture's width / height that's on screen.
        val acrossShown = if (frame > shown) shown / frame else 1f
        val downShown = if (frame > shown) 1f else frame / shown
        fun x(u: Float) = 0.5f + (u - 0.5f) * acrossShown
        fun y(v: Float) = 0.5f + (v - 0.5f) * downShown
        return layers.map { CaptureLayer(it.image, x(it.left), y(it.top), x(it.right), y(it.bottom)) }
    }

    /** A camera frame as a picture that stands on its own (the frame's
     *  memory goes back to the camera straight after). */
    private fun picture(buffer: CVPixelBufferRef, w: Int, h: Int): UIImage? {
        CVPixelBufferLockBaseAddress(buffer, 0u)
        val space = CGColorSpaceCreateDeviceRGB()
        val info = CGImageAlphaInfo.kCGImageAlphaPremultipliedFirst.value or kCGBitmapByteOrder32Little
        val over = CGBitmapContextCreate(CVPixelBufferGetBaseAddress(buffer), w.toULong(), h.toULong(), 8u, CVPixelBufferGetBytesPerRow(buffer), space, info)
        val borrowed = if (over != null) CGBitmapContextCreateImage(over) else null
        // Redrawn into memory of its own.
        val own = CGBitmapContextCreate(null, w.toULong(), h.toULong(), 8u, 0u, space, info)
        var out: UIImage? = null
        if (borrowed != null && own != null) {
            CGContextDrawImage(own, CGRectMake(0.0, 0.0, w.toDouble(), h.toDouble()), borrowed)
            val copy = CGBitmapContextCreateImage(own)
            if (copy != null) { out = UIImage.imageWithCGImage(copy); CGImageRelease(copy) }
        }
        if (borrowed != null) CGImageRelease(borrowed)
        if (own != null) CGContextRelease(own)
        if (over != null) CGContextRelease(over)
        CGColorSpaceRelease(space)
        CVPixelBufferUnlockBaseAddress(buffer, 0u)
        return out
    }

    companion object {
        /** Asks for the camera (iOS shows its prompt the first time). */
        suspend fun cameraAllowed(): Boolean = suspendCancellableCoroutine { cont ->
            runCatching {
                AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted ->
                    dispatch_async(dispatch_get_main_queue()) { if (cont.isActive) cont.resume(granted) }
                }
            }.onFailure { if (cont.isActive) cont.resume(false) }
        }
    }
}

/** The view the camera is shown in: its picture fills it, cropped at the
 *  edges rather than squashed. */
@OptIn(ExperimentalForeignApi::class)
private class PreviewView(session: AVCaptureSession) : UIView(frame = CGRectMake(0.0, 0.0, 100.0, 100.0)) {
    private val preview = AVCaptureVideoPreviewLayer(session = session)

    init {
        setBackgroundColor(UIColor.blackColor)
        preview.setVideoGravity(AVLayerVideoGravityResizeAspectFill)
        layer.addSublayer(preview)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        // (Without the usual quarter-second glide to the new size.)
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        preview.setFrame(bounds)
        CATransaction.commit()
    }
}
