package com.mediaviewer.vrm

import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One look at your body and hands by Apple's Vision framework (see
 * StellarFaceTracker.swift), as plain numbers.
 *
 * [body]: 19 joints × (x, y, confidence), or empty when nobody was found.
 * [hands]: 21 joints × (x, y, confidence) per hand found (up to two).
 * x and y are Vision's: 0…1 across the upright picture, measured from its
 * LEFT and BOTTOM edges. [aspect] is that picture's width ÷ height.
 */
class VisionSample(val body: FloatArray, val hands: FloatArray, val aspect: Float, val atNanos: Long)

/** The order the Swift side sends body joints in. Left/right are the
 *  person's own. */
object VisionJoint {
    const val NOSE = 0
    const val LEFT_EYE = 1
    const val RIGHT_EYE = 2
    const val LEFT_EAR = 3
    const val RIGHT_EAR = 4
    const val LEFT_SHOULDER = 5
    const val RIGHT_SHOULDER = 6
    const val LEFT_ELBOW = 7
    const val RIGHT_ELBOW = 8
    const val LEFT_WRIST = 9
    const val RIGHT_WRIST = 10
    const val LEFT_HIP = 11
    const val RIGHT_HIP = 12
    const val LEFT_KNEE = 13
    const val RIGHT_KNEE = 14
    const val LEFT_ANKLE = 15
    const val RIGHT_ANKLE = 16
    const val COUNT = 19
}

/** What [VisionLift.update] worked out from one [VisionSample]. */
class LiftedPose(
    /** By BlazePose index, for [TrackingFrame.body]. Empty = no body. */
    val body: Map<Int, BodyPoint>,
    /** By avatar side, for [TrackingFrame.hands]. */
    val hands: Map<String, TrackedHand>,
    /** Where the head is turned, judged from the nose, eyes and ears — the
     *  stand-in while the face tracker can't see the face. Null if those
     *  weren't seen. */
    val headMatrix: FloatArray?,
    /** +1: the person in the picture is the right way up; -1: upside
     *  down (the picture is being read the wrong way round); 0: can't tell. */
    val upright: Int
)

/**
 * Turns Vision's flat (2-D) body and hand points into the 3-D points the
 * retargeter was written for (MediaPipe's on Android, which measures depth
 * itself).
 *
 * The missing depth is worked out from foreshortening: an arm or finger
 * bone has a fixed real length, so the shorter it looks in the picture,
 * the more it must be pointing at (or away from) the camera. Which of the
 * two can't be told from one picture, so the likely one is taken: arms and
 * hands reach TOWARDS the camera, and fingers curl towards the palm (which
 * way the palm faces IS visible: from which side the thumb is on).
 *
 * Plain Kotlin on purpose, so it can be tried on a desktop
 * (tools/vrm-test) with made-up poses.
 */
class VisionLift {
    /** Troubleshooting: Vision's "left" is taken as the person's right. */
    var swapLabels = false
    /** Troubleshooting: the picture is read mirrored. */
    var flipX = false

    private val poseFilters = OneEuroFilterBank(minCutoff = 1.0, beta = 8.0, dCutoff = 1.0)
    private val handFilters = OneEuroFilterBank(minCutoff = 1.5, beta = 20.0, dCutoff = 1.0)
    private val headFilters = OneEuroFilterBank(minCutoff = 1.0, beta = 1.5, dCutoff = 1.0)

    fun setSmoothing(strength: Double) {
        poseFilters.strength = strength
        handFilters.strength = strength
        headFilters.strength = strength
    }

    // The longest each of these has looked (picture heights): a bone seen
    // side-on shows its full length, so the longest sighting is its length.
    private var shoulderSpan = 0f
    private var upperArm = 0f
    private var foreArm = 0f

    private class P(val x: Float, val y: Float, val c: Float)

