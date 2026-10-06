package com.mediaviewer.vrm

import com.mediaviewer.platform.Log
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.cValue
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSNumber
import platform.Foundation.NSValue
import platform.Foundation.create
import platform.SceneKit.SCNBlendModeAlpha
import platform.SceneKit.SCNBlendModeReplace
import platform.SceneKit.SCNCullModeBack
import platform.SceneKit.SCNGeometry
import platform.SceneKit.SCNGeometryElement
import platform.SceneKit.SCNGeometryPrimitiveTypeTriangles
import platform.SceneKit.SCNGeometrySource
import platform.SceneKit.SCNGeometrySourceSemanticBoneIndices
import platform.SceneKit.SCNGeometrySourceSemanticBoneWeights
import platform.SceneKit.SCNGeometrySourceSemanticNormal
import platform.SceneKit.SCNGeometrySourceSemanticTexcoord
import platform.SceneKit.SCNGeometrySourceSemanticVertex
import platform.SceneKit.SCNColorMaskNone
import platform.SceneKit.SCNLightingModelConstant
import platform.SceneKit.SCNLightingModelLambert
import platform.SceneKit.SCNMaterial
import platform.SceneKit.SCNMatrix4
import platform.SceneKit.SCNMorpher
import platform.SceneKit.SCNMorpherCalculationModeAdditive
import platform.SceneKit.SCNNode
import platform.SceneKit.SCNShaderModifierEntryPointSurface
import platform.SceneKit.SCNSkinner
import platform.SceneKit.SCNWrapModeClamp
import platform.SceneKit.SCNWrapModeRepeat
import platform.SceneKit.valueWithSCNMatrix4
import platform.UIKit.UIColor
import platform.UIKit.UIImage

/**
 * Things about how the model is drawn that can't be checked without a
 * phone in hand, each with a switch in VRM Settings › Troubleshooting so a
 * wrong guess is fixed by flipping it, not by a new build.
 */
class VrmSceneOptions(
    /** Texture pictures upside down (the file's and SceneKit's idea of
     *  "top of the picture" disagree). */
    val flipTextures: Boolean = false,
    /** Cut-out materials (lashes, hair edges) drawn by blending instead of
     *  by discarding see-through pixels in a shader. */
    val blendCutouts: Boolean = false,
    /** Morph targets handed over as finished shapes instead of as
     *  differences from the base shape. */
    val absoluteMorphs: Boolean = false
)

/** One piece of the avatar that can be hidden on its own (VRM Settings ›
 *  Avatar parts): a mesh, or one material's share of a mesh. [id] is the
 *  same "node:primitive" Android uses, so a choice carries across. */
class VrmPart(val id: String, val label: String)

/**
 * The avatar as SceneKit draws it, built from a [GltfDoc]: one SCNNode per
 * glTF node (so bones are ordinary nodes), each mesh as skinned geometry
 * with its morph targets, and flat "what the texture says" materials — the
 * look Android's Full Bright gives, which is how toon avatars are meant to
 * read. It is also the [VrmRig] the retargeter and spring bones move.
 *
 * Build it off the main thread (decoding a face's morph targets takes a
 * moment); after that, only touch it from SceneKit's own render callback.
 */
@OptIn(ExperimentalForeignApi::class)
class VrmScene(val doc: GltfDoc, private val options: VrmSceneOptions) : VrmRig {
    /** Everything in the file, under one node: add it to a scene. */
    val modelRoot: SCNNode = SCNNode.node()

    private val scnNodes: List<SCNNode> = doc.nodes.map { n ->
        SCNNode.node().also { it.setName(n.name.ifBlank { null }); it.setTransform(matrix(n.local)) }
    }
    /** Each node's transform as it stands now (rest until something moves it). */
    private val current: Array<FloatArray> = Array(doc.nodes.size) { doc.nodes[it].local }

