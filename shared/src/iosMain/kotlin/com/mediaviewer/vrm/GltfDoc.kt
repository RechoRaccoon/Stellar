package com.mediaviewer.vrm

import kotlinx.serialization.json.JsonObject
import kotlin.math.sqrt

/**
 * The parts of a glTF 2.0 document an avatar needs, read straight out of a
 * [Glb]: the node tree, meshes (positions, normals, texture coordinates,
 * skin weights, triangles, morph targets), skins, materials and where each
 * texture's picture sits in the file.
 *
 * Plain Kotlin on purpose — nothing here knows about SceneKit — so it can be
 * run against real .vrm files on any machine (tools/vrm-test does exactly
 * that). VrmScene.kt turns what this reads into something drawable.
 *
 * Vertex data is read on request, one accessor at a time, rather than all
 * up front: a face with 60 morph targets is tens of megabytes decoded, and
 * the renderer only ever needs one array at a time while it builds.
 */
class GltfNode(
    val index: Int,
    val name: String,
    val children: IntArray,
    /** Parent-relative transform as loaded, column-major 4x4. */
    val local: FloatArray,
    val mesh: Int,
    val skin: Int
) {
    var parent: Int = -1
    /** Model-space transform as loaded, column-major 4x4. */
    var world: FloatArray = local
}

class GltfPrimitive(
    val position: Int,
    val normal: Int,
    val texcoord: Int,
    val joints: Int,
    val weights: Int,
    val indices: Int,
    val material: Int,
    /** POSITION accessor of each morph target (-1 = that target doesn't
     *  move this primitive). */
    val targets: IntArray
)

class GltfMesh(val name: String, val primitives: List<GltfPrimitive>)

class GltfSkin(val joints: IntArray, /** 16 floats per joint, column-major. */ val inverseBind: FloatArray)

class GltfMaterial(
    val name: String,
    /** RGBA, linear, 0..1. */
    val baseColor: FloatArray,
    /** Index into [GltfDoc.images], or -1. */
    val baseImage: Int,
    /** "OPAQUE", "MASK" or "BLEND". */
    val alphaMode: String,
    val alphaCutoff: Float,
    val doubleSided: Boolean,
    /** Texture repeats outside 0..1 (false = clamps). */
    val repeatS: Boolean,
    val repeatT: Boolean,
    /** Later numbers draw later (VRM's own render-queue hint among see-through parts). */
    val renderOrder: Int
)

/** One embedded picture: where its PNG/JPEG bytes are inside [Glb.bytes]. */
class GltfImage(val offset: Int, val length: Int, val mimeType: String)

class GltfDoc(val glb: Glb) {
    private val root: JsonObject = glb.json
    private val accessors = root.at("accessors").arr
    private val bufferViews = root.at("bufferViews").arr

    val nodes: List<GltfNode>
    val meshes: List<GltfMesh>
    val skins: List<GltfSkin>
    val materials: List<GltfMaterial>
    val images: List<GltfImage>
    /** Nodes with no parent, in file order. */
    val roots: List<Int>

