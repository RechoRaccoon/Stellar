package com.mediaviewer.vrm

import com.mediaviewer.platform.IosFaceBridge
import com.mediaviewer.platform.IosFaceListener
import com.mediaviewer.platform.Log
import com.mediaviewer.platform.nanoTime
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cValue
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerLooper
import platform.AVFoundation.AVQueuePlayer
import platform.AVFoundation.currentItem
import platform.AVFoundation.muted
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.presentationSize
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSTimeInterval
import platform.Foundation.NSURL
import platform.SceneKit.SCNAntialiasingMode
import platform.SceneKit.SCNCamera
import platform.SceneKit.SCNLight
import platform.SceneKit.SCNLightTypeAmbient
import platform.SceneKit.SCNLightTypeDirectional
import platform.SceneKit.SCNMatrix4
import platform.SceneKit.SCNNode
import platform.SceneKit.SCNScene
import platform.SceneKit.SCNSceneRendererDelegateProtocol
import platform.SceneKit.SCNSceneRendererProtocol
import platform.SceneKit.SCNVector3Make
import platform.SceneKit.SCNView
import platform.UIKit.UIColor
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.concurrent.Volatile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tan

/** What VRM mode shows about itself (the debug line and the "no face" hint). */
class VrmStageStatus(
    val faceVisible: Boolean,
    val bones: Int,
    val expressions: Int,
    val springJoints: Int,
    val vertices: Int,
    val triangles: Int,
    val morphTargets: Int,
    /** Body joints / hands Vision found in its latest look (0 when it
     *  isn't looking). */
    val bodyJoints: Int = 0,
    val hands: Int = 0,
    /** Which way up Vision is reading the camera picture (EXIF number). */
    val visionOrientation: Int = 0
)

/** Something drawn over the avatar in photos, recordings and the stream
 *  (a scene card, an effect, a browser window): a picture and where it
 *  goes, as fractions of the frame from its top-left corner. */
class CaptureLayer(val image: UIImage, val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * VRM mode on iOS, minus its buttons: the SceneKit view the avatar is drawn
 * in, the camera that frames it, and the loop that — every drawn frame —
 * takes the newest look at you and poses the avatar with the same
 * retargeter Android uses, then lets the hair swing.
 *
 * Where the looks come from: your face from ARKit's face tracking (the
 * Face ID camera), your body and hands from Apple's Vision framework
 * looking at the same camera picture (see [VisionLift]). Android uses
 * MediaPipe for all three and Filament to draw; the avatar file handling,
 * the pose and expression maths and the spring bones are the same code.
 *
 * Main thread: create, [load], settings, gestures, [release]. SceneKit's
 * render thread: everything that moves the model (inside [Renderer]).
 */
@OptIn(ExperimentalForeignApi::class)
class VrmStage {
    val view: SCNView = SCNView(frame = CGRectMake(0.0, 0.0, 100.0, 100.0), options = null)
    val scene: SCNScene = SCNScene.scene()
    private val cameraNode: SCNNode = SCNNode.node()
    /** Turns the whole avatar (facing fix + your drag); the model hangs off it. */
    private val turnNode: SCNNode = SCNNode.node()
    /** Lit mode's two lights: an even fill, and one from over the viewer's
     *  shoulder that travels with the camera (so the face is always the
     *  bright side, like Android's rig). */
    private val ambientNode: SCNNode = SCNNode.node()
    private val keyNode: SCNNode = SCNNode.node()

    private class Loaded(
        val model: VrmScene,
        val vrm: VrmData,
        val target: RetargetTarget,
        val spring: VrmSpringSim?,
        /** Where the head is at rest, and how tall the avatar is (file units). */
        val headY: Float,
        val height: Float,
        /** "Follow my head": the point between the avatar's eyes at rest
         *  (or its head bone, for rigs without eye bones) and how far
         *  apart its eyes are. */
        val eyeAnchor: FloatArray,
        val eyeDistance: Float,
        val anchorIsEyes: Boolean
    )

    @Volatile private var loaded: Loaded? = null
    @Volatile private var released = false

