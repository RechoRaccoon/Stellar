package com.mediaviewer.vrm

import com.mediaviewer.stream.IosFrameRecorder
import com.mediaviewer.stream.IosLiveStreamer
import com.mediaviewer.stream.LiveFeeder
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRef
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.CoreGraphics.kCGBitmapByteOrder32Little
import platform.CoreVideo.CVPixelBufferCreate
import platform.CoreVideo.CVPixelBufferGetBaseAddress
import platform.CoreVideo.CVPixelBufferGetBytesPerRow
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferGetPixelFormatType
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.CVPixelBufferRefVar
import platform.CoreVideo.CVPixelBufferRelease
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.CoreVideo.kCVReturnSuccess
import platform.SceneKit.SCNAntialiasingMode
import platform.SceneKit.SCNRenderer
import platform.UIKit.UIImage
import kotlin.concurrent.Volatile

/**
 * Records the avatar — only what's drawn on stage (plus whatever is set to
 * be captured over it), never the buttons and never the camera — as an
 * mp4, with your voice when the mic is on.
 *
 * How: after each frame the view draws, the same scene is drawn once more
 * off screen at video size and handed to an [IosFrameRecorder], which
 * writes the file and adds the sound. Android records the same picture
 * through Filament and a MediaRecorder.
 */
@OptIn(ExperimentalForeignApi::class)
class VrmRecorder(private val stage: VrmStage) {
    private val recorder = IosFrameRecorder()
    private var offscreen: SCNRenderer? = null
    private var width = 720
    private var height = 1280

    val isRecording: Boolean get() = recorder.isRecording

    /** Voice pitch in semitones; follows the slider while recording. */
    var pitchSemitones: Float
        get() = recorder.pitchSemitones
        set(value) { recorder.pitchSemitones = value }

    /** Starts recording. Returns null, or why it couldn't start. Main thread. */
    fun start(withMic: Boolean): String? {
        if (recorder.isRecording) return null
        // Video size: 720 on the short side, the stage's own shape.
        val (vw, vh) = stage.view.bounds.useContents { size.width to size.height }
        if (vw < 1.0 || vh < 1.0) return "The avatar isn't on screen yet"
        if (vw <= vh) { width = 720; height = even(720.0 * vh / vw) } else { height = 720; width = even(720.0 * vw / vh) }
        val r = SCNRenderer.rendererWithDevice(null, options = null)
        r.setScene(stage.scene)
        r.setPointOfView(stage.camera())
        offscreen = r
        val error = recorder.start(width, height, withMic)
        if (error != null) { offscreen = null; return error }
        val w = width; val h = height
        stage.afterFrame = { time ->
            if (recorder.wantsFrame()) {
                val image = runCatching {
                    r.snapshotAtTime(time, withSize = CGSizeMake(w.toDouble(), h.toDouble()), antialiasingMode = SCNAntialiasingMode.SCNAntialiasingModeMultisampling2X)
                }.getOrNull()
                val buffer = if (image != null) PixelFrames.fromImage(image, w, h, stage.captureLayers) else null
                if (buffer != null) try { recorder.append(buffer) } finally { CVPixelBufferRelease(buffer) }
            }
        }
        return null
    }

    /** Stops and finishes the file. Returns its path, or null if nothing
     *  usable was recorded. */
    suspend fun stop(): String? {
        if (!recorder.isRecording) return null
        stage.afterFrame = null
        offscreen = null
        return recorder.stop()
    }

    private fun even(v: Double): Int = ((v.toInt() / 2) * 2).coerceAtLeast(2)

    companion object {
        /** Asks for the microphone (iOS shows its prompt the first time). */
        suspend fun micAllowed(): Boolean = IosFrameRecorder.micAllowed()
    }
}

/**
 * VRM mode's stage as a live stream's pictures: after each frame the stage
 * draws, the same scene is drawn once more off screen at stream size (with
 * whatever is captured over it) and handed to the encoder. Android renders
 * straight into its encoder's surface, so this costs more battery here.
 */
@OptIn(ExperimentalForeignApi::class)
class VrmLiveFeeder(private val stage: VrmStage) : LiveFeeder {
    private var offscreen: SCNRenderer? = null
    @Volatile private var viewSize: Pair<Double, Double>? = null

    /** Main thread, before going live: the stage's shape on screen. */
    fun measure() {
        viewSize = stage.view.bounds.useContents { size.width to size.height }
    }