    private fun bodyPoint(s: VisionSample, joint: Int): P? {
        val index = if (swapLabels) swapped(joint) else joint
        val i = index * 3
        if (i + 2 >= s.body.size) return null
        val c = s.body[i + 2]
        if (c < MIN_CONFIDENCE) return null
        val x = if (flipX) 1f - s.body[i] else s.body[i]
        // To picture heights, measured from the top-left like MediaPipe.
        return P(x * s.aspect, 1f - s.body[i + 1], c)
    }

    private fun swapped(joint: Int): Int = when (joint) {
        VisionJoint.NOSE, 17, 18 -> joint
        else -> if (joint % 2 == 1) joint + 1 else joint - 1
    }

    /**
     * [wantBody] / [wantHands] / [wantHead]: what to work out.
     * [eyesCamera]: where the face tracker has the middle of your eyes, in
     * metres from the camera (+x picture-right, +y down, +z away), or null.
     * [focal]: the camera's focal length in picture heights.
     */
    fun update(
        s: VisionSample, wantBody: Boolean, wantHands: Boolean, wantHead: Boolean,
        eyesCamera: FloatArray?, focal: Float
    ): LiftedPose {
        val t = s.atNanos / 1e9
        val hasBody = s.body.size >= VisionJoint.COUNT * 3
        val upright = if (hasBody) uprightVote(s) else 0
        val body = if (wantBody && hasBody && upright >= 0) liftBody(s, t) else { poseFilters.resetAll(); emptyMap() }
        val head = if (wantHead && hasBody && upright >= 0) headFrom(s, t) else { headFilters.resetAll(); null }
        val hands = if (wantHands && upright >= 0) liftHands(s, if (hasBody) s else null, eyesCamera, focal, t)
            else { handFilters.resetAll(); emptyMap() }
        return LiftedPose(body, hands, head, upright)
    }

    /**
     * Is the person the right way up? Judged from the face when it's seen
     * (eyes level and above the nose), else from the head being above the
     * shoulders. Lying on its side counts as wrong too: the picture is
     * then being read a quarter-turn out.
     */
    private fun uprightVote(s: VisionSample): Int {
        val nose = bodyPoint(s, VisionJoint.NOSE)
        val le = bodyPoint(s, VisionJoint.LEFT_EYE); val re = bodyPoint(s, VisionJoint.RIGHT_EYE)
        if (nose != null && le != null && re != null) {
            val ex = le.x - re.x; val ey = le.y - re.y
            if (hypot(ex, ey) > 0.004f) {
                if (kotlin.math.abs(ex) < kotlin.math.abs(ey)) return -1
                // (y grows downwards: the nose is below the eyes.)
                return if (nose.y > (le.y + re.y) / 2f) 1 else -1
            }
        }
        val ls = bodyPoint(s, VisionJoint.LEFT_SHOULDER); val rs = bodyPoint(s, VisionJoint.RIGHT_SHOULDER)
        val top = nose ?: le ?: re
        if (ls == null || rs == null || top == null) return 0
        val sx = ls.x - rs.x; val sy = ls.y - rs.y
        val span = hypot(sx, sy)
        if (span < 0.03f) return 0
        if (kotlin.math.abs(sx) < kotlin.math.abs(sy)) return -1
        val gap = (ls.y + rs.y) / 2f - top.y
        return if (gap > span * 0.25f) 1 else if (gap < -span * 0.25f) -1 else 0
    }

    // ── body ──