    // ── what the buttons and gestures set (read on the render thread) ──
    /** Your drag: degrees about the vertical axis. */
    @Volatile var yawDegrees = 0f
    /** Pinch: 1 = head and shoulders. */
    @Volatile var zoom = 1f
    /** Vertical drag: how far the camera is raised, as a share of the avatar's height. */
    @Volatile var lift = 0f
    @Volatile var springBones = true
    /** The avatar's head sits where yours is in the camera's (mirrored)
     *  picture, and is as big as yours looks — video-call framing. */
    @Volatile var followHead = true
    /** Blink tracking off; the eyes are [eyeClosed] shut (0 open … 1). */
    @Volatile var manualEyes = false
    @Volatile var eyeClosed = 0f
    /** Flat, unlit colours (how toon avatars are painted to be seen). */
    @Volatile var fullBright = false
    /** Lit mode's brightness: 1 = normal (0.4 … 1.6). */
    @Volatile var lightLevel = 1f
    /** Ids of the avatar parts not drawn (see [parts]). */
    @Volatile var hiddenParts: Set<String> = emptySet()
    @Volatile var armIk = false
    @Volatile var armsNeedHands = false
    /** Troubleshooting: the head turns the opposite way to yours. */
    @Volatile var flipHead = false
    /** Troubleshooting: a wink closes the wrong eye. */
    @Volatile var swapFaceSides = false
    /** Troubleshooting: with Follow my head, the avatar slides the
     *  opposite way to you. */
    @Volatile var flipFollow = false

    // Tracking beyond the face. Set through [setTracking].
    @Volatile private var trackBody = false
    @Volatile private var trackLegs = false
    @Volatile private var trackHands = false
    @Volatile private var headFallback = true
    @Volatile private var fastTracking = false

    /** Drawn over the avatar in everything captured (main thread sets). */
    @Volatile var captureLayers: List<CaptureLayer> = emptyList()

    // ── the newest look at the face (written on the main thread) ──
    private class FaceSample(val matrix: FloatArray, val scores: Map<String, Float>, val atNanos: Long)
    @Volatile private var face: FaceSample? = null
    @Volatile private var faceLost = true
    private val shapeNames = HashMap<String, String>()
    /** The camera's focal length ÷ its picture's longer side (0 = not told yet). */
    @Volatile private var focal = 0f

    // ── the newest look at the body and hands (written on the main thread) ──
    private val lifter = VisionLift()
    private class Lifted(val pose: LiftedPose, val atNanos: Long)
    @Volatile private var lifted: Lifted? = null
    /** The raw points of that look, for the tracking preview. */
    @Volatile var lastVision: VisionSample? = null
        private set
    @Volatile private var handSeen: Map<String, Long> = emptyMap()
    @Volatile private var pictureAspect = 0f

    // Which way up Vision should read the camera picture. The phone can't
    // be asked; it's found by trying — see [judgeOrientation].
    private var orientationIndex = 0
    private var orientationGood = 0
    private var orientationBad = 0
    private var orientationBlind = 0
    /** Called (main thread) when a different way up has been settled on,
     *  so it can be remembered for next time. */
    var onVisionOrientation: ((Int) -> Unit)? = null
    private var visionSent = ""

    /** The view's shape, width ÷ height (main thread tells, render thread reads). */
    @Volatile private var viewAspect = 0.46f

    /** Called on the main thread with a fresh status about twice a second. */
    var onStatus: ((VrmStageStatus) -> Unit)? = null
    /** Called on the render thread after each drawn frame (the recorder). */
    @Volatile var afterFrame: ((time: Double) -> Unit)? = null

    private val listener = object : IosFaceListener {
        override fun onFace(matrix: List<Float>, names: List<String>, values: List<Float>) {
            if (matrix.size != 16) return
            val scores = HashMap<String, Float>(names.size * 2)
            for (i in names.indices) {
                val value = values.getOrNull(i) ?: continue
                scores[shapeNames.getOrPut(names[i]) { arkitName(names[i]) }] = value
            }
            face = FaceSample(FloatArray(16) { matrix[it] }, scores, nanoTime())
            if (faceLost) { faceLost = false; syncVision() }
        }

        override fun onFaceLost() {
            if (!faceLost) { faceLost = true; syncVision() }
        }

        override fun onCamera(focal: Float) {
            if (focal > 0.2f && focal < 5f) this@VrmStage.focal = focal
        }

        override fun onVision(body: List<Float>, hands: List<Float>, aspect: Float) {
            if (released) return
            val now = nanoTime()
            val sample = VisionSample(
                FloatArray(body.size) { body[it] }, FloatArray(hands.size - hands.size % 63) { hands[it] },
                if (aspect > 0.1f && aspect < 10f) aspect else 0.75f, now
            )
            pictureAspect = sample.aspect
            lastVision = sample
            val f = face
            val eyes = if (f != null && !faceLost) floatArrayOf(f.matrix[12], -f.matrix[13], -f.matrix[14]) else null
            val pose = runCatching {
                lifter.update(
                    sample, wantBody = trackBody, wantHands = trackHands, wantHead = headFallback,
                    eyesCamera = eyes?.takeIf { it[2] > 0.05f }, focal = focalInPictureHeights(sample.aspect)
                )
            }.getOrElse { Log.e("VrmStage", "Reading the body failed", it); return }
            if (pose.hands.isNotEmpty()) handSeen = handSeen + pose.hands.keys.associateWith { now }
            lifted = Lifted(pose, now)
            judgeOrientation(pose.upright, sample.body.isNotEmpty())
        }
    }