    init {
        nodes = root.at("nodes").arr.orEmpty().mapIndexed { i, n ->
            val o = n.obj ?: JsonObject(emptyMap())
            GltfNode(
                index = i,
                name = o.at("name").str ?: "",
                children = o.at("children").arr?.mapNotNull { it.int }?.toIntArray() ?: IntArray(0),
                local = localMatrix(o),
                mesh = o.at("mesh").int ?: -1,
                skin = o.at("skin").int ?: -1
            )
        }
        for (n in nodes) for (c in n.children) nodes.getOrNull(c)?.parent = n.index
        roots = nodes.filter { it.parent < 0 }.map { it.index }
        // Model-space transforms, parents first. (A guard on depth keeps a
        // file with a loop in its tree from hanging the load.)
        fun place(i: Int, parentWorld: FloatArray?, depth: Int) {
            val n = nodes.getOrNull(i) ?: return
            n.world = if (parentWorld == null) n.local else multiplyColumnMajor4x4(parentWorld, n.local)
            if (depth < 256) for (c in n.children) place(c, n.world, depth + 1)
        }
        for (r in roots) place(r, null, 0)

        meshes = root.at("meshes").arr.orEmpty().map { m ->
            val o = m.obj ?: JsonObject(emptyMap())
            GltfMesh(
                name = o.at("name").str ?: "",
                primitives = o.at("primitives").arr.orEmpty().mapNotNull { p ->
                    val po = p.obj ?: return@mapNotNull null
                    // Triangles only (mode 4, also the default) — what every
                    // avatar exporter writes.
                    if ((po.at("mode").int ?: 4) != 4) return@mapNotNull null
                    val position = po.at("attributes.POSITION").int ?: return@mapNotNull null
                    GltfPrimitive(
                        position = position,
                        normal = po.at("attributes.NORMAL").int ?: -1,
                        texcoord = po.at("attributes.TEXCOORD_0").int ?: -1,
                        joints = po.at("attributes.JOINTS_0").int ?: -1,
                        weights = po.at("attributes.WEIGHTS_0").int ?: -1,
                        indices = po.at("indices").int ?: -1,
                        material = po.at("material").int ?: -1,
                        targets = po.at("targets").arr?.map { it.obj?.at("POSITION").int ?: -1 }?.toIntArray() ?: IntArray(0)
                    )
                }
            )
        }

        skins = root.at("skins").arr.orEmpty().map { s ->
            val o = s.obj ?: JsonObject(emptyMap())
            val joints = o.at("joints").arr?.mapNotNull { it.int }?.toIntArray() ?: IntArray(0)
            val ibm = o.at("inverseBindMatrices").int?.let { readFloats(it, 16) }
            // (No matrices in the file means identity for every joint.)
            val inverseBind = if (ibm != null && ibm.size >= joints.size * 16) ibm else FloatArray(joints.size * 16).also {
                for (j in joints.indices) { it[j * 16] = 1f; it[j * 16 + 5] = 1f; it[j * 16 + 10] = 1f; it[j * 16 + 15] = 1f }
            }
            GltfSkin(joints, inverseBind)
        }

        images = root.at("images").arr.orEmpty().map { im ->
            val o = im.obj
            val view = o?.at("bufferView").int?.let { bufferViews?.getOrNull(it).obj }
            val offset = view?.at("byteOffset").int ?: 0
            val length = view?.at("byteLength").int ?: 0
            if (view == null || length <= 0 || offset < 0 || offset + length > glb.binLength) GltfImage(0, 0, "")
            else GltfImage(glb.binStart + offset, length, o?.at("mimeType").str ?: "")
        }

        val textures = root.at("textures").arr
        val samplers = root.at("samplers").arr
        // VRM 0.x keeps its own per-material list beside glTF's (same order).
        val vrm0Materials = root.at("extensions.VRM.materialProperties").arr
        materials = root.at("materials").arr.orEmpty().mapIndexed { index, m ->
            val o = m.obj ?: JsonObject(emptyMap())
            val pbr = o.at("pbrMetallicRoughness").obj
            val factor = pbr?.at("baseColorFactor").arr?.mapNotNull { it.float }
            var texture = pbr?.at("baseColorTexture.index").int
            val v0 = vrm0Materials?.getOrNull(index).obj
            // Older VRM 0.x files only name the picture in VRM's own block.
            if (texture == null) texture = v0?.at("textureProperties._MainTex").int
            val tex = texture?.let { textures?.getOrNull(it).obj }
            val sampler = tex?.at("sampler").int?.let { samplers?.getOrNull(it).obj }
            // 33071 = CLAMP_TO_EDGE; anything else repeats (the default).
            val wrapS = sampler?.at("wrapS").int ?: 10497
            val wrapT = sampler?.at("wrapT").int ?: 10497
            val color = if (factor != null && factor.size >= 4) floatArrayOf(factor[0], factor[1], factor[2], factor[3])
                else v0?.at("vectorProperties._Color").arr?.mapNotNull { it.float }?.takeIf { it.size >= 4 }
                    ?.let { floatArrayOf(it[0], it[1], it[2], it[3]) } ?: floatArrayOf(1f, 1f, 1f, 1f)
            val v1 = o.at("extensions.VRMC_materials_mtoon").obj
            GltfMaterial(
                name = o.at("name").str ?: "",
                baseColor = color,
                baseImage = tex?.at("source").int ?: -1,
                alphaMode = o.at("alphaMode").str ?: "OPAQUE",
                alphaCutoff = o.at("alphaCutoff").float ?: 0.5f,
                doubleSided = o.at("doubleSided").bool ?: false,
                repeatS = wrapS != 33071,
                repeatT = wrapT != 33071,
                // VRM 1.0: an offset among the see-through materials. VRM
                // 0.x: Unity's render queue (2000 solid … 3000 see-through).
                renderOrder = v1?.at("renderQueueOffsetNumber").int
                    ?: v0?.at("renderQueue").int?.takeIf { it > 0 }?.let { it - 3000 } ?: 0
            )
        }
    }