    private fun liftBody(s: VisionSample, t: Double): Map<Int, BodyPoint> {
        val ls = bodyPoint(s, VisionJoint.LEFT_SHOULDER); val rs = bodyPoint(s, VisionJoint.RIGHT_SHOULDER)
        if (ls == null || rs == null) { poseFilters.resetAll(); return emptyMap() }
        val span = hypot(ls.x - rs.x, ls.y - rs.y)
        // The shoulders' full width is the widest they've looked lately
        // (turning side-on narrows them; that isn't you shrinking).
        shoulderSpan = maxOf(span, shoulderSpan * 0.998f)
        if (shoulderSpan < 0.02f) return emptyMap()
        val metresPerUnit = SHOULDERS_M / shoulderSpan

        val le = bodyPoint(s, VisionJoint.LEFT_ELBOW); val re = bodyPoint(s, VisionJoint.RIGHT_ELBOW)
        val lw = bodyPoint(s, VisionJoint.LEFT_WRIST); val rw = bodyPoint(s, VisionJoint.RIGHT_WRIST)
        fun len(a: P?, b: P?): Float = if (a == null || b == null) 0f else hypot(a.x - b.x, a.y - b.y)
        // Typical proportions to start from, corrected by what's seen.
        upperArm = maxOf(len(ls, le), len(rs, re), upperArm * 0.998f).coerceIn(shoulderSpan * 0.70f, shoulderSpan * 1.05f)
        foreArm = maxOf(len(le, lw), len(re, rw), foreArm * 0.998f).coerceIn(shoulderSpan * 0.60f, shoulderSpan * 0.95f)

        val lh = bodyPoint(s, VisionJoint.LEFT_HIP); val rh = bodyPoint(s, VisionJoint.RIGHT_HIP)
        // Everything is measured from the middle of the hips, as MediaPipe
        // does; hips out of the picture are taken to be below the shoulders.
        val cx: Float; val cy: Float
        if (lh != null && rh != null) { cx = (lh.x + rh.x) / 2f; cy = (lh.y + rh.y) / 2f }
        else { cx = (ls.x + rs.x) / 2f; cy = (ls.y + rs.y) / 2f + shoulderSpan * 1.4f }

        val out = HashMap<Int, BodyPoint>()
        fun put(index: Int, p: P?, z: Float) {
            if (p == null) return
            out[index] = BodyPoint(
                poseFilters.filter("p.$index.x", (p.x - cx) * metresPerUnit, t),
                poseFilters.filter("p.$index.y", (p.y - cy) * metresPerUnit, t),
                poseFilters.filter("p.$index.z", z * metresPerUnit, t),
                (p.c / GOOD_CONFIDENCE * 0.5f).coerceIn(0f, 1f)
            )
        }
        fun arm(shoulder: P, elbow: P?, wrist: P?, iShoulder: Int, iElbow: Int, iWrist: Int) {
            put(iShoulder, shoulder, 0f)
            if (elbow == null) return
            // (-z = towards the camera.)
            val elbowZ = -depth(upperArm, len(shoulder, elbow))
            put(iElbow, elbow, elbowZ)
            if (wrist != null) put(iWrist, wrist, elbowZ - depth(foreArm, len(elbow, wrist)))
        }
        arm(ls, le, lw, 11, 13, 15)
        arm(rs, re, rw, 12, 14, 16)
        put(23, lh, 0f); put(24, rh, 0f)
        // Legs are taken as seen, flat in the picture.
        put(25, bodyPoint(s, VisionJoint.LEFT_KNEE), 0f); put(26, bodyPoint(s, VisionJoint.RIGHT_KNEE), 0f)
        put(27, bodyPoint(s, VisionJoint.LEFT_ANKLE), 0f); put(28, bodyPoint(s, VisionJoint.RIGHT_ANKLE), 0f)
        return out
    }

    /** How far out of the picture's plane a bone [full] long must reach to
     *  look only [seen] long. Nearly-full sightings count as flat, so
     *  ordinary jitter doesn't push limbs back and forth. */
    private fun depth(full: Float, seen: Float): Float {
        if (full <= 1e-6f) return 0f
        val r = seen / full / FLAT_RATIO
        if (r >= 1f) return 0f
        return full * sqrt(1f - r * r)
    }

    // ── head (stand-in for the face tracker) ──

