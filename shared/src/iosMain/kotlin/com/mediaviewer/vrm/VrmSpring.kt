// iOS counterpart of Android's util/VrmSpringBones.kt: the same reading of
// both VRM spring-bone flavours and the same simulation, step for step.
// Android reads and writes bone transforms through Filament; here they go
// through two plain functions, so this file needs no renderer (and is run
// against real .vrm files by tools/vrm-test).
package com.mediaviewer.vrm

import kotlinx.serialization.json.JsonObject
import kotlin.math.sqrt

/**
 * VRM spring bones ("physics bones"): hair, ears, tails, skirts and
 * accessories that swing and settle when the avatar moves.
 *
 *  - VRM 0.x `extensions.VRM.secondaryAnimation` — bone groups whose listed
 *    nodes, and everything below them, are springs; Unity-space vectors
 *    (collider offsets, gravity) have Z negated, as three-vrm does.
 *  - VRM 1.0 `extensions.VRMC_springBone` — explicit joint chains.
 *
 * The simulation is the standard VRM one: each joint's tail is a Verlet
 * point pulled by inertia, stiffness (back towards its rest direction) and
 * gravity, kept at bone length, and pushed out of sphere/capsule colliders;
 * the joint is then rotated to point at its tail. It runs in the model's
 * own space, so turning the avatar on screen doesn't whip the hair around,
 * but turning your head does.
 */
class SpringJointDef(
    val node: Int,
    /** The node the joint points at, or -1 for a VRM 0.x leaf (which gets
     *  a short virtual tail, as UniVRM does). */
    val tailNode: Int,
    val stiffness: Float,
    val gravityPower: Float,
    val gravityDir: FloatArray,
    val dragForce: Float,
    val hitRadius: Float,
    val colliderGroups: List<Int>
)

class SpringColliderDef(val node: Int, val offset: FloatArray, val radius: Float, val tail: FloatArray?)

class SpringData(val joints: List<SpringJointDef>, val colliderGroups: List<List<SpringColliderDef>>)

object VrmSpringParser {
    fun parse(root: JsonObject): SpringData = runCatching {
        root.at("extensions.VRMC_springBone").obj?.let { return@runCatching parseVrm1(it) }
        root.at("extensions.VRM.secondaryAnimation").obj?.let { return@runCatching parseVrm0(it, root) }
        null
    }.getOrNull() ?: SpringData(emptyList(), emptyList())

    private fun vec(o: JsonObject?, key: String, d: FloatArray): FloatArray {
        val a = o?.at(key).arr?.mapNotNull { it.float } ?: return d
        return if (a.size < 3) d else floatArrayOf(a[0], a[1], a[2])
    }

    private fun unityVec(o: JsonObject?, d: FloatArray): FloatArray =
        if (o == null) d else floatArrayOf(o.at("x").float ?: 0f, o.at("y").float ?: 0f, -(o.at("z").float ?: 0f))

    private fun ints(o: JsonObject?, key: String): List<Int> = o?.at(key).arr?.map { it.int ?: -1 } ?: emptyList()