    private fun localMatrix(o: JsonObject): FloatArray {
        o.at("matrix").arr?.mapNotNull { it.float }?.takeIf { it.size == 16 }?.let { return it.toFloatArray() }
        val t = o.at("translation").arr?.mapNotNull { it.float }?.takeIf { it.size == 3 }
        val r = o.at("rotation").arr?.mapNotNull { it.float }?.takeIf { it.size == 4 }
        val s = o.at("scale").arr?.mapNotNull { it.float }?.takeIf { it.size == 3 }
        val m = (if (r != null) Quaternion(r[0], r[1], r[2], r[3]).normalized() else Quaternion.IDENTITY).toColumnMajorMatrix()
        if (s != null) for (c in 0 until 3) for (row in 0 until 3) m[c * 4 + row] *= s[c]
        if (t != null) { m[12] = t[0]; m[13] = t[1]; m[14] = t[2] }
        return m
    }

    // ── accessors ──

    /** How many elements (vertices, indices, …) accessor [index] holds. */
    fun count(index: Int): Int = accessors?.getOrNull(index).obj?.at("count").int ?: 0

    private class View(val start: Int, val stride: Int, val componentType: Int, val normalized: Boolean)

    private fun componentSize(type: Int): Int = when (type) {
        5120, 5121 -> 1
        5122, 5123 -> 2
        else -> 4
    }

    /** Where accessor data starts in [Glb.bytes]; null when it has no
     *  buffer view (all zeros, usually a sparse accessor's base) or would
     *  read outside the file. */
    private fun viewOf(a: JsonObject, components: Int, count: Int): View? {
        val type = a.at("componentType").int ?: return null
        val view = a.at("bufferView").int?.let { bufferViews?.getOrNull(it).obj } ?: return null
        // (Only the .glb's own binary chunk — buffer 0 — is ever used.)
        if ((view.at("buffer").int ?: 0) != 0) return null
        val size = componentSize(type) * components
        val stride = view.at("byteStride").int?.takeIf { it > 0 } ?: size
        val start = glb.binStart + (view.at("byteOffset").int ?: 0) + (a.at("byteOffset").int ?: 0)
        if (count > 0 && (start < glb.binStart || start.toLong() + stride.toLong() * (count - 1) + size > glb.binStart.toLong() + glb.binLength)) return null
        return View(start, stride, type, a.at("normalized").bool ?: false)
    }