    /** ARKit's own spelling ("eyeBlink_L") → the one the retargeter, like
     *  MediaPipe on Android, uses ("eyeBlinkLeft"). */
    private fun arkitName(raw: String): String = when {
        raw.endsWith("_L") -> raw.dropLast(2) + "Left"
        raw.endsWith("_R") -> raw.dropLast(2) + "Right"
        else -> raw
    }

    /** The focal length in the units [VisionLift] works in. */
    private fun focalInPictureHeights(aspect: Float): Float {
        // (Not told yet: a typical front camera.)
        val f = if (focal > 0f) focal else 0.8f
        return if (aspect > 1f) f * aspect else f
    }

    private val renderer = Renderer()

    init {
        val camera = SCNCamera.camera()
        camera.setFieldOfView(FOV_DEGREES)
        camera.setZNear(0.02)
        camera.setZFar(200.0)
        cameraNode.setCamera(camera)
        scene.rootNode.addChildNode(cameraNode)
        scene.rootNode.addChildNode(turnNode)

        val ambient = SCNLight.light()
        ambient.setType(SCNLightTypeAmbient)
        ambient.setColor(UIColor.whiteColor)
        ambientNode.setLight(ambient)
        ambientNode.setHidden(true)
        scene.rootNode.addChildNode(ambientNode)
        val key = SCNLight.light()
        key.setType(SCNLightTypeDirectional)
        key.setColor(UIColor.whiteColor)
        key.setCastsShadow(false)
        keyNode.setLight(key)
        // A directional light shines along its node's -Z; tipped a little
        // down and to the right it comes from above the viewer's left
        // shoulder (the direction (0.15, -0.35, -1) Android uses).
        keyNode.setEulerAngles(SCNVector3Make(-0.337f, -0.149f, 0f))
        keyNode.setHidden(true)
        cameraNode.addChildNode(keyNode)

        view.setScene(scene)
        view.setPointOfView(cameraNode)
        view.setAntialiasingMode(SCNAntialiasingMode.SCNAntialiasingModeMultisampling4X)
        view.setAllowsCameraControl(false)
        view.setAutoenablesDefaultLighting(false)
        // The avatar is posed every frame, so the view never idles.
        view.setRendersContinuously(true)
        view.setPreferredFramesPerSecond(60)
        view.setDelegate(renderer)
        view.setUserInteractionEnabled(false)
        setBackground(0.05f, 0.05f, 0.07f)
    }

    // ── background ──

    private var backgroundColor: UIColor = UIColor.blackColor
    private var mediaPath: String? = null
    private var mediaAspect = 0f
    private var player: AVQueuePlayer? = null
    private var looper: AVPlayerLooper? = null

    fun setBackground(red: Float, green: Float, blue: Float) {
        val color = UIColor.colorWithRed(red.toDouble(), green = green.toDouble(), blue = blue.toDouble(), alpha = 1.0)
        backgroundColor = color
        view.setBackgroundColor(color)
        if (mediaPath == null) scene.background.setContents(color)
    }

    /**
     * A picture or a looping (silent) video behind the avatar, filling the
     * view; null goes back to the plain colour. Being part of the scene,
     * it's in photos, recordings and the stream as well.
     */
    fun setBackgroundMedia(path: String?, isVideo: Boolean) {
        if (path == mediaPath) return
        runCatching { player?.pause() }
        player = null; looper = null
        mediaPath = path
        mediaAspect = 0f
        if (path == null) {
            scene.background.setContents(backgroundColor)
            scene.background.setContentsTransform(identity())
            return
        }
        runCatching {
            if (isVideo) {
                val item = AVPlayerItem(uRL = NSURL.fileURLWithPath(path))
                val p = AVQueuePlayer()
                looper = AVPlayerLooper.playerLooperWithPlayer(p, templateItem = item)
                p.muted = true
                scene.background.setContents(p)
                p.play()
                player = p
            } else {
                val picture = uprightPicture(path) ?: error("not a picture")
                mediaAspect = picture.size.useContents { if (height > 0.0) (width / height).toFloat() else 0f }
                scene.background.setContents(picture)
            }
        }.onFailure {
            Log.e("VrmStage", "The background couldn't be shown", it)
            mediaPath = null
            scene.background.setContents(backgroundColor)
        }
        fitBackground()
    }

