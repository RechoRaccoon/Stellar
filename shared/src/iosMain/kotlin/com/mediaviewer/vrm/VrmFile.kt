// iOS counterpart of Android's util/VrmFile.kt: the same parsing of a .vrm
// file's VRM block (bones, expressions, unnamed "orphan" morph targets),
// written against kotlinx.serialization's JSON tree instead of Gson. It also
// keeps the whole glTF document and the binary chunk, because on iOS the
// model itself is built from them too (VrmScene.kt) — Android leaves that
// to Filament's loader.
package com.mediaviewer.vrm

import com.mediaviewer.platform.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull

/** One glTF node's contribution to one expression: which mesh's morph
 *  target to drive, and how far driving it all the way should move it.
 *  [nodeIndex] is a glTF node index. */
data class MorphTargetBind(val nodeIndex: Int, val morphTargetIndex: Int, val weight: Float)

enum class VrmSpecVersion { VRM_0, VRM_1 }

/** See Android's VrmData: bone name → node, expression name → binds (names
 *  are not normalised between VRM 0.x and 1.0), node names, and morph
 *  targets no expression uses (keyed by their own name). */
data class VrmData(
    val specVersion: VrmSpecVersion,
    val humanBones: Map<String, Int>,
    val expressions: Map<String, List<MorphTargetBind>>,
    val nodeNames: Map<Int, String>,
    val orphanMorphTargets: Map<String, List<MorphTargetBind>> = emptyMap()
)

/** A .vrm / .glb file taken apart: its JSON document and where the binary
 *  chunk (meshes, textures) sits inside [bytes]. */
class Glb(val json: JsonObject, val bytes: ByteArray, val binStart: Int, val binLength: Int)

/** Reads the glTF 2.0 binary container: a 12-byte header, then chunks of
 *  [length][type][data], all little-endian. */
object GlbReader {
    private const val TAG = "GlbReader"
    private const val GLB_MAGIC = 0x46546C67      // "glTF"
    private const val CHUNK_JSON = 0x4E4F534A     // "JSON"
    private const val CHUNK_BIN = 0x004E4942      // "BIN\0"

    fun int32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

    fun read(bytes: ByteArray): Glb? = runCatching {
        require(bytes.size >= 12) { "file too short for a GLB header" }
        require(int32(bytes, 0) == GLB_MAGIC) { "not a GLB file (bad magic)" }
        var pos = 12
        var json: JsonObject? = null
        var binStart = 0
        var binLength = 0
        while (pos + 8 <= bytes.size) {
            val length = int32(bytes, pos)
            val type = int32(bytes, pos + 4)
            pos += 8
            require(length >= 0 && pos + length <= bytes.size) { "chunk length runs past end of file" }
            if (type == CHUNK_JSON && json == null) {
                json = Json.parseToJsonElement(bytes.decodeToString(pos, pos + length)) as? JsonObject
            } else if (type == CHUNK_BIN && binLength == 0) {
                binStart = pos
                binLength = length
            }
            pos += length
        }
        Glb(json ?: error("no JSON chunk"), bytes, binStart, binLength)
    }.onFailure { Log.e(TAG, "Could not read GLB container", it) }.getOrNull()
}

/** Parses the VRM-specific extension block out of a glTF JSON document. */
object VrmParser {
    private const val TAG = "VrmParser"

    /** Null (logged) when the document has neither a VRMC_vrm (1.0) nor a
     *  VRM (0.x) extension — a plain glTF, not an avatar. */
    fun parse(root: JsonObject): VrmData? = runCatching {
        val nodeNames = mutableMapOf<Int, String>()
        root.at("nodes").arr?.forEachIndexed { nodeIndex, node ->
            node.obj?.at("name").str?.let { nodeNames[nodeIndex] = it }
        }
        val extensions = root.at("extensions").obj ?: return@runCatching null
        val vrmData = when {
            extensions["VRMC_vrm"].obj != null -> parseVrm1(extensions["VRMC_vrm"].obj!!, nodeNames)
            extensions["VRM"].obj != null -> parseVrm0(root, extensions["VRM"].obj!!, nodeNames)
            else -> return@runCatching null
        }
        vrmData.copy(orphanMorphTargets = extractOrphanMorphTargets(root, vrmData))
    }.onFailure { Log.e(TAG, "Could not parse VRM extension block", it) }.getOrNull()

    // ---- VRM 1.0 (VRMC_vrm) ----

    private fun parseVrm1(vrmc: JsonObject, nodeNames: Map<Int, String>): VrmData {
        val humanBones = mutableMapOf<String, Int>()
        vrmc.at("humanoid.humanBones").obj?.forEach { (boneName, boneValue) ->
            boneValue.obj?.at("node").int?.let { humanBones[boneName] = it }
        }
        val expressions = mutableMapOf<String, List<MorphTargetBind>>()
        val expressionsRoot = vrmc.at("expressions").obj
        // "preset" (spec-defined names) and "custom" (the author's own).
        listOf("preset", "custom").forEach { bucket ->
            expressionsRoot?.at(bucket).obj?.forEach { (expressionName, expressionValue) ->
                val binds = expressionValue.obj?.at("morphTargetBinds").arr?.mapNotNull { b ->
                    val o = b.obj ?: return@mapNotNull null
                    val node = o.at("node").int ?: return@mapNotNull null
                    val index = o.at("index").int ?: return@mapNotNull null
                    MorphTargetBind(node, index, o.at("weight").float ?: 1.0f)
                } ?: emptyList()
                if (binds.isNotEmpty()) expressions[expressionName] = binds
            }
        }
        return VrmData(VrmSpecVersion.VRM_1, humanBones, expressions, nodeNames)
    }