    /** One drawn piece of a mesh and how the mesh's morph targets map onto
     *  the ones it actually kept ([slots][i] = its own slot for the mesh's
     *  target i, or -1 if that target doesn't move this piece). */
    private class Piece(val morpher: SCNMorpher, val slots: IntArray, val kept: Int)
    private val pieces = HashMap<Int, List<Piece>>()
    private val morphCounts = HashMap<Int, Int>()

    private val images = HashMap<Int, UIImage?>()
    private val materials = HashMap<Int, SCNMaterial>()
    private val plain: SCNMaterial by lazy { makeMaterial(null) }

    /** Where one [VrmPart] is drawn: which piece, which of its materials. */
    private class PartSlot(val holder: SCNNode, val element: Int, val material: SCNMaterial)
    private val partSlots = LinkedHashMap<String, PartSlot>()
    private val partList = ArrayList<VrmPart>()
    /** Every hideable piece, in the file's order. */
    val parts: List<VrmPart> get() = partList
    /** Stands in for a hidden part's material: draws nothing at all. */
    private val nothing: SCNMaterial by lazy {
        SCNMaterial.material().also {
            it.setLightingModelName(SCNLightingModelConstant)
            it.setTransparency(0.0)
            it.setBlendMode(SCNBlendModeAlpha)
            it.setWritesToDepthBuffer(false)
            it.setColorBufferWriteMask(SCNColorMaskNone)
        }
    }
    private var hiddenNow: Set<String> = emptySet()
    private var litNow = false

    /** What went into the scene, for the debug line. */
    var vertexCount = 0; private set
    var triangleCount = 0; private set
    var morphTargetCount = 0; private set

    init {
        for (n in doc.nodes) for (c in n.children) scnNodes.getOrNull(c)?.let { scnNodes[n.index].addChildNode(it) }
        for (r in doc.roots) modelRoot.addChildNode(scnNodes[r])
        for (n in doc.nodes) {
            val mesh = doc.meshes.getOrNull(n.mesh) ?: continue
            runCatching { buildMesh(n, mesh) }.onFailure { Log.e("VrmScene", "Couldn't build mesh ${mesh.name}", it) }
        }
    }

    // ── VrmRig ──

    override fun restLocal(node: Int): FloatArray? = doc.nodes.getOrNull(node)?.local
    override fun restWorld(node: Int): FloatArray? = doc.nodes.getOrNull(node)?.world
    override fun parentOf(node: Int): Int? = doc.nodes.getOrNull(node)?.parent?.takeIf { it >= 0 }

    override fun setLocal(node: Int, matrix: FloatArray) {
        if (node !in current.indices) return
        current[node] = matrix
        scnNodes[node].setTransform(matrix(matrix))
    }

    fun currentLocal(node: Int): FloatArray = current[node]

    override fun morphCount(node: Int): Int = morphCounts[node] ?: 0

    override fun setMorphWeights(node: Int, weights: FloatArray) {
        for (piece in pieces[node] ?: return) {
            val out = arrayOfNulls<NSNumber>(piece.kept)
            for (i in piece.slots.indices) {
                val slot = piece.slots[i]
                if (slot >= 0) out[slot] = NSNumber(float = weights.getOrElse(i) { 0f })
            }
            piece.morpher.setWeights(out.map { it ?: NSNumber(float = 0f) })
        }
    }

    /** Hides exactly the parts whose ids are in [hidden]. */
    fun setHiddenParts(hidden: Set<String>) {
        if (hidden == hiddenNow) return
        hiddenNow = hidden
        val perHolder = HashMap<SCNNode, Boolean>()
        for ((id, slot) in partSlots) {
            val hide = id in hidden
            runCatching { slot.holder.geometry?.replaceMaterialAtIndex(slot.element.toULong(), if (hide) nothing else slot.material) }
            perHolder[slot.holder] = (perHolder[slot.holder] ?: true) && hide
        }
        // A piece with nothing left to draw isn't drawn (or skinned) at all.
        for ((holder, allHidden) in perHolder) holder.setHidden(allHidden)
    }

