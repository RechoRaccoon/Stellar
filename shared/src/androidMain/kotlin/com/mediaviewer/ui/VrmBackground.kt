// check:jvm
package com.mediaviewer.ui

import android.graphics.Bitmap
import android.util.Log
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Scene
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.google.android.filament.utils.ModelViewer
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A picture or looping video behind the avatar (VRM Settings → Display,
 *  supporters). [path] is a file in the app's own storage. */
data class VrmBackgroundMedia(val path: String, val isVideo: Boolean)

/**
 * The picture/video background, drawn by Filament itself: one flat, unlit
 * rectangle far behind the avatar, facing the camera and sized to cover the
 * whole view, textured with the picture (or the video's current frame).
 * Because it's part of the 3D scene, it's in everything Filament renders —
 * the screen, photos, recordings and the live stream — with no extra work.
 *
 * It's a tiny glTF model made here in memory and loaded with gltfio, the
 * same loader the avatar goes through, so it uses Filament's own stock
 * "unlit" material (lights and Full Bright don't touch it). Main thread
 * only, like everything else that talks to the engine.
 */
internal class VrmBackgroundQuad private constructor(
    private val engine: Engine,
    private val scene: Scene,
    private val materials: UbershaderProvider,
    private val assetLoader: AssetLoader,
    private val resourceLoader: ResourceLoader,
    private val asset: FilamentAsset
) {
    private var texture: Texture? = null
    private var mediaAspect = 1f
    private val sampler = TextureSampler(
        TextureSampler.MinFilter.LINEAR, TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.CLAMP_TO_EDGE
    )
    /** Upload buffers, taken in turn: Filament reads a buffer a moment
     *  after it's handed over, so the one just used isn't reused at once. */
    private val buffers = arrayOfNulls<ByteBuffer>(3)
    private var nextBuffer = 0
    private var destroyed = false

    /** Shows [bitmap] (ARGB_8888). Cheap to call per video frame: the
     *  texture is only rebuilt when the size changes. */
    fun show(bitmap: Bitmap) {
        if (destroyed || bitmap.isRecycled || bitmap.config != Bitmap.Config.ARGB_8888) return
        val w = bitmap.width
        val h = bitmap.height
        if (w !in 1..4096 || h !in 1..4096) return
        try {
            var tex = texture
            if (tex == null || tex.getWidth(0) != w || tex.getHeight(0) != h) {
                val fresh = Texture.Builder()
                    .width(w).height(h).levels(1)
                    .format(Texture.InternalFormat.SRGB8_A8)
                    .sampler(Texture.Sampler.SAMPLER_2D)
                    .build(engine)
                bind(fresh)
                // (Bound first, so the old one is never drawn after it's gone.)
                tex?.let { engine.destroyTexture(it) }
                texture = fresh
                tex = fresh
                mediaAspect = w.toFloat() / h
                for (i in buffers.indices) buffers[i] = null
            }
            val needed = w * h * 4
            var buffer = buffers[nextBuffer]
            if (buffer == null || buffer.capacity() != needed) {
                buffer = ByteBuffer.allocateDirect(needed).order(ByteOrder.nativeOrder())
                buffers[nextBuffer] = buffer
            }
            nextBuffer = (nextBuffer + 1) % buffers.size
            buffer!!.clear()
            bitmap.copyPixelsToBuffer(buffer)
            buffer.flip()
            // Exactly the texture's own size, one level: the same upload the
            // avatar's own textures use.
            tex!!.setImage(engine, 0, Texture.PixelBufferDescriptor(buffer, Texture.Format.RGBA, Texture.Type.UBYTE))
        } catch (e: Exception) {
            Log.e(TAG, "Updating the background failed", e)
        }
    }

    private fun bind(tex: Texture) {
        val rm = engine.renderableManager
        for (entity in asset.renderableEntities) {
            val ri = rm.getInstance(entity)
            if (ri == 0) continue
            for (p in 0 until rm.getPrimitiveCount(ri)) {
                rm.getMaterialInstanceAt(ri, p).setParameter("baseColorMap", tex, sampler)
            }
        }
    }

    /**
     * Keeps the rectangle straight in front of the camera, far behind the
     * avatar, and big enough to fill the view — including a recording or a
     * stream that's wider than the screen ([widestAspect]). The picture is
     * never stretched: it's scaled up until it covers, like a wallpaper.
     */
    fun place(viewer: ModelViewer, widestAspect: Float) {
        if (destroyed) return
        try {
            val proj = viewer.camera.getProjectionMatrix(DoubleArray(16))
            val cot = proj[5].toFloat()
            if (cot <= 0f) return
            val halfHeight = DISTANCE / cot
            val needH = halfHeight * 2f * 1.02f
            val needW = needH * widestAspect.coerceIn(0.2f, 4f)
            val a = mediaAspect.coerceIn(0.05f, 20f)
            val (w, h) = if (a >= needW / needH) (needH * a) to needH else needW to (needW / a)
            val m = viewer.camera.getModelMatrix(null as FloatArray?)
            val t = FloatArray(16)
            // camera × translate(0, 0, −distance) × scale(w, h, 1), column-major.
            t[0] = m[0] * w; t[1] = m[1] * w; t[2] = m[2] * w; t[3] = 0f
            t[4] = m[4] * h; t[5] = m[5] * h; t[6] = m[6] * h; t[7] = 0f
            t[8] = m[8]; t[9] = m[9]; t[10] = m[10]; t[11] = 0f
            t[12] = m[12] - m[8] * DISTANCE; t[13] = m[13] - m[9] * DISTANCE; t[14] = m[14] - m[10] * DISTANCE; t[15] = 1f
            val tm = engine.transformManager
            val inst = tm.getInstance(asset.root)
            if (inst != 0) tm.setTransform(inst, t)
        } catch (e: Exception) {
            Log.e(TAG, "Placing the background failed", e)
        }
    }

    /** Before the engine goes (or when the background is switched off). */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        runCatching { scene.removeEntities(asset.entities) }
        runCatching { assetLoader.destroyAsset(asset) }
        runCatching { texture?.let { engine.destroyTexture(it) } }
        texture = null
        runCatching { resourceLoader.destroy() }
        runCatching { assetLoader.destroy() }
        runCatching { materials.destroyMaterials() }
        runCatching { materials.destroy() }
    }

    companion object {
        private const val TAG = "VrmBackground"
        /** How far behind the camera's eye the rectangle sits (the avatar
         *  is a few units away; the far plane is at 1000). */
        private const val DISTANCE = 200f

        /** Builds the rectangle and adds it to the scene. Null if anything
         *  about that fails (the plain color background then simply stays). */
        fun create(viewer: ModelViewer): VrmBackgroundQuad? {
            val engine = viewer.engine
            var materials: UbershaderProvider? = null
            var loader: AssetLoader? = null
            var resources: ResourceLoader? = null
            return try {
                val glb = buildQuadGlb()
                val m = UbershaderProvider(engine).also { materials = it }
                val l = AssetLoader(engine, m, EntityManager.get()).also { loader = it }
                val r = ResourceLoader(engine).also { resources = it }
                val asset = l.createAsset(glb) ?: error("createAsset returned null")
                r.loadResources(asset)
                asset.releaseSourceData()
                val rm = engine.renderableManager
                for (entity in asset.renderableEntities) {
                    val ri = rm.getInstance(entity)
                    if (ri == 0) continue
                    // It's scenery, not part of the lit scene.
                    rm.setCastShadows(ri, false)
                    rm.setReceiveShadows(ri, false)
                    rm.setCulling(ri, false)
                }
                viewer.scene.addEntities(asset.entities)
                VrmBackgroundQuad(engine, viewer.scene, m, l, r, asset)
            } catch (e: Throwable) {
                Log.e(TAG, "Couldn't set up the picture background", e)
                runCatching { resources?.destroy() }
                runCatching { loader?.destroy() }
                runCatching { materials?.destroyMaterials() }
                runCatching { materials?.destroy() }
                null
            }
        }

        /**
         * A one-rectangle binary glTF: 1×1, centred, facing +Z, textured
         * (with a one-pixel placeholder until [show] is called) and marked
         * unlit.
         */
        private fun buildQuadGlb(): ByteBuffer {
            val png = ByteArrayOutputStream().also { out ->
                val one = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                one.eraseColor(android.graphics.Color.BLACK)
                one.compress(Bitmap.CompressFormat.PNG, 100, out)
                one.recycle()
            }.toByteArray()
            val binLength = 140 + png.size
            val binPadded = (binLength + 3) / 4 * 4
            val bin = ByteBuffer.allocate(binPadded).order(ByteOrder.LITTLE_ENDIAN)
            // Positions (bottom-left, bottom-right, top-right, top-left).
            floatArrayOf(-0.5f, -0.5f, 0f, 0.5f, -0.5f, 0f, 0.5f, 0.5f, 0f, -0.5f, 0.5f, 0f).forEach { bin.putFloat(it) }
            // Normals.
            repeat(4) { bin.putFloat(0f); bin.putFloat(0f); bin.putFloat(1f) }
            // Texture coordinates (glTF's start at the picture's top-left).
            floatArrayOf(0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f).forEach { bin.putFloat(it) }
            // Two triangles.
            shortArrayOf(0, 1, 2, 0, 2, 3).forEach { bin.putShort(it) }
            bin.put(png)
            while (bin.position() < binPadded) bin.put(0)

            var json = """{"asset":{"version":"2.0"},"extensionsUsed":["KHR_materials_unlit"],"scene":0,"scenes":[{"nodes":[0]}],""" +
                """"nodes":[{"mesh":0,"name":"stellar-background"}],""" +
                """"meshes":[{"primitives":[{"attributes":{"POSITION":0,"NORMAL":1,"TEXCOORD_0":2},"indices":3,"material":0}]}],""" +
                """"materials":[{"name":"stellar-background","doubleSided":true,"pbrMetallicRoughness":{"baseColorTexture":{"index":0},"metallicFactor":0,"roughnessFactor":1},"extensions":{"KHR_materials_unlit":{}}}],""" +
                """"textures":[{"source":0,"sampler":0}],"samplers":[{"magFilter":9729,"minFilter":9729,"wrapS":33071,"wrapT":33071}],""" +
                """"images":[{"bufferView":4,"mimeType":"image/png"}],""" +
                """"accessors":[{"bufferView":0,"componentType":5126,"count":4,"type":"VEC3","min":[-0.5,-0.5,0],"max":[0.5,0.5,0]},""" +
                """{"bufferView":1,"componentType":5126,"count":4,"type":"VEC3"},{"bufferView":2,"componentType":5126,"count":4,"type":"VEC2"},""" +
                """{"bufferView":3,"componentType":5123,"count":6,"type":"SCALAR"}],""" +
                """"bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":48,"target":34962},{"buffer":0,"byteOffset":48,"byteLength":48,"target":34962},""" +
                """{"buffer":0,"byteOffset":96,"byteLength":32,"target":34962},{"buffer":0,"byteOffset":128,"byteLength":12,"target":34963},""" +
                """{"buffer":0,"byteOffset":140,"byteLength":${png.size}}],"buffers":[{"byteLength":$binLength}]}"""
            while (json.length % 4 != 0) json += " "
            val jsonBytes = json.toByteArray(Charsets.US_ASCII)
            val total = 12 + 8 + jsonBytes.size + 8 + binPadded
            // (A direct buffer in native order's memory, read as little-endian.)
            val glb = ByteBuffer.allocateDirect(total).order(ByteOrder.LITTLE_ENDIAN)
            glb.putInt(0x46546C67).putInt(2).putInt(total)
            glb.putInt(jsonBytes.size).putInt(0x4E4F534A).put(jsonBytes)
            glb.putInt(binPadded).putInt(0x004E4942).put(bin.array(), 0, binPadded)
            glb.flip()
            return glb
        }
    }
}