    /** The picture redrawn the right way up (a camera photo is stored
     *  sideways with a note saying so, which SceneKit doesn't read) and no
     *  more than 1600 points on its longer side. */
    private fun uprightPicture(path: String): UIImage? {
        val source = UIImage.imageWithContentsOfFile(path) ?: return null
        val (w, h) = source.size.useContents { width to height }
        if (w < 1.0 || h < 1.0) return null
        val scale = minOf(1.0, 1600.0 / maxOf(w, h))
        UIGraphicsBeginImageContextWithOptions(CGSizeMake(w * scale, h * scale), true, 1.0)
        source.drawInRect(CGRectMake(0.0, 0.0, w * scale, h * scale))
        val out = UIGraphicsGetImageFromCurrentImageContext()
        UIGraphicsEndImageContext()
        return out
    }

    /** Crops the background to the view's shape instead of stretching it. */
    private fun fitBackground() {
        if (mediaPath == null) return
        val media = mediaAspect
        val shown = viewAspect
        if (media <= 0f || shown <= 0f) return
        val m = if (media > shown) {
            // Wider than the view: the middle of its width is shown.
            val s = shown / media
            cValue<SCNMatrix4> { m11 = s; m22 = 1f; m33 = 1f; m44 = 1f; m41 = (1f - s) / 2f }
        } else {
            val s = media / shown
            cValue<SCNMatrix4> { m11 = 1f; m22 = s; m33 = 1f; m44 = 1f; m42 = (1f - s) / 2f }
        }
        scene.background.setContentsTransform(m)
    }

    private fun identity(): CValue<SCNMatrix4> = cValue { m11 = 1f; m22 = 1f; m33 = 1f; m44 = 1f }