    // ---- VRM 0.x (VRM) ----

    /** glTF mesh index → the node that shows it (an avatar never shows one
     *  mesh on two nodes). VRM 0.x binds name the mesh, VRM 1.0 the node. */
    fun meshIndexToNodeIndex(root: JsonObject): Map<Int, Int> {
        val out = mutableMapOf<Int, Int>()
        root.at("nodes").arr?.forEachIndexed { nodeIndex, node ->
            node.obj?.at("mesh").int?.let { out[it] = nodeIndex }
        }
        return out
    }

    private fun parseVrm0(root: JsonObject, vrm: JsonObject, nodeNames: Map<Int, String>): VrmData {
        val humanBones = mutableMapOf<String, Int>()
        vrm.at("humanoid.humanBones").arr?.forEach { entry ->
            val boneObject = entry.obj ?: return@forEach
            val boneName = boneObject.at("bone").str ?: return@forEach
            val node = boneObject.at("node").int ?: return@forEach
            humanBones[boneName] = node
        }
        val meshToNode = meshIndexToNodeIndex(root)
        val expressions = mutableMapOf<String, List<MorphTargetBind>>()
        vrm.at("blendShapeMaster.blendShapeGroups").arr?.forEach { entry ->
            val group = entry.obj ?: return@forEach
            // The spec's presetName ("joy", "a", "blink", …) when there is
            // one, else the group's own free-text name.
            val presetName = group.at("presetName").str?.takeIf { it.isNotBlank() && it != "unknown" }
            val expressionName = presetName ?: group.at("name").str ?: return@forEach
            val binds = group.at("binds").arr?.mapNotNull { b ->
                val o = b.obj ?: return@mapNotNull null
                val meshIndex = o.at("mesh").int ?: return@mapNotNull null
                val nodeIndex = meshToNode[meshIndex] ?: return@mapNotNull null
                val index = o.at("index").int ?: return@mapNotNull null
                // VRM 0.x weights are percentages.
                MorphTargetBind(nodeIndex, index, (o.at("weight").float ?: 100.0f) / 100.0f)
            } ?: emptyList()
            if (binds.isNotEmpty()) expressions[expressionName] = binds
        }
        return VrmData(VrmSpecVersion.VRM_0, humanBones, expressions, nodeNames)
    }

    /** Morph targets in the meshes that no VRM expression refers to, named
     *  from the primitive's extras.targetNames (how "ARKit-ready" models
     *  carry the 52 ARKit shapes). First primitive with targets per mesh. */
    private fun extractOrphanMorphTargets(root: JsonObject, vrmData: VrmData): Map<String, List<MorphTargetBind>> {
        val meshToNode = meshIndexToNodeIndex(root)
        val referenced = vrmData.expressions.values.flatten().map { it.nodeIndex to it.morphTargetIndex }.toSet()
        val orphans = mutableMapOf<String, MutableList<MorphTargetBind>>()
        root.at("meshes").arr?.forEachIndexed { meshIndex, mesh ->
            val nodeIndex = meshToNode[meshIndex] ?: return@forEachIndexed
            val primitive = mesh.obj?.at("primitives").arr?.mapNotNull { it.obj }
                ?.firstOrNull { (it.at("targets").arr?.size ?: 0) > 0 } ?: return@forEachIndexed
            // (Some exporters put the names on the mesh instead.)
            val targetNames = (primitive.at("extras.targetNames").arr ?: mesh.obj?.at("extras.targetNames").arr)
                ?.mapNotNull { it.str } ?: return@forEachIndexed
            targetNames.forEachIndexed { targetIndex, name ->
                if (name.isBlank()) return@forEachIndexed
                if ((nodeIndex to targetIndex) in referenced) return@forEachIndexed
                orphans.getOrPut(name) { mutableListOf() }.add(MorphTargetBind(nodeIndex, targetIndex, 1.0f))
            }
        }
        return orphans
    }
}

// ---- Small JSON helpers: a missing or wrong-typed field is null, never an
// exception, so one absent field costs one bone/expression, not the file.

/** Dotted-path lookup, e.g. at("humanoid.humanBones"). */
internal fun JsonObject.at(path: String): JsonElement? {
    var current: JsonElement = this
    for (segment in path.split(".")) {
        val o = current as? JsonObject ?: return null
        current = o[segment] ?: return null
        if (current is JsonNull) return null
    }
    return current
}

internal val JsonElement?.obj: JsonObject? get() = this as? JsonObject
internal val JsonElement?.arr: JsonArray? get() = this as? JsonArray
internal val JsonElement?.int: Int?
    get() = (this as? JsonPrimitive)?.let { p -> p.intOrNull ?: p.floatOrNull?.toInt() }
internal val JsonElement?.float: Float? get() = (this as? JsonPrimitive)?.floatOrNull
internal val JsonElement?.str: String? get() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
internal val JsonElement?.bool: Boolean? get() = (this as? JsonPrimitive)?.booleanOrNull
