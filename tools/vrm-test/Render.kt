import com.mediaviewer.vrm.*
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.tan

/**
 * Draws an avatar to a PNG with a small software renderer — the same
 * picture VRM mode's stage is meant to show (same camera, same skinning and
 * morph maths, textures as painted), without SceneKit. It's for LOOKING at
 * what the loader, the retargeter and the spring bones produce from a real
 * file: a head that turns the wrong way or an arm folded through the chest
 * shows up here, on any machine.
 *
 *   sh tools/vrm-test/run.sh --render out-folder a.vrm [b.vrm …]
 *
 * Three pictures per file: at rest as the file stores it, posed (head
 * turned to the avatar's own right as seen in a mirror, left eye closed,
 * mouth open, arms relaxed), and the same after a second of spring bones.
 */
class Rig(val doc: GltfDoc) : VrmRig {
    val locals = HashMap<Int, FloatArray>()
    val morphs = HashMap<Int, FloatArray>()
    override fun restLocal(node: Int) = doc.nodes.getOrNull(node)?.local
    override fun restWorld(node: Int) = doc.nodes.getOrNull(node)?.world
    override fun parentOf(node: Int) = doc.nodes.getOrNull(node)?.parent?.takeIf { it >= 0 }
    override fun setLocal(node: Int, matrix: FloatArray) { locals[node] = matrix }
    override fun morphCount(node: Int): Int =
        doc.meshes.getOrNull(doc.nodes.getOrNull(node)?.mesh ?: -1)?.primitives?.maxOfOrNull { it.targets.size } ?: 0
    override fun setMorphWeights(node: Int, weights: FloatArray) { morphs[node] = weights.copyOf() }
    fun local(n: Int): FloatArray = locals[n] ?: doc.nodes[n].local
    fun world(n: Int): FloatArray {
        val p = doc.nodes[n].parent
        return if (p < 0) local(n) else multiplyColumnMajor4x4(world(p), local(n))
    }
}