    /**
     * Lit or Full Bright. Lit: surfaces take the scene's lights (soft
     * shading that follows the avatar's shape). Full Bright: every surface
     * shows its texture exactly as painted.
     */
    fun setLit(lit: Boolean) {
        if (lit == litNow) return
        litNow = lit
        val model = if (lit) SCNLightingModelLambert else SCNLightingModelConstant
        for (m in materials.values) m.setLightingModelName(model)
        if (plainMade) plain.setLightingModelName(model)
    }
    private var plainMade = false

    /** "Hair", or "Body · Tops" when a mesh has several materials — the
     *  same tidying of Unity/VRoid names as Android. */
    private fun partLabel(nodeName: String, materialName: String, primitive: Int, count: Int): String {
        val node = nodeName.replace('_', ' ').trim().ifEmpty { "Mesh" }
        if (count <= 1) return node
        val material = materialName
            .replace(Regex("\\s*\\(Instance\\)"), "")
            .replace(Regex("^N\\d+_\\d+_\\d+_"), "")
            .replace(Regex("_\\d+_[A-Z]+$"), "")
            .replace('_', ' ').trim()
            .ifEmpty { "part ${primitive + 1}" }
        return "$node · $material"
    }

    // ── building ──

    private fun matrix(m: FloatArray): CValue<SCNMatrix4> = cValue {
        m11 = m[0]; m12 = m[1]; m13 = m[2]; m14 = m[3]
        m21 = m[4]; m22 = m[5]; m23 = m[6]; m24 = m[7]
        m31 = m[8]; m32 = m[9]; m33 = m[10]; m34 = m[11]
        m41 = m[12]; m42 = m[13]; m43 = m[14]; m44 = m[15]
    }

    private fun data(a: FloatArray): NSData =
        if (a.isEmpty()) NSData() else a.usePinned { NSData.create(bytes = it.addressOf(0), length = (a.size * 4).toULong()) }

    private fun data(a: IntArray): NSData =
        if (a.isEmpty()) NSData() else a.usePinned { NSData.create(bytes = it.addressOf(0), length = (a.size * 4).toULong()) }

    private fun data(a: ShortArray): NSData =
        if (a.isEmpty()) NSData() else a.usePinned { NSData.create(bytes = it.addressOf(0), length = (a.size * 2).toULong()) }

    private fun floatSource(values: FloatArray, semantic: String?, components: Int): SCNGeometrySource =
        SCNGeometrySource.geometrySourceWithData(
            data(values), semantic = semantic ?: "", vectorCount = (values.size / components).toLong(), floatComponents = true,
            componentsPerVector = components.toLong(), bytesPerComponent = 4, dataOffset = 0, dataStride = (components * 4).toLong()
        )

