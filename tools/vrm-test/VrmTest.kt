import com.mediaviewer.vrm.*
import java.io.File
import kotlin.math.abs

/** A rig with no renderer behind it: remembers what the retargeter set. */
class FakeRig(val doc: GltfDoc) : VrmRig {
    val locals = HashMap<Int, FloatArray>()
    val morphs = HashMap<Int, FloatArray>()
    override fun restLocal(node: Int) = doc.nodes.getOrNull(node)?.local
    override fun restWorld(node: Int) = doc.nodes.getOrNull(node)?.world
    override fun parentOf(node: Int) = doc.nodes.getOrNull(node)?.parent?.takeIf { it >= 0 }
    override fun setLocal(node: Int, matrix: FloatArray) { locals[node] = matrix }
    override fun morphCount(node: Int): Int =
        doc.meshes.getOrNull(doc.nodes.getOrNull(node)?.mesh ?: -1)?.primitives?.maxOfOrNull { it.targets.size } ?: 0
    override fun setMorphWeights(node: Int, weights: FloatArray) { morphs[node] = weights.copyOf() }
}

var failures = 0
fun check(ok: Boolean, what: String) { if (!ok) { failures++; println("   FAIL: $what") } }

fun main(args: Array<String>) {
    for (path in args) {
        println("== " + path.substringAfterLast('/'))
        val bytes = File(path).readBytes()
        val glb = GlbReader.read(bytes)
        if (glb == null) { check(false, "not a GLB"); continue }
        val doc = GltfDoc(glb)
        val vrm = VrmParser.parse(glb.json)
        println("   nodes ${doc.nodes.size}, meshes ${doc.meshes.size}, skins ${doc.skins.size}, materials ${doc.materials.size}, images ${doc.images.size}")
        if (vrm == null) { println("   (no VRM block)"); continue }
        println("   ${vrm.specVersion}: ${vrm.humanBones.size} bones, ${vrm.expressions.size} expressions ${vrm.expressions.keys.take(8)}, orphans ${vrm.orphanMorphTargets.size}")
        check(vrm.humanBones.containsKey("head") && vrm.humanBones.containsKey("hips"), "head and hips bones")

        // Every primitive reads, and its arrays agree with each other.
        var vertices = 0; var tris = 0; var targets = 0; var moved = 0
        for (n in doc.nodes) {
            val mesh = doc.meshes.getOrNull(n.mesh) ?: continue
            val skin = doc.skins.getOrNull(n.skin)
            for (p in mesh.primitives) {
                val pos = doc.readFloats(p.position, 3)
                check(pos != null, "positions of ${mesh.name}")
                pos ?: continue
                val count = pos.size / 3
                vertices += count
                val t = doc.triangles(p); tris += t.size / 3
                check(t.size % 3 == 0 && t.all { it in 0 until count }, "triangles of ${mesh.name}")
                if (p.texcoord >= 0) check(doc.readFloats(p.texcoord, 2)?.size == count * 2, "uvs of ${mesh.name}")
                if (skin != null) {
                    val w = doc.weights(p); val j = doc.joints(p, skin.joints.size)
                    check(w?.size == count * 4 && j?.size == count * 4, "skin data of ${mesh.name}")
                    if (w != null && j != null) {
                        // At rest a skinned vertex normally lands where the file put it.
                        var worst = 0f
                        var v = 0
                        while (v < count) {
                            val s = doc.skinned(pos, v, w, j, skin)
                            val d = GltfDoc.length(s[0] - pos[v * 3], s[1] - pos[v * 3 + 1], s[2] - pos[v * 3 + 2])
                            if (d > worst) worst = d
                            v += 13
                        }
                        // (Not an error when it does: a file may store a mesh in one
                        // pose and its skeleton in another — Seed-san's arm does.)
                        if (worst > 0.02f) println("   note: ${mesh.name} is stored in a different pose from its skeleton ($worst m apart)")
                        check(worst.isFinite() && worst < 5f, "skin of ${mesh.name} is sane")
                    }
                }
                for (ti in p.targets.indices) {
                    if (p.targets[ti] < 0) continue
                    val d = doc.readFloats(p.targets[ti], 3)
                    check(d?.size == count * 3, "morph target $ti of ${mesh.name}")
                    targets++
                    if (d != null && d.any { abs(it) > 1e-5f }) moved++
                }
            }
        }
        println("   $vertices vertices, $tris triangles, $targets morph targets ($moved move something)")
        val b = doc.bounds()
        println("   bounds " + b?.joinToString { "%.2f".format(it) })
        check(b != null && (b[4] - b[1]) in 0.3f..3.5f, "avatar height looks like metres")
        for (m in doc.materials) {
            val im = doc.images.getOrNull(m.baseImage)
            if (im != null && im.length > 0) {
                val sig = glb.bytes[im.offset].toInt() and 0xFF
                check(sig == 0x89 || sig == 0xFF, "image of ${m.name} is a PNG or JPEG")
            }
        }
        println("   materials: " + doc.materials.groupingBy { it.alphaMode }.eachCount() + ", textured ${doc.materials.count { it.baseImage >= 0 }}")

        // Retargeting: a head turned and tilted, a wink and an open mouth.
        val rig = FakeRig(doc)
        val target = AvatarRetargeter.buildTarget(rig, vrm)
        println("   faces -Z: ${target.facesNegativeZ}; bones resolved ${target.bones.size}")
        val turn = Quaternion(0.1f, 0.25f, 0.05f, 1f).normalized().toColumnMajorMatrix()
        AvatarRetargeter.smoothingScale = 0f
        AvatarRetargeter.applyPose(target, TrackingFrame(faceMatrix = turn, body = null, trackLegs = false))
        check(rig.locals.isNotEmpty(), "pose wrote bone transforms")
        check(rig.locals.values.all { m -> m.all { it.isFinite() } }, "bone transforms are finite numbers")
        val head = vrm.humanBones["head"]?.let { rig.locals[it] }
        val headRest = vrm.humanBones["head"]?.let { doc.nodes[it].local }
        check(head != null && headRest != null && !head.contentEquals(headRest), "head moved")
        // Arms hang instead of holding the T-pose.
        val upper = vrm.humanBones["leftUpperArm"]?.let { rig.locals[it] }
        val upperRest = vrm.humanBones["leftUpperArm"]?.let { doc.nodes[it].local }
        check(upper != null && upperRest != null && !upper.contentEquals(upperRest), "arms relaxed")
        AvatarRetargeter.applyExpressions(
            target, vrm, mapOf("eyeBlinkLeft" to 1f, "eyeBlinkRight" to 0f, "jawOpen" to 0.8f, "mouthSmileLeft" to 0.5f, "mouthSmileRight" to 0.5f),
            remapBlink = false
        )
        val active = rig.morphs.values.sumOf { w -> w.count { it > 0.01f } }
        println("   expression test: ${rig.morphs.size} meshes written, $active morph weights above zero")
        if (vrm.expressions.isNotEmpty()) check(active > 0, "expressions drive morph targets")

        val spring = VrmSpringParser.parse(glb.json)
        println("   spring bones: ${spring.joints.size} joints, ${spring.colliderGroups.sumOf { it.size }} colliders")
        if (spring.joints.isNotEmpty()) {
            val owned = target.bones.values.map { it.entity }.toSet()
            val sim = VrmSpringSim(doc, spring, owned, { n -> rig.locals[n] ?: doc.nodes[n].local }, { n, m -> rig.locals[n] = m })
            check(sim.jointCount > 0, "spring joints resolve")
            // Stand still for two seconds: hair must settle, not drift or blow up.
            repeat(120) { sim.update(1f / 60f) }
            val settled = sim.debugTailSum()
            repeat(30) { sim.update(1f / 60f) }
            check(abs(sim.debugTailSum() - settled) < 0.05f * sim.jointCount, "hair settles when nothing moves")
            // Then turn the head sharply.
            AvatarRetargeter.applyPose(target, TrackingFrame(faceMatrix = Quaternion(0f, 0.6f, 0f, 0.8f).normalized().toColumnMajorMatrix(), body = null, trackLegs = false))
            repeat(10) { sim.update(1f / 60f) }
            check(rig.locals.values.all { m -> m.all { it.isFinite() } }, "spring bones stay finite")
            check(sim.debugTailSum() != settled, "hair reacts to the head turning")
        }
    }
    println(if (failures == 0) "ALL OK" else "$failures FAILURES")
    if (failures != 0) System.exit(1)
}