fun render(doc: GltfDoc, rig: Rig, facesNegativeZ: Boolean, headY: Float, height: Float, w: Int, h: Int, zoom: Float): BufferedImage {
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val color = IntArray(w * h) { 0x202028 }
    val depth = FloatArray(w * h) { Float.MAX_VALUE }
    // The stage's camera (VrmStage.placeCamera).
    val fov = 24.0
    val span = height * 0.46f / zoom
    val dist = (span / 2f) / tan(fov / 2.0 * PI / 180.0).toFloat()
    val aim = headY - height * (0.075f / zoom)
    val f = (h / 2f) / tan(fov / 2.0 * PI / 180.0).toFloat()
    val textures = HashMap<Int, BufferedImage?>()
    fun texture(i: Int): BufferedImage? = textures.getOrPut(i) {
        val im = doc.images.getOrNull(i)?.takeIf { it.length > 0 } ?: return@getOrPut null
        runCatching { ImageIO.read(ByteArrayInputStream(doc.glb.bytes, im.offset, im.length)) }.getOrNull()
    }
    val worlds = HashMap<Int, FloatArray>()
    fun world(n: Int) = worlds.getOrPut(n) { rig.world(n) }

    class Draw(val blend: Boolean, val run: () -> Unit)
    val draws = ArrayList<Draw>()
    for (n in doc.nodes) {
        val mesh = doc.meshes.getOrNull(n.mesh) ?: continue
        val skin = doc.skins.getOrNull(n.skin)
        val weights = rig.morphs[n.index]
        for (p in mesh.primitives) {
            val pos = doc.readFloats(p.position, 3)?.copyOf() ?: continue
            val count = pos.size / 3
            if (weights != null) for (t in p.targets.indices) {
                val wt = weights.getOrElse(t) { 0f }
                if (wt <= 0f || p.targets[t] < 0) continue
                val d = doc.readFloats(p.targets[t], 3) ?: continue
                for (i in pos.indices) pos[i] += d[i] * wt
            }
            val sw = if (skin != null) doc.weights(p) else null
            val sj = if (skin != null) doc.joints(p, skin.joints.size) else null
            val skinM = if (skin != null && sw != null && sj != null) Array(skin.joints.size) { j ->
                multiplyColumnMajor4x4(world(skin.joints[j]), skin.inverseBind.copyOfRange(j * 16, j * 16 + 16))
            } else null
            val sx = FloatArray(count); val sy = FloatArray(count); val sz = FloatArray(count)
            for (v in 0 until count) {
                var x = 0f; var y = 0f; var z = 0f
                if (skinM != null) {
                    for (k in 0 until 4) {
                        val wt = sw!![v * 4 + k]; if (wt <= 0f) continue
                        val q = GltfDoc.transformPoint(skinM[sj!![v * 4 + k]], pos[v * 3], pos[v * 3 + 1], pos[v * 3 + 2])
                        x += q[0] * wt; y += q[1] * wt; z += q[2] * wt
                    }
                } else {
                    val q = GltfDoc.transformPoint(world(n.index), pos[v * 3], pos[v * 3 + 1], pos[v * 3 + 2])
                    x = q[0]; y = q[1]; z = q[2]
                }
                if (facesNegativeZ) { x = -x; z = -z }
                // Camera at (0, aim, dist) looking down -Z.
                val cz = dist - z
                sx[v] = w / 2f + x / cz * f
                sy[v] = h / 2f - (y - aim) / cz * f
                sz[v] = cz
            }
            val uv = doc.readFloats(p.texcoord, 2)
            val tris = doc.triangles(p)
            val m = doc.materials.getOrNull(p.material)
            val tex = m?.let { texture(it.baseImage) }
            val base = m?.baseColor ?: floatArrayOf(1f, 1f, 1f, 1f)
            val mode = m?.alphaMode ?: "OPAQUE"
            draws.add(Draw(mode == "BLEND") {
                var i = 0
                while (i + 2 < tris.size) {
                    val a = tris[i]; val b = tris[i + 1]; val c = tris[i + 2]; i += 3
                    if (sz[a] <= 0.01f || sz[b] <= 0.01f || sz[c] <= 0.01f) continue
                    val area = (sx[b] - sx[a]) * (sy[c] - sy[a]) - (sx[c] - sx[a]) * (sy[b] - sy[a])
                    if (area == 0f) continue
                    // Back faces are culled unless the material is two-sided.
                    if (m?.doubleSided != true && area > 0f) continue
                    val minX = maxOf(0, minOf(sx[a], sx[b], sx[c]).toInt()); val maxX = minOf(w - 1, maxOf(sx[a], sx[b], sx[c]).toInt() + 1)
                    val minY = maxOf(0, minOf(sy[a], sy[b], sy[c]).toInt()); val maxY = minOf(h - 1, maxOf(sy[a], sy[b], sy[c]).toInt() + 1)
                    for (py in minY..maxY) for (px in minX..maxX) {
                        val x = px + 0.5f; val y = py + 0.5f
                        val w0 = ((sx[b] - x) * (sy[c] - y) - (sx[c] - x) * (sy[b] - y)) / area
                        val w1 = ((sx[c] - x) * (sy[a] - y) - (sx[a] - x) * (sy[c] - y)) / area
                        val w2 = 1f - w0 - w1
                        if (w0 < 0f || w1 < 0f || w2 < 0f) continue
                        val z = w0 * sz[a] + w1 * sz[b] + w2 * sz[c]
                        val at = py * w + px
                        if (z >= depth[at]) continue
                        var r = base[0]; var g = base[1]; var bl = base[2]; var al = base[3]
                        if (tex != null && uv != null) {
                            var u = w0 * uv[a * 2] + w1 * uv[b * 2] + w2 * uv[c * 2]
                            var v = w0 * uv[a * 2 + 1] + w1 * uv[b * 2 + 1] + w2 * uv[c * 2 + 1]
                            u -= kotlin.math.floor(u); v -= kotlin.math.floor(v)
                            // glTF: (0,0) is the picture's top-left corner.
                            val argb = tex.getRGB((u * (tex.width - 1)).toInt().coerceIn(0, tex.width - 1), (v * (tex.height - 1)).toInt().coerceIn(0, tex.height - 1))
                            r *= ((argb shr 16) and 0xFF) / 255f; g *= ((argb shr 8) and 0xFF) / 255f; bl *= (argb and 0xFF) / 255f
                            al *= ((argb ushr 24) and 0xFF) / 255f
                        }
                        if (mode == "MASK" && al < m!!.alphaCutoff) continue
                        if (mode == "BLEND") {
                            if (al <= 0.003f) continue
                            val old = color[at]
                            r = r * al + ((old shr 16) and 0xFF) / 255f * (1 - al)
                            g = g * al + ((old shr 8) and 0xFF) / 255f * (1 - al)
                            bl = bl * al + (old and 0xFF) / 255f * (1 - al)
                        } else depth[at] = z
                        color[at] = ((r.coerceIn(0f, 1f) * 255).toInt() shl 16) or ((g.coerceIn(0f, 1f) * 255).toInt() shl 8) or (bl.coerceIn(0f, 1f) * 255).toInt()
                    }
                }
            })
        }
    }
    draws.filter { !it.blend }.forEach { it.run() }
    draws.filter { it.blend }.forEach { it.run() }
    img.setRGB(0, 0, w, h, color, 0, w)
    return img
}