    private fun parseVrm1(sb: JsonObject): SpringData {
        val colliders = sb.at("colliders").arr.orEmpty().map { c ->
            val o = c.obj
            val node = o?.at("node").int ?: -1
            val sphere = o?.at("shape.sphere").obj
            val capsule = o?.at("shape.capsule").obj
            when {
                node < 0 -> null
                sphere != null -> SpringColliderDef(node, vec(sphere, "offset", FloatArray(3)), sphere.at("radius").float ?: 0f, null)
                capsule != null -> SpringColliderDef(node, vec(capsule, "offset", FloatArray(3)), capsule.at("radius").float ?: 0f, vec(capsule, "tail", FloatArray(3)))
                else -> null
            }
        }
        val groups = sb.at("colliderGroups").arr.orEmpty().map { g -> ints(g.obj, "colliders").mapNotNull { colliders.getOrNull(it) } }
        val joints = ArrayList<SpringJointDef>()
        for (s in sb.at("springs").arr.orEmpty()) {
            val spring = s.obj ?: continue
            val cg = ints(spring, "colliderGroups")
            val js = spring.at("joints").arr ?: continue
            // Every joint but the last is simulated; the last is its tail.
            for (j in 0 until js.size - 1) {
                val jo = js[j].obj ?: continue
                val tail = js[j + 1].obj?.at("node").int ?: -1
                val node = jo.at("node").int ?: -1
                if (node < 0 || tail < 0) continue
                joints.add(SpringJointDef(
                    node, tail,
                    jo.at("stiffness").float ?: 1f,
                    jo.at("gravityPower").float ?: 0f,
                    vec(jo, "gravityDir", floatArrayOf(0f, -1f, 0f)),
                    jo.at("dragForce").float ?: 0.5f,
                    jo.at("hitRadius").float ?: 0f,
                    cg
                ))
            }
        }
        return SpringData(joints, groups)
    }

    private fun parseVrm0(sa: JsonObject, root: JsonObject): SpringData {
        val nodes = root.at("nodes").arr
        val groups = sa.at("colliderGroups").arr.orEmpty().map { g ->
            val o = g.obj
            val node = o?.at("node").int ?: -1
            o?.at("colliders").arr.orEmpty().mapNotNull { c ->
                val co = c.obj ?: return@mapNotNull null
                if (node < 0) null else SpringColliderDef(node, unityVec(co.at("offset").obj, FloatArray(3)), co.at("radius").float ?: 0f, null)
            }
        }
        val joints = ArrayList<SpringJointDef>()
        val seen = HashSet<Int>()
        for (b in sa.at("boneGroups").arr.orEmpty()) {
            val bg = b.obj ?: continue
            // (sic) UniVRM's key is "stiffiness".
            val stiffness = bg.at("stiffiness").float ?: bg.at("stiffness").float ?: 1f
            val gravityPower = bg.at("gravityPower").float ?: 0f
            val gravityDir = unityVec(bg.at("gravityDir").obj, floatArrayOf(0f, -1f, 0f)).let {
                if (it[0] == 0f && it[1] == 0f && it[2] == 0f) floatArrayOf(0f, -1f, 0f) else it
            }
            val drag = bg.at("dragForce").float ?: 0.4f
            val hit = bg.at("hitRadius").float ?: 0.02f
            val cg = ints(bg, "colliderGroups")
            // Each listed node and every descendant is a joint aimed at its first child.
            val stack = ArrayDeque(ints(bg, "bones").filter { it >= 0 })
            while (stack.isNotEmpty()) {
                val n = stack.removeLast()
                if (!seen.add(n)) continue
                val children = ints(nodes?.getOrNull(n).obj, "children").filter { it >= 0 }
                joints.add(SpringJointDef(n, children.firstOrNull() ?: -1, stiffness, gravityPower, gravityDir, drag, hit, cg))
                children.forEach { stack.addLast(it) }
            }
        }
        return SpringData(joints, groups)
    }
}

/**
 * Live simulation for one loaded model. [update] once per drawn frame,
 * after the tracking pose has been applied; [reset] puts every joint back.
 * [getLocal] must return a node's CURRENT parent-relative transform (the
 * pose as it stands this frame) and [setLocal] moves it.
 */