    private fun headFrom(s: VisionSample, t: Double): FloatArray? {
        val nose = bodyPoint(s, VisionJoint.NOSE) ?: return null
        val lEye = bodyPoint(s, VisionJoint.LEFT_EYE) ?: return null
        val rEye = bodyPoint(s, VisionJoint.RIGHT_EYE) ?: return null
        val lEar = bodyPoint(s, VisionJoint.LEFT_EAR); val rEar = bodyPoint(s, VisionJoint.RIGHT_EAR)
        // The eye line gives the tilt.
        val ex = lEye.x - rEye.x
        val ey = lEye.y - rEye.y
        val eyeSpacing = hypot(ex, ey)
        if (eyeSpacing < 0.004f) return null
        val roll = atan2(-ey, ex)
        // The nose against the ears (or eyes) gives the turn and the nod.
        val ears = lEar != null && rEar != null
        val midX = if (ears) (lEar!!.x + rEar!!.x) / 2f else (lEye.x + rEye.x) / 2f
        val midY = if (ears) (lEar!!.y + rEar!!.y) / 2f else (lEye.y + rEye.y) / 2f
        val span2d = if (ears) hypot(lEar!!.x - rEar!!.x, lEar.y - rEar.y) else eyeSpacing * 2.2f
        if (span2d < 0.004f) return null
        val yaw = atan(((nose.x - midX) / span2d) / 0.55f).coerceIn(-1.2f, 1.2f)
        val fullSpan = span2d / cos(yaw).coerceAtLeast(0.35f)
        val neutral = if (ears) 0.12f else 0.22f
        val pitch = atan(((nose.y - midY) / fullSpan - neutral) / 0.55f).coerceIn(-0.6f, 0.6f)

        val sYaw = headFilters.filter("h.yaw", yaw, t)
        val sPitch = headFilters.filter("h.pitch", pitch, t)
        val sRoll = headFilters.filter("h.roll", roll, t)
        fun axis(x: Float, y: Float, z: Float, a: Float): Quaternion {
            val h = sin(a / 2f)
            return Quaternion(x * h, y * h, z * h, cos(a / 2f))
        }
        val q = axis(0f, 1f, 0f, sYaw) * axis(1f, 0f, 0f, sPitch) * axis(0f, 0f, 1f, sRoll)
        return q.normalized().toColumnMajorMatrix()
    }

    // ── hands ──

    private fun handPoint(s: VisionSample, hand: Int, joint: Int): P {
        val i = (hand * 21 + joint) * 3
        val x = if (flipX) 1f - s.hands[i] else s.hands[i]
        return P(x * s.aspect, 1f - s.hands[i + 1], s.hands[i + 2])
    }