    override fun frameSize(shortSide: Int): Pair<Int, Int>? {
        val (vw, vh) = viewSize ?: return null
        if (vw < 1.0 || vh < 1.0) return null
        return if (vw <= vh) shortSide to even(shortSide * vh / vw) else even(shortSide * vw / vh) to shortSide
    }

    override fun begin(width: Int, height: Int, streamer: IosLiveStreamer) {
        val r = SCNRenderer.rendererWithDevice(null, options = null)
        r.setScene(stage.scene)
        r.setPointOfView(stage.camera())
        offscreen = r
        stage.afterFrame = { time ->
            if (streamer.wantsFrame()) {
                val image = runCatching {
                    r.snapshotAtTime(time, withSize = CGSizeMake(width.toDouble(), height.toDouble()), antialiasingMode = SCNAntialiasingMode.SCNAntialiasingModeMultisampling2X)
                }.getOrNull()
                val buffer = if (image != null) PixelFrames.fromImage(image, width, height, stage.captureLayers) else null
                if (buffer != null) try { streamer.submit(buffer) } finally { CVPixelBufferRelease(buffer) }
            }
        }
    }

    override fun end() {
        stage.afterFrame = null
        offscreen = null
    }

    private fun even(v: Double): Int = ((v.toInt() / 2) * 2).coerceAtLeast(2)
}

/** Pictures as the frames video encoders take. */
@OptIn(ExperimentalForeignApi::class)
object PixelFrames {
    /** [image] drawn into a new [width]×[height] BGRA pixel buffer, with
     *  [layers] over it; the caller releases it (CVPixelBufferRelease).
     *  Null if it can't be made. */
    fun fromImage(image: UIImage, width: Int, height: Int, layers: List<CaptureLayer> = emptyList()): CVPixelBufferRef? {
        val cg = image.CGImage ?: return null
        return memScoped {
            val bufferVar = alloc<CVPixelBufferRefVar>()
            if (CVPixelBufferCreate(null, width.toULong(), height.toULong(), kCVPixelFormatType_32BGRA, null, bufferVar.ptr) != kCVReturnSuccess) return@memScoped null
            val buffer = bufferVar.value ?: return@memScoped null
            val drawn = draw(buffer) { context ->
                CGContextDrawImage(context, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()), cg)
                drawLayers(context, width, height, layers)
            }
            if (!drawn) { CVPixelBufferRelease(buffer); null } else buffer
        }
    }

    /** Draws [layers] straight onto a camera frame (which must be BGRA —
     *  the Camera page asks its camera for that). */
    fun addLayers(buffer: CVPixelBufferRef, layers: List<CaptureLayer>) {
        if (layers.isEmpty() || CVPixelBufferGetPixelFormatType(buffer) != kCVPixelFormatType_32BGRA) return
        val w = CVPixelBufferGetWidth(buffer).toInt()
        val h = CVPixelBufferGetHeight(buffer).toInt()
        draw(buffer) { context -> drawLayers(context, w, h, layers) }
    }

    private inline fun draw(buffer: CVPixelBufferRef, block: (CGContextRef) -> Unit): Boolean {
        CVPixelBufferLockBaseAddress(buffer, 0u)
        val space = CGColorSpaceCreateDeviceRGB()
        val context = CGBitmapContextCreate(
            CVPixelBufferGetBaseAddress(buffer), CVPixelBufferGetWidth(buffer), CVPixelBufferGetHeight(buffer), 8u,
            CVPixelBufferGetBytesPerRow(buffer), space,
            CGImageAlphaInfo.kCGImageAlphaPremultipliedFirst.value or kCGBitmapByteOrder32Little
        )
        if (context != null) {
            block(context)
            CGContextRelease(context)
        }
        CGColorSpaceRelease(space)
        CVPixelBufferUnlockBaseAddress(buffer, 0u)
        return context != null
    }

    /** (This kind of drawing measures from the bottom edge.) */
    private fun drawLayers(context: CGContextRef, width: Int, height: Int, layers: List<CaptureLayer>) {
        for (l in layers) {
            val layer = l.image.CGImage ?: continue
            CGContextDrawImage(
                context,
                CGRectMake(l.left * width.toDouble(), (1.0 - l.bottom) * height, (l.right - l.left) * width.toDouble(), (l.bottom - l.top) * height.toDouble()),
                layer
            )
        }
    }
}
