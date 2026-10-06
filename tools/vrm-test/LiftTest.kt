import com.mediaviewer.vrm.*
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

// Tries VisionLift (iOS body and hand tracking's "flat points to 3-D")
// with made-up people: a pose is built in 3-D, flattened the way a camera
// would see it, lifted back, and compared with what it started as.
//   sh tools/vrm-test/run.sh --lift [a.vrm …]
// With .vrm files, each lifted pose is also put through the retargeter.

private const val ASPECT = 0.75f
/** Picture heights per metre, and where the hips sit in the picture. */
private const val SCALE = 0.5f

private class Person {
    /** Joint (VisionJoint numbering) → x right, y down, z away, metres from the hips. */
    val joints = HashMap<Int, FloatArray>()
    fun put(j: Int, x: Float, y: Float, z: Float) { joints[j] = floatArrayOf(x, y, z) }
}

/** Facing the camera, so the person's LEFT is on the picture's RIGHT (+x). */
private fun standing(): Person = Person().apply {
    put(VisionJoint.NOSE, 0f, -0.62f, -0.08f)
    put(VisionJoint.LEFT_EYE, 0.032f, -0.66f, -0.06f); put(VisionJoint.RIGHT_EYE, -0.032f, -0.66f, -0.06f)
    put(VisionJoint.LEFT_EAR, 0.075f, -0.65f, 0.02f); put(VisionJoint.RIGHT_EAR, -0.075f, -0.65f, 0.02f)
    put(VisionJoint.LEFT_SHOULDER, 0.18f, -0.45f, 0f); put(VisionJoint.RIGHT_SHOULDER, -0.18f, -0.45f, 0f)
    put(VisionJoint.LEFT_HIP, 0.1f, 0f, 0f); put(VisionJoint.RIGHT_HIP, -0.1f, 0f, 0f)
}

private fun sample(p: Person, hands: FloatArray = FloatArray(0), upsideDown: Boolean = false, t: Long): VisionSample {
    val body = FloatArray(VisionJoint.COUNT * 3)
    for ((j, v) in p.joints) {
        var x = (ASPECT / 2f + v[0] * SCALE) / ASPECT      // 0…1 from the left
        var y = 1f - (0.6f + v[1] * SCALE)                  // 0…1 from the bottom
        if (upsideDown) { x = 1f - x; y = 1f - y }
        body[j * 3] = x; body[j * 3 + 1] = y; body[j * 3 + 2] = 0.9f
    }
    return VisionSample(body, hands, ASPECT, t)
}

private fun dir(a: FloatArray, b: FloatArray): FloatArray {
    val d = floatArrayOf(b[0] - a[0], b[1] - a[1], b[2] - a[2])
    val l = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]).coerceAtLeast(1e-6f)
    return floatArrayOf(d[0] / l, d[1] / l, d[2] / l)
}
private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
private fun v(p: BodyPoint) = floatArrayOf(p.x, p.y, p.z)

/** A right hand, palm to the camera, fingers up: x right, y down, z away,
 *  metres from the wrist. [curl] 0 = open … 1 = fingers bent 90° at each
 *  joint towards the camera (it's the palm side). */
private fun hand(curl: Float, mirrored: Boolean): Array<FloatArray> {
    val out = Array(21) { FloatArray(3) }
    // Palm to the camera, a right hand's thumb is on the picture's right.
    val knuckles = listOf(5 to 0.030f, 9 to 0.008f, 13 to -0.012f, 17 to -0.032f)
    val lengths = mapOf(5 to floatArrayOf(0.042f, 0.025f, 0.022f), 9 to floatArrayOf(0.046f, 0.029f, 0.023f),
        13 to floatArrayOf(0.042f, 0.027f, 0.022f), 17 to floatArrayOf(0.033f, 0.020f, 0.020f))
    val palm = mapOf(5 to 0.095f, 9 to 0.093f, 13 to 0.088f, 17 to 0.083f)
    for ((k, x) in knuckles) {
        val up = sqrt(palm[k]!! * palm[k]!! - x * x)
        out[k] = floatArrayOf(x, -up, 0f)
        var angle = 0.0
        var at = out[k]
        for (s in 0 until 3) {
            angle += curl * Math.PI / 2
            val l = lengths[k]!![s]
            // Bending towards the camera: up turns into -z.
            at = floatArrayOf(at[0], at[1] - (l * Math.cos(angle)).toFloat(), at[2] - (l * Math.sin(angle)).toFloat())
            out[k + s + 1] = at
        }
    }
    out[1] = floatArrayOf(0.030f, -0.026f, 0f); out[2] = floatArrayOf(0.062f, -0.058f, 0f)
    out[3] = floatArrayOf(0.085f, -0.081f, 0f); out[4] = floatArrayOf(0.105f, -0.101f, 0f)
    if (mirrored) for (p in out) p[0] = -p[0]
    return out
}