    private fun liftHands(s: VisionSample, bodySample: VisionSample?, eyesCamera: FloatArray?, focal: Float, t: Double): Map<String, TrackedHand> {
        val count = s.hands.size / 63
        if (count == 0) { handFilters.resetAll(); return emptyMap() }
        // The joints the hand's size and facing are read from must be seen.
        val usable = (0 until count).filter { h ->
            handPoint(s, h, 0).c >= MIN_CONFIDENCE && handPoint(s, h, 5).c >= MIN_CONFIDENCE && handPoint(s, h, 17).c >= MIN_CONFIDENCE
        }.take(2)
        if (usable.isEmpty()) { handFilters.resetAll(); return emptyMap() }

        // Whose hand is whose — by where it is, like Android:
        //  1. nearest of the body's two wrists, when the body is seen;
        //  2. two hands: the one further left in the picture is your right
        //     (the picture isn't mirrored);
        //  3. one hand: which side of your face it's on.
        val sides = HashMap<Int, String>()
        val bodyLeft = bodySample?.let { bodyPoint(it, VisionJoint.LEFT_WRIST) }
        val bodyRight = bodySample?.let { bodyPoint(it, VisionJoint.RIGHT_WRIST) }
        val faceX = bodySample?.let { b ->
            bodyPoint(b, VisionJoint.NOSE)?.x ?: run {
                val l = bodyPoint(b, VisionJoint.LEFT_SHOULDER); val r = bodyPoint(b, VisionJoint.RIGHT_SHOULDER)
                if (l != null && r != null) (l.x + r.x) / 2f else null
            }
        } ?: (s.aspect / 2f)
        if (bodyLeft != null && bodyRight != null) {
            for (h in usable) {
                val w = handPoint(s, h, 0)
                val dl = (w.x - bodyLeft.x) * (w.x - bodyLeft.x) + (w.y - bodyLeft.y) * (w.y - bodyLeft.y)
                val dr = (w.x - bodyRight.x) * (w.x - bodyRight.x) + (w.y - bodyRight.y) * (w.y - bodyRight.y)
                sides[h] = if (dl <= dr) "left" else "right"
            }
            // Both hands landing on the same wrist: fall back to their order.
            if (usable.size == 2 && sides[usable[0]] == sides[usable[1]]) sides.clear()
        }
        if (sides.isEmpty()) {
            if (usable.size >= 2) {
                val a = usable[0]; val b = usable[1]
                val leftmost = if (handPoint(s, a, 0).x <= handPoint(s, b, 0).x) a else b
                sides[leftmost] = "right"; sides[if (leftmost == a) b else a] = "left"
            } else {
                sides[usable[0]] = if (handPoint(s, usable[0], 0).x < faceX) "right" else "left"
            }
        }

        // Your eyes in the picture, for the arm IK's "hand relative to eyes".
        val eyesPicture = bodySample?.let { b ->
            val l = bodyPoint(b, VisionJoint.LEFT_EYE); val r = bodyPoint(b, VisionJoint.RIGHT_EYE)
            if (l != null && r != null) floatArrayOf((l.x + r.x) / 2f, (l.y + r.y) / 2f) else null
        }

        val out = HashMap<String, TrackedHand>()
        for (h in usable) {
            val personSide = sides[h] ?: continue
            val avatarSide = if (AvatarRetargeter.MIRROR) (if (personSide == "left") "right" else "left") else personSide
            if (out.containsKey(avatarSide)) continue
            val p = Array(21) { handPoint(s, h, it) }
            // Picture heights per metre at the hand: the palm edge that
            // looks longest for its real length is the one seen most side-on.
            var unitsPerMetre = 0f
            for (e in PALM_EDGES) {
                val a = p[e[0].toInt()]; val b = p[e[1].toInt()]
                if (a.c < MIN_CONFIDENCE || b.c < MIN_CONFIDENCE) continue
                unitsPerMetre = maxOf(unitsPerMetre, hypot(a.x - b.x, a.y - b.y) / e[2])
            }
            if (unitsPerMetre < 1e-4f) continue
            // Palm or back of the hand towards the camera: from which side
            // of the wrist→fingers line the index knuckle is on.
            val ax = p[5].x - p[0].x; val ay = p[5].y - p[0].y
            val bx = p[17].x - p[0].x; val by = p[17].y - p[0].y
            val turn = ax * by - ay * bx
            val palmToCamera = (personSide == "right") == (turn < 0f)
            val curl = if (palmToCamera) -1f else 1f

            // The palm is one stiff plate: how much shorter than life its
            // three wrist→knuckle edges look, taken together, says how far
            // it leans out of the picture (towards the camera, see above).
            var palmRatio = 0f
            var palmEdges = 0
            for (e in PALM_EDGES) {
                if (e[0] != 0f) continue
                val k = p[e[1].toInt()]
                if (k.c < MIN_CONFIDENCE) continue
                palmRatio += hypot(k.x - p[0].x, k.y - p[0].y) / unitsPerMetre / e[2]
                palmEdges++
            }
            val palmLean = if (palmEdges == 0) 0f else leanOf(palmRatio / palmEdges)
            val z = FloatArray(21)
            for (bone in HAND_BONES) {
                val from = bone[0].toInt(); val to = bone[1].toInt()
                if (from == 0) { z[to] = -bone[2] * palmLean; continue }
                val seen = hypot(p[to].x - p[from].x, p[to].y - p[from].y) / unitsPerMetre
                // Fingers curl towards the palm.
                z[to] = z[from] + if (p[to].c < MIN_CONFIDENCE) 0f else curl * bone[2] * leanOf(seen / bone[2])
            }
            val points = List(21) { k ->
                floatArrayOf(
                    handFilters.filter("f.$avatarSide.$k.x", (p[k].x - p[0].x) / unitsPerMetre, t),
                    handFilters.filter("f.$avatarSide.$k.y", (p[k].y - p[0].y) / unitsPerMetre, t),
                    handFilters.filter("f.$avatarSide.$k.z", z[k], t)
                )
            }

            // Where the wrist is, in metres from the camera: its apparent
            // size gives the distance, the picture gives the rest.
            var offset: FloatArray? = null
            if (eyesCamera != null && focal > 0.1f) {
                val depth = focal / unitsPerMetre
                val eyeZ = eyesCamera[2]
                val eyeX = if (eyesPicture != null) (eyesPicture[0] - s.aspect / 2f) * eyeZ / focal else eyesCamera[0]
                val eyeY = if (eyesPicture != null) (eyesPicture[1] - 0.5f) * eyeZ / focal else eyesCamera[1]
                val raw = floatArrayOf(
                    (p[0].x - s.aspect / 2f) * depth / focal - eyeX,
                    (p[0].y - 0.5f) * depth / focal - eyeY,
                    (depth - eyeZ).coerceIn(-0.7f, 0.3f) // depth is the least reliable of the three
                )
                offset = FloatArray(3) { k -> handFilters.filter("o.$avatarSide.$k", raw[k], t) }
            }
            if (offset == null) handFilters.resetPrefixed("o.$avatarSide.")
            out[avatarSide] = TrackedHand(points, offset)
        }
        for (side in SIDES) if (!out.containsKey(side)) {
            handFilters.resetPrefixed("f.$side.")
            handFilters.resetPrefixed("o.$side.")
        }
        return out
    }