    private fun buildMesh(n: GltfNode, mesh: GltfMesh) {
        val skin = doc.skins.getOrNull(n.skin)
        val targetCount = mesh.primitives.maxOfOrNull { it.targets.size } ?: 0
        if (targetCount > 0) morphCounts[n.index] = targetCount
        val built = ArrayList<Piece>()
        // Primitives that share their vertices are one piece with several
        // materials; the rest are each their own (both layouts are common).
        for ((_, group) in mesh.primitives.groupBy { it.position }) {
            val first = group.first()
            val positions = doc.readFloats(first.position, 3) ?: continue
            val vertices = positions.size / 3
            if (vertices == 0) continue
            val sources = ArrayList<SCNGeometrySource>()
            sources.add(floatSource(positions, SCNGeometrySourceSemanticVertex, 3))
            doc.readFloats(first.normal, 3)?.takeIf { it.size == vertices * 3 }?.let {
                sources.add(floatSource(it, SCNGeometrySourceSemanticNormal, 3))
            }
            doc.readFloats(first.texcoord, 2)?.takeIf { it.size == vertices * 2 }?.let { uv ->
                if (options.flipTextures) { var i = 1; while (i < uv.size) { uv[i] = 1f - uv[i]; i += 2 } }
                sources.add(floatSource(uv, SCNGeometrySourceSemanticTexcoord, 2))
            }
            val elements = ArrayList<SCNGeometryElement>()
            val mats = ArrayList<SCNMaterial>()
            val kept = ArrayList<Int>()
            for (p in group) {
                val tris = doc.triangles(p)
                if (tris.isEmpty()) continue
                kept.add(mesh.primitives.indexOf(p))
                elements.add(SCNGeometryElement.geometryElementWithData(
                    data(tris), primitiveType = SCNGeometryPrimitiveTypeTriangles,
                    primitiveCount = (tris.size / 3).toLong(), bytesPerIndex = 4
                ))
                mats.add(material(p.material))
                triangleCount += tris.size / 3
            }
            if (elements.isEmpty()) continue
            vertexCount += vertices
            val geometry = SCNGeometry.geometryWithSources(sources, elements = elements)
            geometry.setMaterials(mats)
            val holder = SCNNode.node()
            holder.setGeometry(geometry)
            for ((element, primitive) in kept.withIndex()) {
                val id = "${n.index}:$primitive"
                val materialName = doc.materials.getOrNull(mesh.primitives[primitive].material)?.name.orEmpty()
                partSlots[id] = PartSlot(holder, element, mats[element])
                partList.add(VrmPart(id, partLabel(n.name.ifBlank { mesh.name }, materialName, primitive, mesh.primitives.size)))
            }
            // Draw order: solid first, then cut-outs, then see-through
            // parts in the order the file asks for.
            holder.setRenderingOrder((group.maxOfOrNull { p ->
                val m = doc.materials.getOrNull(p.material)
                when (m?.alphaMode) { "BLEND" -> 200 + m.renderOrder.coerceIn(-90, 90); "MASK" -> 100; else -> 0 }
            } ?: 0).toLong())

            // Morph targets: only the ones that move this piece are kept.
            if (targetCount > 0) {
                val slots = IntArray(targetCount) { -1 }
                val targets = ArrayList<SCNGeometry>()
                for (t in 0 until targetCount) {
                    val accessor = group.firstNotNullOfOrNull { p -> p.targets.getOrNull(t)?.takeIf { it >= 0 } } ?: continue
                    val delta = doc.readFloats(accessor, 3)?.takeIf { it.size == vertices * 3 } ?: continue
                    var moves = false
                    for (v in delta) if (v > 1e-6f || v < -1e-6f) { moves = true; break }
                    if (!moves) continue
                    if (options.absoluteMorphs) for (i in delta.indices) delta[i] += positions[i]
                    slots[t] = targets.size
                    targets.add(SCNGeometry.geometryWithSources(listOf(floatSource(delta, SCNGeometrySourceSemanticVertex, 3)), elements = null))
                }
                if (targets.isNotEmpty()) {
                    val morpher = SCNMorpher()
                    if (!options.absoluteMorphs) morpher.setCalculationMode(SCNMorpherCalculationModeAdditive)
                    morpher.setTargets(targets)
                    holder.setMorpher(morpher)
                    built.add(Piece(morpher, slots, targets.size))
                    morphTargetCount += targets.size
                }
            }

            if (skin != null && skin.joints.isNotEmpty()) {
                val w = doc.weights(first)
                val j = doc.joints(first, skin.joints.size)
                if (w != null && j != null && w.size == vertices * 4 && j.size == vertices * 4) {
                    val indices = ShortArray(j.size) { j[it].toShort() }
                    val boneIndices = SCNGeometrySource.geometrySourceWithData(
                        data(indices), semantic = SCNGeometrySourceSemanticBoneIndices ?: "", vectorCount = vertices.toLong(),
                        floatComponents = false, componentsPerVector = 4, bytesPerComponent = 2, dataOffset = 0, dataStride = 8
                    )
                    val bones = skin.joints.map { scnNodes.getOrNull(it) ?: modelRoot }
                    val binds = List(skin.joints.size) { k ->
                        NSValue.valueWithSCNMatrix4(matrix(skin.inverseBind.copyOfRange(k * 16, k * 16 + 16)))
                    }
                    holder.setSkinner(SCNSkinner.skinnerWithBaseGeometry(
                        geometry, bones = bones, boneInverseBindTransforms = binds,
                        boneWeights = floatSource(w, SCNGeometrySourceSemanticBoneWeights, 4), boneIndices = boneIndices
                    ))
                }
            }
            scnNodes[n.index].addChildNode(holder)
        }
        if (built.isNotEmpty()) pieces[n.index] = built
    }