    /** Main thread, whenever the page is laid out. */
    fun setViewSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val aspect = width.toFloat() / height
        if (abs(aspect - viewAspect) < 0.001f) return
        viewAspect = aspect
        fitBackground()
    }

    /** 30, 60 or 120 drawn frames a second at most. */
    fun setMaxFps(fps: Int) {
        view.setPreferredFramesPerSecond(fps.coerceIn(15, 120).toLong())
    }

    // ── tracking beyond the face ──

    /**
     * Which of your movements, besides the face, the avatar follows.
     * [headFallback]: while the face tracker can't see your face (hair, a
     * hand, turned away), the body tracker's idea of your head turns the
     * avatar's. [fast]: about 30 looks a second instead of 15.
     */
    fun setTracking(body: Boolean, legs: Boolean, hands: Boolean, headFallback: Boolean, fast: Boolean) {
        trackBody = body; trackLegs = legs; trackHands = hands
        this.headFallback = headFallback; fastTracking = fast
        if (!body && !hands && !headFallback) { lifted = null; lastVision = null }
        syncVision()
    }

    /** Troubleshooting switches for the body and hands (see [VisionLift]). */
    fun setVisionFixes(swapLabels: Boolean, flipX: Boolean) {
        lifter.swapLabels = swapLabels
        lifter.flipX = flipX
    }

    fun setSmoothing(strength: Double) {
        lifter.setSmoothing(strength)
        AvatarRetargeter.smoothingScale = strength.toFloat()
    }

    /** The way up to start with (remembered from last time), as an EXIF number. */
    fun setVisionOrientation(exif: Int) {
        val i = ORIENTATIONS.indexOf(exif)
        if (i >= 0) { orientationIndex = i; syncVision() }
    }

    /** Tells the Swift side what Vision should be doing now. Main thread. */
    private fun syncVision() {
        if (released || !tracking) return
        val wanted = trackBody || trackHands || headFallback
        // The body is looked for whenever anything is: it's what tells
        // hands apart and which way up the picture is.
        val interval = when {
            trackBody || trackHands -> if (fastTracking) 33 else 66
            // Only standing by for the head: a few looks a second keep it
            // warm while the face is seen; full speed once it's lost.
            faceLost -> 66
            else -> 350
        }
        val key = "$wanted/$trackHands/${ORIENTATIONS[orientationIndex]}/$interval"
        if (key == visionSent) return
        visionSent = key
        runCatching { IosFaceBridge.tracker?.setVision(wanted, wanted && trackHands, ORIENTATIONS[orientationIndex], interval) }
    }

    /**
     * Finds which way up the camera picture is by watching what Vision
     * makes of it: someone standing on their head, or nobody at all while
     * the face tracker can plainly see a face, means it's being read the
     * wrong way round — so the next way is tried.
     */
    private fun judgeOrientation(upright: Int, sawBody: Boolean) {
        when {
            upright > 0 -> { orientationGood++; orientationBad = 0; orientationBlind = 0 }
            upright < 0 -> orientationBad++
            !sawBody && !faceLost -> orientationBlind++
        }
        val settled = orientationGood >= 10
        val wrong = orientationBad >= (if (settled) 15 else 4) || (!settled && orientationBlind >= 20)
        if (!wrong) return
        orientationIndex = (orientationIndex + 1) % ORIENTATIONS.size
        orientationGood = 0; orientationBad = 0; orientationBlind = 0
        lifted = null
        syncVision()
        onVisionOrientation?.invoke(ORIENTATIONS[orientationIndex])
    }

    // ── the avatar ──

    /**
     * Reads a .vrm file and puts the avatar on stage, replacing any that
     * was there. Returns null when it worked, or what went wrong, in words
     * for the person looking at the screen.
     */
    suspend fun load(bytes: ByteArray, options: VrmSceneOptions): String? {
        val built = withContext(Dispatchers.IO) {
            runCatching {
                val glb = GlbReader.read(bytes) ?: return@runCatching "That file isn't a VRM avatar (it isn't a .glb file inside)."
                val vrm = VrmParser.parse(glb.json) ?: return@runCatching "That file is a 3D model, but not a VRM avatar."
                val doc = GltfDoc(glb)
                val model = VrmScene(doc, options)
                if (model.triangleCount == 0) return@runCatching "That avatar has nothing in it that can be drawn."
                val target = AvatarRetargeter.buildTarget(model, vrm)
                val owned = target.bones.values.map { it.entity }.toSet()
                val springData = VrmSpringParser.parse(glb.json)
                val spring = if (springData.joints.isEmpty()) null
                    else VrmSpringSim(doc, springData, owned, { model.currentLocal(it) }, { n, m -> model.setLocal(n, m) })
                val bounds = doc.bounds()
                val height = bounds?.let { it[4] - it[1] }?.takeIf { it > 0.05f } ?: 1.5f
                val head = target.bones["head"]?.restWorldPosition
                val headY = head?.get(1) ?: (bounds?.let { it[1] + height * 0.9f } ?: 1.4f)
                // Follow my head: the avatar's eyes, or its head bone.
                val le = target.bones["leftEye"]?.restWorldPosition
                val re = target.bones["rightEye"]?.restWorldPosition
                val spacing = if (le != null && re != null) distance(le, re) else 0f
                val footY = listOfNotNull(target.bones["leftFoot"], target.bones["rightFoot"]).minOfOrNull { it.restWorldPosition[1] }
                val eyeHeight = if (head != null && footY != null) head[1] - footY else height * 0.9f
                val usingEyes = spacing > 1e-5f && le != null && re != null
                val anchor = if (usingEyes) floatArrayOf((le!![0] + re!![0]) / 2f, (le[1] + re[1]) / 2f, (le[2] + re[2]) / 2f)
                    else head ?: floatArrayOf(0f, headY, 0f)
                Loaded(
                    model, vrm, target, spring, headY, height,
                    eyeAnchor = anchor,
                    eyeDistance = if (usingEyes) spacing else (eyeHeight * 0.042f).coerceAtLeast(0.01f),
                    anchorIsEyes = usingEyes
                )
            }.getOrElse { e ->
                Log.e("VrmStage", "Loading the avatar failed", e)
                "The avatar couldn't be loaded: " + (e.message ?: "unknown error")
            }
        }
        if (built !is Loaded) return built as? String ?: "The avatar couldn't be loaded."
        if (released) return null
        // Off stage first, so the render thread never sees half of each.
        val old = loaded
        loaded = null
        old?.model?.modelRoot?.removeFromParentNode()
        turnNode.addChildNode(built.model.modelRoot)
        loaded = built
        return null
    }

    private fun distance(a: FloatArray, b: FloatArray): Float {
        val dx = a[0] - b[0]; val dy = a[1] - b[1]; val dz = a[2] - b[2]
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }

    fun hasAvatar(): Boolean = loaded != null

    /** The loaded avatar's hideable pieces (empty while there's none). */
    fun parts(): List<VrmPart> = loaded?.model?.parts ?: emptyList()

    private var tracking = false

    fun startTracking() {
        tracking = true
        visionSent = ""
        IosFaceBridge.tracker?.start(listener)
        syncVision()
    }

    fun stopTracking() {
        tracking = false
        runCatching { IosFaceBridge.tracker?.setVision(false, false, ORIENTATIONS[orientationIndex], 66) }
        IosFaceBridge.tracker?.stop()
        faceLost = true
    }

    /** Stops everything; the stage can't be used afterwards. */
    fun release() {
        released = true
        stopTracking()
        afterFrame = null
        onStatus = null
        onVisionOrientation = null
        runCatching { player?.pause() }
        player = null; looper = null
        view.setDelegate(null)
        view.setScene(null)
        loaded?.model?.modelRoot?.removeFromParentNode()
        loaded = null
        captureLayers = emptyList()
    }

    /** The picture on stage right now — the avatar plus whatever is set to
     *  be captured over it, never the buttons (main thread). */
    fun snapshot(): UIImage? = runCatching {
        val base = view.snapshot() ?: return@runCatching null
        val layers = captureLayers
        if (layers.isEmpty()) return@runCatching base
        val (w, h) = base.size.useContents { width to height }
        UIGraphicsBeginImageContextWithOptions(CGSizeMake(w, h), true, base.scale)
        base.drawInRect(CGRectMake(0.0, 0.0, w, h))
        for (l in layers) l.image.drawInRect(CGRectMake(l.left * w, l.top * h, (l.right - l.left) * w, (l.bottom - l.top) * h))
        val out = UIGraphicsGetImageFromCurrentImageContext()
        UIGraphicsEndImageContext()
        out ?: base
    }.getOrNull()

    private inner class Renderer : NSObject(), SCNSceneRendererDelegateProtocol {
        private var lastNanos = 0L
        private var lastStatusNanos = 0L
        private var lastFaceNanos = 0L
        private var springWasOn = true
        private var litApplied: Boolean? = null
        private var levelApplied = -1f
        // Follow my head: where the camera is now (eased towards where it
        // should be, so 15 looks a second still move smoothly).
        private var followX = 0f
        private var followY = 0f
        private var followD = 0f
        private var followValid = false

        /** Before each frame is drawn: pose the avatar. */
        override fun renderer(renderer: SCNSceneRendererProtocol, updateAtTime: NSTimeInterval) {
            if (released) return
            val now = nanoTime()
            val dt = if (lastNanos == 0L) 1f / 60f else ((now - lastNanos) / 1e9f).coerceIn(0.001f, 0.1f)
            lastNanos = now
            val l = loaded
            if (l != null) runCatching { look(l); pose(l, dt, now) }.onFailure { Log.e("VrmStage", "Posing failed", it) }
            placeCamera(l, dt)
            if (now - lastStatusNanos > 500_000_000L) {
                lastStatusNanos = now
                reportStatus(l)
            }
        }

        override fun renderer(renderer: SCNSceneRendererProtocol, didRenderScene: SCNScene, atTime: NSTimeInterval) {
            if (released) return
            afterFrame?.invoke(atTime)
        }

        /** Parts and lighting, whenever their settings changed. */
        private fun look(l: Loaded) {
            l.model.setHiddenParts(hiddenParts)
            val lit = !fullBright
            l.model.setLit(lit)
            val level = lightLevel
            if (litApplied != lit || levelApplied != level) {
                litApplied = lit; levelApplied = level
                ambientNode.setHidden(!lit)
                keyNode.setHidden(!lit)
                // SceneKit's "1000" is a light at full strength: an even
                // fill a little over half, and as much again from the front.
                ambientNode.light?.setIntensity(580.0 * level)
                keyNode.light?.setIntensity(520.0 * level)
            }
        }

        private fun pose(l: Loaded, dt: Float, now: Long) {
            val sample = face
            val seesFace = sample != null && !faceLost
            // Body and hands: only looks from the last half second count.
            val body = lifted?.takeIf { now - it.atNanos < 500_000_000L }?.pose
            var matrix: FloatArray? = null
            // Only a look that's new since the last frame turns the head;
            // between looks (and while the face is hidden) it holds.
            if (sample != null && seesFace && sample.atNanos != lastFaceNanos) {
                lastFaceNanos = sample.atNanos
                matrix = sample.matrix
            } else if (!seesFace && headFallback) {
                matrix = body?.headMatrix
            }
            if (matrix != null && flipHead) {
                val q = AvatarRetargeter.rotationOf(matrix)
                matrix = Quaternion(q.x, -q.y, -q.z, q.w).toColumnMajorMatrix()
            }
            val hands = if (trackHands) body?.hands ?: emptyMap() else emptyMap()
            // "Arms need hands": an arm follows the body tracker only while
            // its hand is (or was, a moment ago) being tracked.
            val armSides = if (armsNeedHands && trackBody && trackHands) {
                handSeen.filterValues { now - it <= 300_000_000L }.keys
            } else null
            AvatarRetargeter.applyPose(
                l.target,
                TrackingFrame(
                    faceMatrix = matrix,
                    body = if (trackBody) body?.body?.takeIf { it.isNotEmpty() } else null,
                    trackLegs = trackBody && trackLegs,
                    hands = hands,
                    armIk = armIk,
                    armBodySides = armSides
                )
            )
            if (sample != null && seesFace) {
                var scores = if (swapFaceSides) swapSides(sample.scores) else sample.scores
                if (manualEyes) {
                    val shut = eyeClosed
                    scores = scores + mapOf(
                        "eyeBlinkLeft" to shut, "eyeBlinkRight" to shut,
                        "eyeSquintLeft" to 0f, "eyeSquintRight" to 0f,
                        "eyeWideLeft" to 0f, "eyeWideRight" to 0f
                    )
                }
                // ARKit's blink is already 0 open … 1 closed (MediaPipe's on
                // Android needs reshaping), so it's passed through as it is.
                AvatarRetargeter.applyExpressions(l.target, l.vrm, scores, remapBlink = false)
            }
            val spring = l.spring
            if (spring != null) {
                if (springBones) spring.update(dt) else if (springWasOn) spring.reset()
                springWasOn = springBones
            }
        }

        private fun swapSides(scores: Map<String, Float>): Map<String, Float> {
            val out = HashMap<String, Float>(scores.size * 2)
            for ((k, v) in scores) {
                out[when {
                    k.endsWith("Left") -> k.removeSuffix("Left") + "Right"
                    k.endsWith("Right") -> k.removeSuffix("Right") + "Left"
                    else -> k
                }] = v
            }
            return out
        }

        private fun placeCamera(l: Loaded?, dt: Float) {
            val height = l?.height ?: 1.5f
            val headY = l?.headY ?: 1.4f
            // A model that faces away from +Z (VRM 0.x) is turned to face us.
            val flipped = l?.target?.facesNegativeZ == true
            val facing = if (flipped) 180f else 0f
            val z = zoom.coerceIn(0.35f, 4f)
            val tanHalf = tan((FOV_DEGREES / 2.0 * PI / 180.0).toFloat())

            if (followHead && l != null && follow(l, flipped, z, tanHalf, dt)) {
                // (Following, the avatar always faces you; drags wait.)
                turnNode.setTransform(yaw(facing))
                cameraNode.setTransform(translation(followX, followY, followD))
                return
            }
            followValid = false
            turnNode.setTransform(yaw(facing + yawDegrees))
            // Head and shoulders by default; pinch closes in on the face.
            // How much of the avatar's height the picture shows top to bottom.
            val span = height * 0.46f / z
            val distance = (span / 2f) / tanHalf
            // Zoomed out the middle of the picture drops towards the chest;
            // zoomed in it rises to the face.
            val aim = headY - height * (0.075f / z) + lift * height
            cameraNode.setTransform(translation(0f, aim, distance))
        }

        /**
         * Follow my head. The camera's picture is laid over the screen like
         * a video call (scaled to cover it, mirrored); the avatar's eyes go
         * where yours are in it, and are as far apart as yours look. Here
         * the avatar stays put and the stage's camera moves instead — the
         * same picture either way.
         *
         * False while there's nothing to follow yet (the ordinary framing
         * is used then); once following, a hidden face just holds the
         * last placement.
         */
        private fun follow(l: Loaded, flipped: Boolean, zoomNow: Float, tanHalf: Float, dt: Float): Boolean {
            val sample = face
            if (sample == null || faceLost) return followValid
            val depth = -sample.matrix[14]
            if (depth < 0.08f || depth > 5f) return followValid
            val shown = viewAspect
            val picture = if (pictureAspect > 0f) pictureAspect else if (shown <= 1f) 0.75f else 1.333f
            val f = focalInPictureHeights(picture)
            // Screen heights per picture height, so the picture covers the screen.
            val fill = maxOf(1f, shown / picture)
            var ndcX = -(f * sample.matrix[12] / depth * fill) / (shown / 2f)   // mirrored
            if (flipFollow) ndcX = -ndcX
            var ndcY = (f * sample.matrix[13] / depth * fill) / 0.5f
            // Your eye spacing on screen, in screen heights (63 mm in life).
            val eyeOnScreen = f * 0.063f / depth * fill
            if (!l.anchorIsEyes) ndcY -= 0.8f * eyeOnScreen / 0.5f   // a head bone sits below the eyes
            ndcX = ndcX.coerceIn(-1.4f, 1.4f); ndcY = ndcY.coerceIn(-1.4f, 1.4f)
            val d = (l.eyeDistance / (2f * tanHalf * eyeOnScreen) / zoomNow).coerceIn(l.height * 0.08f, l.height * 40f)
            val sign = if (flipped) -1f else 1f
            val targetX = sign * l.eyeAnchor[0] - ndcX * d * tanHalf * shown
            val targetY = l.eyeAnchor[1] - ndcY * d * tanHalf
            val targetD = sign * l.eyeAnchor[2] + d
            if (!followValid) {
                followX = targetX; followY = targetY; followD = targetD
                followValid = true
            } else {
                val a = 1f - exp(-dt / 0.08f)
                followX += (targetX - followX) * a
                followY += (targetY - followY) * a
                followD += (targetD - followD) * a
            }
            return true
        }

        private fun reportStatus(l: Loaded?) {
            val callback = onStatus ?: return
            val vision = lastVision?.takeIf { nanoTime() - it.atNanos < 1_000_000_000L }
            var joints = 0
            if (vision != null) { var i = 2; while (i < vision.body.size) { if (vision.body[i] >= VisionLift.MIN_CONFIDENCE) joints++; i += 3 } }
            val status = VrmStageStatus(
                faceVisible = !faceLost,
                bones = l?.target?.bones?.size ?: 0,
                expressions = l?.vrm?.expressions?.size ?: 0,
                springJoints = l?.spring?.jointCount ?: 0,
                vertices = l?.model?.vertexCount ?: 0,
                triangles = l?.model?.triangleCount ?: 0,
                morphTargets = l?.model?.morphTargetCount ?: 0,
                bodyJoints = joints,
                hands = (vision?.hands?.size ?: 0) / 63,
                visionOrientation = ORIENTATIONS[orientationIndex]
            )
            dispatch_async(dispatch_get_main_queue()) {
                if (!released) {
                    // (A background video tells its shape once it has started.)
                    val p = player
                    if (p != null && mediaAspect <= 0f) {
                        val shape = p.currentItem?.presentationSize?.useContents { if (height > 1.0) (width / height).toFloat() else 0f } ?: 0f
                        if (shape > 0f) { mediaAspect = shape; fitBackground() }
                    }
                    callback(status)
                }
            }
        }
    }

    private fun yaw(degrees: Float): CValue<SCNMatrix4> {
        val r = (degrees * PI / 180.0).toFloat()
        val c = cos(r); val s = sin(r)
        return cValue {
            m11 = c; m12 = 0f; m13 = -s; m14 = 0f
            m21 = 0f; m22 = 1f; m23 = 0f; m24 = 0f
            m31 = s; m32 = 0f; m33 = c; m34 = 0f
            m41 = 0f; m42 = 0f; m43 = 0f; m44 = 1f
        }
    }

    private fun translation(x: Float, y: Float, z: Float): CValue<SCNMatrix4> = cValue {
        m11 = 1f; m12 = 0f; m13 = 0f; m14 = 0f
        m21 = 0f; m22 = 1f; m23 = 0f; m24 = 0f
        m31 = 0f; m32 = 0f; m33 = 1f; m34 = 0f
        m41 = x; m42 = y; m43 = z; m44 = 1f
    }

    /** The camera a recorder should film through. */
    fun camera(): SCNNode = cameraNode

    companion object {
        /** Top-to-bottom field of view, degrees: a portrait lens. */
        const val FOV_DEGREES = 24.0
        /** The ways up Vision can read the camera picture, in the order
         *  they're tried: the two an upright phone can need, then the two
         *  for one held sideways. */
        private val ORIENTATIONS = intArrayOf(6, 8, 1, 3)
    }
}