    /** 0 = flat in the picture … 1 = pointing straight at the camera, for
     *  a bone that looks [ratio] of its real length. Hands differ from the
     *  "typical" one by a tenth or so, so that much shortening is flat. */
    private fun leanOf(ratio: Float): Float {
        val r = ratio / 0.9f
        return if (r >= 1f) 0f else sqrt(1f - r * r)
    }

    companion object {
        /** Vision's confidence below which a joint is a guess. */
        const val MIN_CONFIDENCE = 0.1f
        /** …and from which it's as good as seen. */
        private const val GOOD_CONFIDENCE = 0.3f
        /** A bone that looks at least this much of its length is flat. */
        private const val FLAT_RATIO = 0.93f
        /** Typical adult shoulder-joint spacing, metres. */
        private const val SHOULDERS_M = 0.36f
        private val SIDES = listOf("left", "right")

        // Typical adult hand, metres. Joint numbers are MediaPipe's (and
        // the order the Swift side sends): 0 wrist; thumb 1–4; index 5–8;
        // middle 9–12; ring 13–16; little 17–20.
        /** from, to, length — in an order that always has "from" done first. */
        private val HAND_BONES = arrayOf(
            floatArrayOf(0f, 1f, 0.040f), floatArrayOf(1f, 2f, 0.045f), floatArrayOf(2f, 3f, 0.033f), floatArrayOf(3f, 4f, 0.028f),
            floatArrayOf(0f, 5f, 0.095f), floatArrayOf(5f, 6f, 0.042f), floatArrayOf(6f, 7f, 0.025f), floatArrayOf(7f, 8f, 0.022f),
            floatArrayOf(0f, 9f, 0.093f), floatArrayOf(9f, 10f, 0.046f), floatArrayOf(10f, 11f, 0.029f), floatArrayOf(11f, 12f, 0.023f),
            floatArrayOf(0f, 13f, 0.088f), floatArrayOf(13f, 14f, 0.042f), floatArrayOf(14f, 15f, 0.027f), floatArrayOf(15f, 16f, 0.022f),
            floatArrayOf(0f, 17f, 0.083f), floatArrayOf(17f, 18f, 0.033f), floatArrayOf(18f, 19f, 0.020f), floatArrayOf(19f, 20f, 0.020f)
        )
        /** Wrist–index, wrist–middle, wrist–little, index–little knuckles. */
        private val PALM_EDGES = arrayOf(
            floatArrayOf(0f, 5f, 0.095f), floatArrayOf(0f, 9f, 0.093f), floatArrayOf(0f, 17f, 0.083f), floatArrayOf(5f, 17f, 0.068f)
        )
    }
}