    private fun image(index: Int): UIImage? = images.getOrPut(index) {
        val im = doc.images.getOrNull(index)?.takeIf { it.length > 0 } ?: return@getOrPut null
        runCatching {
            doc.glb.bytes.usePinned { UIImage.imageWithData(NSData.create(bytes = it.addressOf(im.offset), length = im.length.toULong())) }
        }.getOrNull()
    }

    private fun material(index: Int): SCNMaterial {
        val m = doc.materials.getOrNull(index) ?: run { plainMade = true; return plain }
        return materials.getOrPut(index) { makeMaterial(m) }
    }

    private fun makeMaterial(m: GltfMaterial?): SCNMaterial {
        val out = SCNMaterial.material()
        // "Constant": the surface shows its texture exactly as painted, no
        // scene lights involved.
        out.setLightingModelName(SCNLightingModelConstant)
        out.setCullMode(SCNCullModeBack)
        if (m == null) { out.diffuse.setContents(UIColor.whiteColor); return out }
        out.setName(m.name)
        out.setDoubleSided(m.doubleSided)
        val picture = if (m.baseImage >= 0) image(m.baseImage) else null
        val c = m.baseColor
        if (picture != null) {
            out.diffuse.setContents(picture)
            out.diffuse.setWrapS(if (m.repeatS) SCNWrapModeRepeat else SCNWrapModeClamp)
            out.diffuse.setWrapT(if (m.repeatT) SCNWrapModeRepeat else SCNWrapModeClamp)
            // The file's colour tints the picture (white = as painted).
            if (c[0] < 0.999f || c[1] < 0.999f || c[2] < 0.999f) {
                out.multiply.setContents(UIColor.colorWithRed(c[0].toDouble(), green = c[1].toDouble(), blue = c[2].toDouble(), alpha = 1.0))
            }
        } else {
            out.diffuse.setContents(UIColor.colorWithRed(c[0].toDouble(), green = c[1].toDouble(), blue = c[2].toDouble(), alpha = c[3].toDouble()))
        }
        when {
            m.alphaMode == "BLEND" || (m.alphaMode == "MASK" && options.blendCutouts) -> {
                out.setBlendMode(SCNBlendModeAlpha)
                // See-through parts don't hide what's behind them.
                out.setWritesToDepthBuffer(m.alphaMode == "MASK")
                if (c[3] < 0.999f) out.setTransparency(c[3].toDouble())
            }
            m.alphaMode == "MASK" -> {
                // Solid where the picture is, nothing at all where it's clear.
                out.setBlendMode(SCNBlendModeReplace)
                out.setShaderModifiers(mapOf<Any?, Any?>(
                    SCNShaderModifierEntryPointSurface to "if (_surface.diffuse.a < " + m.alphaCutoff.coerceIn(0.01f, 0.99f) + ") { discard_fragment(); }"
                ))
            }
            // Solid: the picture's own transparency (some have junk in it) is ignored.
            else -> out.setBlendMode(SCNBlendModeReplace)
        }
        return out
    }
}