class VrmSpringSim(
    private val doc: GltfDoc,
    data: SpringData,
    /** Nodes the tracking retargeter owns — never simulated. */
    excluded: Set<Int>,
    private val getLocal: (Int) -> FloatArray,
    private val setLocal: (Int, FloatArray) -> Unit
) {
    private class Joint(
        val node: Int,
        val parent: Int,
        val restLocal: FloatArray,
        val restRot: Quaternion,
        val localT: FloatArray,
        val localS: FloatArray,
        /** Rest direction to the tail, in the joint's own space. */
        val axis: FloatArray,
        /** Tail distance in the joint's own (unscaled) space. */
        val length: Float,
        val def: SpringJointDef,
        val colliders: List<SpringColliderDef>
    ) {
        var cur: FloatArray? = null
        var prev: FloatArray? = null
    }

    private val joints: List<Joint>
    val jointCount: Int get() = joints.size

    init {
        val built = ArrayList<Pair<Int, Joint>>()
        for (def in data.joints) {
            val node = doc.nodes.getOrNull(def.node) ?: continue
            if (def.node in excluded || node.parent < 0) continue
            val local = node.local
            val localT = floatArrayOf(local[12], local[13], local[14])
            val localS = floatArrayOf(colLen(local, 0), colLen(local, 1), colLen(local, 2))
            val tail = doc.nodes.getOrNull(def.tailNode)
            val axis: FloatArray
            val length: Float
            if (tail != null) {
                val v = floatArrayOf(tail.local[12], tail.local[13], tail.local[14])
                axis = normalize(v); length = len(v)
            } else {
                // VRM 0.x leaf: virtual 7 cm tail, continuing the bone's line.
                axis = normalize(localT); length = 0.07f / localS[0].coerceAtLeast(1e-6f)
            }
            if (length < 1e-6f || len(axis) < 0.5f) continue
            val colliders = def.colliderGroups.flatMap { data.colliderGroups.getOrNull(it).orEmpty() }
                .filter { doc.nodes.getOrNull(it.node) != null }
            built.add(depth(def.node) to Joint(def.node, node.parent, local, rotationOf(local), localT, localS, axis, length, def, colliders))
        }
        // Parents before children, so each joint sees its parent's new pose.
        joints = built.sortedBy { it.first }.map { it.second }
    }

    private fun depth(node: Int): Int {
        var d = 0; var n = node
        while (d < 512) {
            val p = doc.nodes.getOrNull(n)?.parent ?: -1
            if (p < 0) break
            n = p; d++
        }
        return d
    }

    /** Puts every joint back to its rest pose and forgets its motion. */
    fun reset() {
        for (j in joints) { setLocal(j.node, j.restLocal); j.cur = null; j.prev = null }
    }

    // Model-space transforms this step, worked out on request from the
    // current pose and remembered until something above them moves.
    private val worldCache = HashMap<Int, FloatArray>()

    private fun world(node: Int, guard: Int = 0): FloatArray {
        worldCache[node]?.let { return it }
        val parent = doc.nodes.getOrNull(node)?.parent ?: -1
        val local = getLocal(node)
        val w = if (parent < 0 || guard > 256) local else multiplyColumnMajor4x4(world(parent, guard + 1), local)
        worldCache[node] = w
        return w
    }

    fun update(frameDt: Float) {
        if (joints.isEmpty()) return
        // Fixed-ish steps keep it stable when a frame is slow.
        val steps = (frameDt / (1f / 60f)).toInt().coerceIn(1, 3)
        val dt = (frameDt / steps).coerceIn(1e-4f, 1f / 30f)
        repeat(steps) {
            worldCache.clear()
            for (j in joints) {
                val parentM = world(j.parent)
                val parentRot = rotationOf(parentM)
                val head = point(parentM, j.localT)
                val restWorldRot = parentRot * j.restRot
                val scale = colLen(parentM, 0) * j.localS[0]
                val boneLen = j.length * scale
                val restDir = rotate(restWorldRot, j.axis)
                val cur = j.cur ?: add(head, scl(restDir, boneLen))
                val prev = j.prev ?: cur
                val d = j.def
                var next = add(cur, scl(sub(cur, prev), 1f - d.dragForce))
                next = add(next, scl(restDir, d.stiffness * dt))
                next = add(next, scl(d.gravityDir, d.gravityPower * dt))
                next = add(head, scl(normalize(sub(next, head)), boneLen))
                for (c in j.colliders) {
                    val cm = world(c.node)
                    var center = point(cm, c.offset)
                    if (c.tail != null) center = closestOnSegment(center, point(cm, c.tail), next)
                    val r = c.radius * colLen(cm, 0) + d.hitRadius * scale
                    val away = sub(next, center)
                    val dist = len(away)
                    if (dist < r && dist > 1e-6f) {
                        next = add(center, scl(away, r / dist))
                        next = add(head, scl(normalize(sub(next, head)), boneLen))
                    }
                }
                j.prev = cur
                j.cur = next
                // Aim the joint at its tail.
                val to = rotate(restWorldRot.conjugate(), normalize(sub(next, head)))
                val q = (j.restRot * quaternionBetweenDirections(j.axis, to)).normalized()
                setLocal(j.node, compose(j.localT, q, j.localS))
                // (Anything worked out below this joint is now out of date;
                // joints are in parent-first order, so that's only itself.)
                worldCache.remove(j.node)
            }
        }
    }

    /** For tests: a number that changes whenever any tail moves. */
    fun debugTailSum(): Float = joints.sumOf { j -> (j.cur?.let { it[0] + it[1] * 3f + it[2] * 7f } ?: 0f).toDouble() }.toFloat()

    // ── maths ──

    private fun colLen(m: FloatArray, c: Int) = sqrt(m[c * 4] * m[c * 4] + m[c * 4 + 1] * m[c * 4 + 1] + m[c * 4 + 2] * m[c * 4 + 2])

    private fun rotationOf(m: FloatArray): Quaternion {
        val n = m.copyOf()
        for (c in 0 until 3) {
            val l = colLen(m, c)
            if (l > 1e-8f) for (r in 0 until 3) n[c * 4 + r] /= l
        }
        return Quaternion.fromRotationColumnMajorMatrix(n)
    }

    private fun compose(t: FloatArray, q: Quaternion, s: FloatArray): FloatArray {
        val r = q.toColumnMajorMatrix()
        for (c in 0 until 3) for (row in 0 until 3) r[c * 4 + row] *= s[c]
        r[12] = t[0]; r[13] = t[1]; r[14] = t[2]; r[15] = 1f
        return r
    }

    private fun point(m: FloatArray, p: FloatArray) = floatArrayOf(
        m[0] * p[0] + m[4] * p[1] + m[8] * p[2] + m[12],
        m[1] * p[0] + m[5] * p[1] + m[9] * p[2] + m[13],
        m[2] * p[0] + m[6] * p[1] + m[10] * p[2] + m[14]
    )

    private fun closestOnSegment(a: FloatArray, b: FloatArray, p: FloatArray): FloatArray {
        val ab = sub(b, a)
        val l2 = dot(ab, ab)
        if (l2 < 1e-12f) return a
        val t = (dot(sub(p, a), ab) / l2).coerceIn(0f, 1f)
        return add(a, scl(ab, t))
    }

    private fun rotate(q: Quaternion, v: FloatArray): FloatArray {
        val ux = q.x; val uy = q.y; val uz = q.z
        val tx = 2f * (uy * v[2] - uz * v[1]); val ty = 2f * (uz * v[0] - ux * v[2]); val tz = 2f * (ux * v[1] - uy * v[0])
        return floatArrayOf(
            v[0] + q.w * tx + (uy * tz - uz * ty),
            v[1] + q.w * ty + (uz * tx - ux * tz),
            v[2] + q.w * tz + (ux * ty - uy * tx)
        )
    }

    private fun add(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] + b[0], a[1] + b[1], a[2] + b[2])
    private fun sub(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
    private fun scl(a: FloatArray, s: Float) = floatArrayOf(a[0] * s, a[1] * s, a[2] * s)
    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun len(a: FloatArray) = sqrt(dot(a, a))
    private fun normalize(a: FloatArray): FloatArray { val l = len(a); return if (l < 1e-8f) a else scl(a, 1f / l) }
}