    private fun readComponent(at: Int, type: Int): Int {
        val b = glb.bytes
        return when (type) {
            5120 -> b[at].toInt()
            5121 -> b[at].toInt() and 0xFF
            5122 -> ((b[at].toInt() and 0xFF) or (b[at + 1].toInt() shl 8))
            5123 -> (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
            else -> GlbReader.int32(b, at)
        }
    }

    /** Accessor [index] as floats, [components] per element (3 for
     *  positions, 2 for texture coordinates, 16 for matrices…). Whole-number
     *  data marked "normalized" is scaled to 0..1 / -1..1 as glTF defines.
     *  Sparse accessors (how some exporters store morph targets) are
     *  filled in. Null if the accessor is missing or malformed. */
    fun readFloats(index: Int, components: Int): FloatArray? {
        val a = accessors?.getOrNull(index).obj ?: return null
        val count = a.at("count").int ?: return null
        if (count < 0 || count.toLong() * components > 64_000_000L) return null
        val out = FloatArray(count * components)
        val view = viewOf(a, components, count)
        if (view != null) fill(out, 0, count, components, view)
        else if (a.at("bufferView").int != null) return null
        val sparse = a.at("sparse").obj
        if (sparse != null) {
            val n = sparse.at("count").int ?: 0
            val indexType = sparse.at("indices.componentType").int ?: return null
            val indexView = viewOfSparse(sparse.at("indices").obj, componentSize(indexType), n) ?: return null
            val type = a.at("componentType").int ?: return null
            val valueView = viewOfSparse(sparse.at("values").obj, componentSize(type) * components, n) ?: return null
            val values = FloatArray(n * components)
            fill(values, 0, n, components, View(valueView, componentSize(type) * components, type, a.at("normalized").bool ?: false))
            for (i in 0 until n) {
                val target = readComponent(indexView + i * componentSize(indexType), indexType)
                if (target < 0 || target >= count) continue
                for (c in 0 until components) out[target * components + c] = values[i * components + c]
            }
        }
        return out
    }

    private fun viewOfSparse(o: JsonObject?, elementSize: Int, count: Int): Int? {
        val view = o?.at("bufferView").int?.let { bufferViews?.getOrNull(it).obj } ?: return null
        val start = glb.binStart + (view.at("byteOffset").int ?: 0) + (o?.at("byteOffset").int ?: 0)
        if (start < glb.binStart || start.toLong() + elementSize.toLong() * count > glb.binStart.toLong() + glb.binLength) return null
        return start
    }

    private fun fill(out: FloatArray, outOffset: Int, count: Int, components: Int, view: View) {
        val b = glb.bytes
        val size = componentSize(view.componentType)
        var o = outOffset
        for (i in 0 until count) {
            var at = view.start + i * view.stride
            for (c in 0 until components) {
                out[o++] = when (view.componentType) {
                    5126 -> Float.fromBits(GlbReader.int32(b, at))
                    else -> {
                        val v = readComponent(at, view.componentType)
                        if (!view.normalized) v.toFloat() else when (view.componentType) {
                            5120 -> maxOf(v / 127f, -1f)
                            5121 -> v / 255f
                            5122 -> maxOf(v / 32767f, -1f)
                            5123 -> v / 65535f
                            else -> v.toFloat()
                        }
                    }
                }
                at += size
            }
        }
    }

    /** Accessor [index] as whole numbers, [components] per element
     *  (triangle indices: 1; joint numbers: 4). */
    fun readInts(index: Int, components: Int): IntArray? {
        val a = accessors?.getOrNull(index).obj ?: return null
        val count = a.at("count").int ?: return null
        if (count < 0 || count.toLong() * components > 64_000_000L) return null
        val view = viewOf(a, components, count) ?: return null
        val out = IntArray(count * components)
        val size = componentSize(view.componentType)
        var o = 0
        for (i in 0 until count) {
            var at = view.start + i * view.stride
            for (c in 0 until components) { out[o++] = readComponent(at, view.componentType); at += size }
        }
        return out
    }

    // ── what the renderer asks for, ready to hand over ──

    /** The triangles of [p] as vertex numbers; 0,1,2,3,… when the file
     *  lists none. Triangles naming a vertex that doesn't exist are dropped. */
    fun triangles(p: GltfPrimitive): IntArray {
        val vertices = count(p.position)
        val raw = if (p.indices >= 0) readInts(p.indices, 1) ?: IntArray(0) else IntArray(vertices) { it }
        val whole = raw.size - raw.size % 3
        var bad = false
        for (i in 0 until whole) if (raw[i] < 0 || raw[i] >= vertices) { bad = true; break }
        if (!bad) return if (whole == raw.size) raw else raw.copyOf(whole)
        val kept = ArrayList<Int>(whole)
        var i = 0
        while (i < whole) {
            val a = raw[i]; val b = raw[i + 1]; val c = raw[i + 2]
            if (a in 0 until vertices && b in 0 until vertices && c in 0 until vertices) { kept.add(a); kept.add(b); kept.add(c) }
            i += 3
        }
        return kept.toIntArray()
    }

    /** Skin weights of [p], four per vertex, each vertex's adding up to 1
     *  (some exporters leave them a little off, which shows as a mesh that
     *  swells or shrinks as it bends). Null when [p] isn't skinned. */
    fun weights(p: GltfPrimitive): FloatArray? {
        if (p.weights < 0 || p.joints < 0) return null
        val w = readFloats(p.weights, 4) ?: return null
        var i = 0
        while (i + 3 < w.size) {
            val sum = w[i] + w[i + 1] + w[i + 2] + w[i + 3]
            if (sum > 1e-6f) { w[i] /= sum; w[i + 1] /= sum; w[i + 2] /= sum; w[i + 3] /= sum }
            else { w[i] = 1f; w[i + 1] = 0f; w[i + 2] = 0f; w[i + 3] = 0f }
            i += 4
        }
        return w
    }

    /** Which of the skin's joints each of those weights belongs to, four
     *  per vertex; numbers past the end of the skin become joint 0. */
    fun joints(p: GltfPrimitive, jointCount: Int): IntArray? {
        if (p.weights < 0 || p.joints < 0) return null
        val j = readInts(p.joints, 4) ?: return null
        for (i in j.indices) if (j[i] < 0 || j[i] >= jointCount) j[i] = 0
        return j
    }

    /** Lowest and highest corner of everything the model draws, in model
     *  space at rest: (minX, minY, minZ, maxX, maxY, maxZ). Skinned meshes
     *  are measured through their skeleton, like they're drawn. */
    fun bounds(): FloatArray? {
        var any = false
        val lo = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val hi = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (n in nodes) {
            val mesh = meshes.getOrNull(n.mesh) ?: continue
            val skin = skins.getOrNull(n.skin)
            for (p in mesh.primitives) {
                val pos = readFloats(p.position, 3) ?: continue
                val w = if (skin != null) weights(p) else null
                val j = if (skin != null) joints(p, skin.joints.size) else null
                // Every 7th vertex is plenty to find the outline.
                var v = 0
                while (v * 3 + 2 < pos.size) {
                    val out = if (skin != null && w != null && j != null) skinned(pos, v, w, j, skin) else transformPoint(n.world, pos[v * 3], pos[v * 3 + 1], pos[v * 3 + 2])
                    for (k in 0 until 3) { if (out[k] < lo[k]) lo[k] = out[k]; if (out[k] > hi[k]) hi[k] = out[k] }
                    any = true
                    v += 7
                }
            }
        }
        return if (any) floatArrayOf(lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]) else null
    }