fun main(args: Array<String>) {
    val out = File(args[0]).also { it.mkdirs() }
    for (path in args.drop(1)) {
        val name = path.substringAfterLast('/').substringBeforeLast('.')
        val glb = GlbReader.read(File(path).readBytes()) ?: continue
        val doc = GltfDoc(glb)
        val vrm = VrmParser.parse(glb.json) ?: continue
        val rig = Rig(doc)
        val target = AvatarRetargeter.buildTarget(rig, vrm)
        val b = doc.bounds()
        val height = b?.let { it[4] - it[1] } ?: 1.5f
        val headY = target.bones["head"]?.restWorldPosition?.get(1) ?: 1.4f
        ImageIO.write(render(doc, rig, target.facesNegativeZ, headY, height, 420, 560, 0.42f), "png", File(out, "$name-1-rest-full.png"))
        ImageIO.write(render(doc, rig, target.facesNegativeZ, headY, height, 420, 560, 1f), "png", File(out, "$name-2-rest.png"))

        // What ARKit would report for: head turned towards the camera's
        // right-hand side of the picture and tipped down a little, the
        // person's LEFT eye shut, mouth open, a smile.
        AvatarRetargeter.smoothingScale = 0f
        val turn = Quaternion(0.10f, 0.26f, 0f, 1f).normalized().toColumnMajorMatrix()
        AvatarRetargeter.applyPose(target, TrackingFrame(faceMatrix = turn, body = null, trackLegs = false))
        AvatarRetargeter.applyExpressions(target, vrm, mapOf("eyeBlinkLeft" to 1f, "jawOpen" to 0.7f, "mouthSmileLeft" to 0.6f, "mouthSmileRight" to 0.6f), remapBlink = false)
        ImageIO.write(render(doc, rig, target.facesNegativeZ, headY, height, 420, 560, 1f), "png", File(out, "$name-3-posed.png"))
        ImageIO.write(render(doc, rig, target.facesNegativeZ, headY, height, 420, 560, 0.42f), "png", File(out, "$name-4-posed-full.png"))

        val spring = VrmSpringParser.parse(glb.json)
        if (spring.joints.isNotEmpty()) {
            val owned = target.bones.values.map { it.entity }.toSet()
            val sim = VrmSpringSim(doc, spring, owned, { rig.local(it) }, { n, m -> rig.locals[n] = m })
            repeat(90) { sim.update(1f / 60f) }
            ImageIO.write(render(doc, rig, target.facesNegativeZ, headY, height, 420, 560, 1f), "png", File(out, "$name-5-springs.png"))
        }
        println("rendered $name")
    }
}