/** One hand's 63 numbers, with its wrist at ([cx], [cy]) metres from the hips. */
private fun handNumbers(h: Array<FloatArray>, cx: Float, cy: Float): FloatArray {
    val out = FloatArray(63)
    for (k in 0 until 21) {
        out[k * 3] = (ASPECT / 2f + (cx + h[k][0]) * SCALE) / ASPECT
        out[k * 3 + 1] = 1f - (0.6f + (cy + h[k][1]) * SCALE)
        out[k * 3 + 2] = 0.9f
    }
    return out
}

fun liftMain(vrmPaths: List<String>) {
    println("== VisionLift")
    var clock = 1_000_000_000L
    fun next(): Long { clock += 66_000_000L; return clock }
    val poses = ArrayList<LiftedPose>()

    // ── body ──
    run {
        val lift = VisionLift().also { it.setSmoothing(0.0) }
        // First with both arms straight out to the sides, so their length is learnt.
        val wide = standing().apply {
            put(VisionJoint.LEFT_ELBOW, 0.46f, -0.45f, 0f); put(VisionJoint.LEFT_WRIST, 0.70f, -0.45f, 0f)
            put(VisionJoint.RIGHT_ELBOW, -0.46f, -0.45f, 0f); put(VisionJoint.RIGHT_WRIST, -0.70f, -0.45f, 0f)
        }
        var r = lift.update(sample(wide, t = next()), true, false, true, null, 0.8f)
        check(r.upright == 1, "a standing person reads as upright")
        check(r.body.size >= 8, "body joints come through (${r.body.size})")
        val ls = r.body[11]; val le = r.body[13]; val lw = r.body[15]
        if (ls != null && le != null && lw != null) {
            check(dot(dir(v(ls), v(lw)), floatArrayOf(1f, 0f, 0f)) > 0.98f, "an arm held out sideways stays flat and sideways")
            check(abs(ls.x - 0.18f) < 0.02f && abs(ls.y + 0.45f) < 0.02f, "the scale comes out in metres (shoulder at ${ls.x}, ${ls.y})")
        } else check(false, "left arm joints")
        check(r.headMatrix != null, "the head's turn is read from the nose, eyes and ears")
        poses.add(r)

        // Then: left arm down with the forearm raised straight at the camera,
        // right arm reaching forward and a little out.
        val reach = standing().apply {
            put(VisionJoint.LEFT_ELBOW, 0.20f, -0.17f, 0f); put(VisionJoint.LEFT_WRIST, 0.20f, -0.19f, -0.24f)
            put(VisionJoint.RIGHT_ELBOW, -0.30f, -0.40f, -0.25f); put(VisionJoint.RIGHT_WRIST, -0.36f, -0.42f, -0.48f)
        }
        r = lift.update(sample(reach, t = next()), true, false, false, null, 0.8f)
        for ((name, s, e, w, side) in listOf(
            listOf("left", 11, 13, 15, VisionJoint.LEFT_SHOULDER), listOf("right", 12, 14, 16, VisionJoint.RIGHT_SHOULDER)
        )) {
            val bs = r.body[s as Int]; val be = r.body[e as Int]; val bw = r.body[w as Int]
            if (bs == null || be == null || bw == null) { check(false, "$name arm joints"); continue }
            val j = side as Int
            val truthUpper = dir(reach.joints[j]!!, reach.joints[j + 2]!!)
            val truthLower = dir(reach.joints[j + 2]!!, reach.joints[j + 4]!!)
            val upper = dot(dir(v(bs), v(be)), truthUpper)
            val lower = dot(dir(v(be), v(bw)), truthLower)
            println("   $name arm reaching at the camera: upper arm ${"%.2f".format(upper)}, forearm ${"%.2f".format(lower)} (1 = exactly right)")
            check(upper > 0.85f && lower > 0.85f, "$name arm's depth is recovered")
            check(bw.z < be.z - 0.1f || name == "right", "$name wrist comes towards the camera")
        }
        poses.add(r)

        // The same person with the picture read upside down.
        val wrong = VisionLift().update(sample(wide, upsideDown = true, t = next()), true, true, true, null, 0.8f)
        check(wrong.upright == -1, "an upside-down picture is noticed")
        check(wrong.body.isEmpty() && wrong.hands.isEmpty(), "…and nothing is made of it")
    }

    // ── hands ──
    run {
        val lift = VisionLift().also { it.setSmoothing(0.0) }
        val body = standing()
        // Your right hand is on the picture's left; your left on its right.
        val right = hand(0f, mirrored = false)
        val left = hand(0f, mirrored = true)
        val numbers = handNumbers(right, -0.35f, -0.35f) + handNumbers(left, 0.35f, -0.35f)
        var r = lift.update(sample(body, numbers, t = next()), false, true, false, floatArrayOf(0f, -0.1f, 0.5f), 0.8f)
        check(r.hands.size == 2, "both hands come through (${r.hands.keys})")
        // Mirrored: your right hand drives the avatar's left.
        val avatarLeft = r.hands["left"]
        if (avatarLeft != null) {
            val flat = avatarLeft.points.maxOf { abs(it[2]) }
            check(flat < 0.012f, "an open hand facing the camera stays flat (deepest point ${"%.3f".format(flat)} m)")
            check(avatarLeft.points[5][0] > avatarLeft.points[17][0], "its thumb side is where it was put")
            check(avatarLeft.offsetFromEyes != null, "where the hand is, from the eyes, is worked out")
            val o = avatarLeft.offsetFromEyes
            if (o != null) check(o[0] < -0.1f, "your right hand is to the picture's left of your eyes (${o.joinToString { "%.2f".format(it) }})")
        } else check(false, "the hand on the picture's left is taken as your right")
        poses.add(r)

        // Fingers half curled, towards the camera (it's the palm side).
        for ((label, mirrored, side) in listOf(Triple("right", false, "left"), Triple("left", true, "right"))) {
            val curled = hand(0.5f, mirrored)
            val one = handNumbers(curled, if (mirrored) 0.35f else -0.35f, -0.35f)
            val fresh = VisionLift().also { it.setSmoothing(0.0) }
            r = fresh.update(sample(body, one, t = next()), false, true, false, null, 0.8f)
            val h = r.hands[side]
            if (h == null) { check(false, "a single $label hand is told from where it is (${r.hands.keys})"); continue }
            var worst = 1f
            for (k in listOf(5, 9, 13, 17)) for (s in 0 until 3) {
                val truth = dir(curled[k + s], curled[k + s + 1])
                val got = dir(h.points[k + s], h.points[k + s + 1])
                worst = minOf(worst, dot(truth, got))
            }
            println("   $label hand, fingers half curled: worst finger bone ${"%.2f".format(worst)} (1 = exactly right)")
            check(worst > 0.8f, "$label hand's curl is recovered, towards the palm")
            check(h.points[8][2] < -0.02f, "$label index fingertip comes towards the camera")
            poses.add(r)
        }
    }

    // ── through the retargeter ──
    for (path in vrmPaths) {
        val glb = GlbReader.read(File(path).readBytes()) ?: continue
        val vrm = VrmParser.parse(glb.json) ?: continue
        val rig = FakeRig(GltfDoc(glb))
        val target = AvatarRetargeter.buildTarget(rig, vrm)
        for (pose in poses) for (ik in listOf(false, true)) {
            AvatarRetargeter.applyPose(target, TrackingFrame(pose.headMatrix, pose.body.takeIf { it.isNotEmpty() }, true, pose.hands, ik, null))
            Thread.sleep(4)
        }
        check(rig.locals.isNotEmpty() && rig.locals.values.all { m -> m.all { it.isFinite() } }, "retargeting lifted poses onto ${path.substringAfterLast('/')}")
        println("   ${path.substringAfterLast('/')}: ${rig.locals.size} bones posed from lifted points")
    }
    println(if (failures == 0) "ALL OK" else "$failures FAILED")
}

fun main(args: Array<String>) = liftMain(args.toList())