    /** Where vertex [v] ends up at rest once its skin is applied. */
    fun skinned(pos: FloatArray, v: Int, w: FloatArray, j: IntArray, skin: GltfSkin): FloatArray {
        val out = FloatArray(3)
        for (k in 0 until 4) {
            val weight = w[v * 4 + k]
            if (weight <= 0f) continue
            val joint = j[v * 4 + k]
            val node = nodes.getOrNull(skin.joints[joint]) ?: continue
            val bind = skin.inverseBind.copyOfRange(joint * 16, joint * 16 + 16)
            val p = transformPoint(multiplyColumnMajor4x4(node.world, bind), pos[v * 3], pos[v * 3 + 1], pos[v * 3 + 2])
            out[0] += p[0] * weight; out[1] += p[1] * weight; out[2] += p[2] * weight
        }
        return out
    }

    companion object {
        fun transformPoint(m: FloatArray, x: Float, y: Float, z: Float): FloatArray = floatArrayOf(
            m[0] * x + m[4] * y + m[8] * z + m[12],
            m[1] * x + m[5] * y + m[9] * z + m[13],
            m[2] * x + m[6] * y + m[10] * z + m[14]
        )

        fun length(x: Float, y: Float, z: Float): Float = sqrt(x * x + y * y + z * z)
    }
}
