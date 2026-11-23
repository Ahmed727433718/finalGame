package com.cubeforge.stoneage.render

import android.content.Context
import android.opengl.Matrix
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Single sub-mesh extracted from a glTF 2.0 binary (.glb) asset, preserving node name,
 * local pivot, baseColorFactor, emissive strength, and baked COLOR_0 vertex colors!
 */
class GlbMeshPart(
    val nodeName: String,
    val mesh: SimpleMesh,
    val r: Float,
    val g: Float,
    val b: Float,
    val emissiveBoost: Float,
    val pivotX: Float,
    val pivotY: Float,
    val pivotZ: Float,
    val isAnimatedPart: Boolean
)

class GlbModel(
    val parts: Array<GlbMeshPart>
)

/**
 * Production-grade zero-dependency glTF 2.0 Binary (.glb) loader built specifically for our
 * Blender 4.x/5.x stylized Stone Age scripts:
 *  - Traverses full parent-child node hierarchy (scenes -> nodes -> children)
 *  - Automatically SKIPS invisible collision/helper proxies (`COL_*`, `TRIGGER_*`, `INTERACT_*`)
 *  - Extracts `POSITION`, `NORMAL`, `COLOR_0` (FLOAT / UNSIGNED_SHORT / UNSIGNED_BYTE), and `indices`
 *  - Reads `pbrMetallicRoughness.baseColorFactor` and `emissiveFactor`
 *  - Tags animated nodes (`Anim_*`, `AnimatedPart`, `Arm*`, `Claw*`, `Binder_*`, `ClayJaw_*`)
 *  - Automatically tries `<modelId>_lod1.glb` if `<modelId>.glb` is absent (or vice versa)
 */
object GlbLoader {

    private fun isSkippedHelperNode(name: String): Boolean {
        return name.startsWith("COL_", ignoreCase = true) ||
            name.startsWith("TRIGGER_", ignoreCase = true) ||
            name.startsWith("INTERACT_", ignoreCase = true)
    }

    private fun isAnimatedNodeName(name: String): Boolean {
        return name.contains("AnimatedPart", ignoreCase = true) ||
            name.startsWith("Anim_", ignoreCase = true) ||
            name.startsWith("Arm", ignoreCase = true) ||
            name.startsWith("Claw", ignoreCase = true) ||
            name.startsWith("Binder_", ignoreCase = true) ||
            name.startsWith("ClayJaw_", ignoreCase = true)
    }

    fun loadSafe(context: Context, assetPath: String): GlbModel? {
        return try {
            val bytes = context.assets.open(assetPath).use { it.readBytes() }
            parseGlb(bytes)
        } catch (_: Exception) {
            null
        }
    }

    fun loadMachineModel(context: Context, modelId: String): GlbModel? {
        // Prefer LOD1 on mobile if available for ultra-fast 60 FPS, fallback to full LOD0
        return loadSafe(context, "models/machines/${modelId}_lod1.glb")
            ?: loadSafe(context, "models/machines/${modelId}.glb")
            ?: loadSafe(context, "models/archetypes/${modelId}.glb")
    }

    fun parseGlb(bytes: ByteArray): GlbModel? {
        if (bytes.size < 20) return null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bb.int != 0x46546C67) return null // "glTF"
        if (bb.int != 2) return null
        bb.int // total length

        var jsonText: String? = null
        var binOffset = -1
        var binLength = 0

        while (bb.remaining() >= 8) {
            val chunkLen = bb.int
            val chunkType = bb.int
            if (chunkLen < 0 || chunkLen > bb.remaining()) break
            when (chunkType) {
                0x4E4F534A -> { // JSON
                    val jsonBytes = ByteArray(chunkLen)
                    bb.get(jsonBytes)
                    jsonText = String(jsonBytes, Charsets.UTF_8)
                }
                0x004E4942 -> { // BIN
                    binOffset = bb.position()
                    binLength = chunkLen
                    bb.position(bb.position() + chunkLen)
                }
                else -> bb.position(bb.position() + chunkLen)
            }
        }

        if (jsonText == null || binOffset < 0 || binLength <= 0) return null
        val root = JSONObject(jsonText)
        val meshes = root.optJSONArray("meshes") ?: return null
        val accessors = root.optJSONArray("accessors") ?: return null
        val bufferViews = root.optJSONArray("bufferViews") ?: return null
        val materials = root.optJSONArray("materials")
        val nodes = root.optJSONArray("nodes")

        val parts = ArrayList<GlbMeshPart>()
        val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

        if (nodes != null && nodes.length() > 0) {
            // Find root nodes (nodes not referenced as a child of any other node)
            val isChild = BooleanArray(nodes.length())
            for (i in 0 until nodes.length()) {
                val children = nodes.getJSONObject(i).optJSONArray("children") ?: continue
                for (c in 0 until children.length()) {
                    val idx = children.getInt(c)
                    if (idx in isChild.indices) isChild[idx] = true
                }
            }
            for (i in 0 until nodes.length()) {
                if (!isChild[i]) {
                    traverseNode(
                        i, nodes, identity, false,
                        meshes, accessors, bufferViews, materials,
                        bytes, binOffset, parts
                    )
                }
            }
        } else {
            for (m in 0 until meshes.length()) {
                val meshObj = meshes.getJSONObject(m)
                val name = meshObj.optString("name", "")
                if (isSkippedHelperNode(name)) continue
                extractMeshParts(
                    name, meshObj, accessors, bufferViews, materials,
                    bytes, binOffset, identity, isAnimatedNodeName(name), parts
                )
            }
        }

        return if (parts.isEmpty()) null else GlbModel(parts.toTypedArray())
    }

    private fun traverseNode(
        nodeIdx: Int,
        nodes: JSONArray,
        parentMat: FloatArray,
        parentAnimated: Boolean,
        meshes: JSONArray,
        accessors: JSONArray,
        bufferViews: JSONArray,
        materials: JSONArray?,
        binBytes: ByteArray,
        binOffset: Int,
        outParts: MutableList<GlbMeshPart>
    ) {
        val node = nodes.getJSONObject(nodeIdx)
        val name = node.optString("name", "")
        if (isSkippedHelperNode(name)) return

        val localMat = computeNodeMatrix(node)
        val worldMat = FloatArray(16)
        Matrix.multiplyMM(worldMat, 0, parentMat, 0, localMat, 0)

        val isAnim = parentAnimated || isAnimatedNodeName(name)
        if (node.has("mesh")) {
            val meshIdx = node.getInt("mesh")
            extractMeshParts(
                name, meshes.getJSONObject(meshIdx),
                accessors, bufferViews, materials,
                binBytes, binOffset, worldMat, isAnim, outParts
            )
        }

        val children = node.optJSONArray("children") ?: return
        for (c in 0 until children.length()) {
            traverseNode(
                children.getInt(c), nodes, worldMat, isAnim,
                meshes, accessors, bufferViews, materials,
                binBytes, binOffset, outParts
            )
        }
    }

    private fun computeNodeMatrix(node: JSONObject): FloatArray {
        val mat = FloatArray(16)
        val mArr = node.optJSONArray("matrix")
        if (mArr != null && mArr.length() == 16) {
            for (i in 0 until 16) mat[i] = mArr.getDouble(i).toFloat()
            return mat
        }
        Matrix.setIdentityM(mat, 0)
        val tArr = node.optJSONArray("translation")
        if (tArr != null && tArr.length() == 3) {
            Matrix.translateM(mat, 0, tArr.getDouble(0).toFloat(), tArr.getDouble(1).toFloat(), tArr.getDouble(2).toFloat())
        }
        val rArr = node.optJSONArray("rotation")
        if (rArr != null && rArr.length() == 4) {
            val rot = quatToMatrix(
                rArr.getDouble(0).toFloat(),
                rArr.getDouble(1).toFloat(),
                rArr.getDouble(2).toFloat(),
                rArr.getDouble(3).toFloat()
            )
            val tmp = FloatArray(16)
            Matrix.multiplyMM(tmp, 0, mat, 0, rot, 0)
            System.arraycopy(tmp, 0, mat, 0, 16)
        }
        val sArr = node.optJSONArray("scale")
        if (sArr != null && sArr.length() == 3) {
            Matrix.scaleM(mat, 0, sArr.getDouble(0).toFloat(), sArr.getDouble(1).toFloat(), sArr.getDouble(2).toFloat())
        }
        return mat
    }

    private fun quatToMatrix(x: Float, y: Float, z: Float, w: Float): FloatArray {
        val m = FloatArray(16)
        val xx = x * x; val yy = y * y; val zz = z * z
        val xy = x * y; val xz = x * z; val yz = y * z
        val wx = w * x; val wy = w * y; val wz = w * z
        m[0] = 1f - 2f * (yy + zz); m[1] = 2f * (xy + wz); m[2] = 2f * (xz - wy); m[3] = 0f
        m[4] = 2f * (xy - wz); m[5] = 1f - 2f * (xx + zz); m[6] = 2f * (yz + wx); m[7] = 0f
        m[8] = 2f * (xz + wy); m[9] = 2f * (yz - wx); m[10] = 1f - 2f * (xx + yy); m[11] = 0f
        m[12] = 0f; m[13] = 0f; m[14] = 0f; m[15] = 1f
        return m
    }

    private fun extractMeshParts(
        nodeName: String,
        meshObj: JSONObject,
        accessors: JSONArray,
        bufferViews: JSONArray,
        materials: JSONArray?,
        binBytes: ByteArray,
        binOffset: Int,
        worldMat: FloatArray,
        isAnim: Boolean,
        outParts: MutableList<GlbMeshPart>
    ) {
        val primitives = meshObj.optJSONArray("primitives") ?: return
        val pivX = worldMat[12]
        val pivY = worldMat[13]
        val pivZ = worldMat[14]

        for (p in 0 until primitives.length()) {
            val prim = primitives.getJSONObject(p)
            val attrs = prim.optJSONObject("attributes") ?: continue
            if (!attrs.has("POSITION")) continue

            val positions = readVec3Accessor(accessors.getJSONObject(attrs.getInt("POSITION")), bufferViews, binBytes, binOffset)
                ?: continue
            val normals = if (attrs.has("NORMAL")) {
                readVec3Accessor(accessors.getJSONObject(attrs.getInt("NORMAL")), bufferViews, binBytes, binOffset)
            } else null
            val colors = if (attrs.has("COLOR_0")) {
                readColorAccessor(accessors.getJSONObject(attrs.getInt("COLOR_0")), bufferViews, binBytes, binOffset)
            } else null

            val indices = if (prim.has("indices")) {
                readIndexAccessor(accessors.getJSONObject(prim.getInt("indices")), bufferViews, binBytes, binOffset)
            } else {
                IntArray(positions.size / 3) { it }
            } ?: continue

            val vertCount = indices.size
            val interleaved = FloatArray(vertCount * 9)
            val inPos = FloatArray(4)
            val outPos = FloatArray(4)
            val inNorm = FloatArray(4)
            val outNorm = FloatArray(4)

            for (i in 0 until vertCount) {
                val vi = indices[i]
                val pBase = vi * 3
                if (pBase + 2 >= positions.size) continue

                inPos[0] = positions[pBase]
                inPos[1] = positions[pBase + 1]
                inPos[2] = positions[pBase + 2]
                inPos[3] = 1f
                Matrix.multiplyMV(outPos, 0, worldMat, 0, inPos, 0)

                if (normals != null && pBase + 2 < normals.size) {
                    inNorm[0] = normals[pBase]
                    inNorm[1] = normals[pBase + 1]
                    inNorm[2] = normals[pBase + 2]
                    inNorm[3] = 0f
                    Matrix.multiplyMV(outNorm, 0, worldMat, 0, inNorm, 0)
                    val len = sqrt(outNorm[0] * outNorm[0] + outNorm[1] * outNorm[1] + outNorm[2] * outNorm[2]).coerceAtLeast(1e-5f)
                    outNorm[0] /= len; outNorm[1] /= len; outNorm[2] /= len
                } else {
                    outNorm[0] = 0f; outNorm[1] = 1f; outNorm[2] = 0f
                }

                val cr = if (colors != null && pBase + 2 < colors.size) colors[pBase] else 1f
                val cg = if (colors != null && pBase + 2 < colors.size) colors[pBase + 1] else 1f
                val cb = if (colors != null && pBase + 2 < colors.size) colors[pBase + 2] else 1f

                val dst = i * 9
                interleaved[dst] = outPos[0]
                interleaved[dst + 1] = outPos[1]
                interleaved[dst + 2] = outPos[2]
                interleaved[dst + 3] = outNorm[0]
                interleaved[dst + 4] = outNorm[1]
                interleaved[dst + 5] = outNorm[2]
                interleaved[dst + 6] = cr
                interleaved[dst + 7] = cg
                interleaved[dst + 8] = cb
            }

            var r = 0.88f; var g = 0.84f; var b = 0.78f
            var emissiveBoost = 0f
            if (materials != null && prim.has("material")) {
                val matObj = materials.optJSONObject(prim.getInt("material"))
                val pbr = matObj?.optJSONObject("pbrMetallicRoughness")
                val baseColor = pbr?.optJSONArray("baseColorFactor")
                if (baseColor != null && baseColor.length() >= 3) {
                    r = linearToSrgbApprox(baseColor.getDouble(0).toFloat().coerceIn(0f, 1f))
                    g = linearToSrgbApprox(baseColor.getDouble(1).toFloat().coerceIn(0f, 1f))
                    b = linearToSrgbApprox(baseColor.getDouble(2).toFloat().coerceIn(0f, 1f))
                }
                val emi = matObj?.optJSONArray("emissiveFactor")
                if (emi != null && emi.length() >= 3) {
                    val maxE = maxOf(emi.getDouble(0), emi.getDouble(1), emi.getDouble(2)).toFloat()
                    if (maxE > 0.05f) {
                        emissiveBoost = (maxE * 0.65f).coerceIn(0.15f, 0.75f)
                    }
                }
            }

            outParts.add(
                GlbMeshPart(
                    nodeName = nodeName,
                    mesh = SimpleMesh(interleaved),
                    r = r, g = g, b = b,
                    emissiveBoost = emissiveBoost,
                    pivotX = pivX, pivotY = pivY, pivotZ = pivZ,
                    isAnimatedPart = isAnim
                )
            )
        }
    }

    private fun linearToSrgbApprox(c: Float): Float {
        return Math.pow(c.coerceIn(0f, 1f).toDouble(), 1.0 / 2.2).toFloat()
    }

    private fun readVec3Accessor(
        acc: JSONObject,
        bufferViews: JSONArray,
        binBytes: ByteArray,
        binOffset: Int
    ): FloatArray? {
        val bvIdx = acc.optInt("bufferView", -1)
        if (bvIdx < 0) return null
        val bv = bufferViews.getJSONObject(bvIdx)
        val count = acc.optInt("count", 0)
        val byteOffset = binOffset + bv.optInt("byteOffset", 0) + acc.optInt("byteOffset", 0)
        val stride = bv.optInt("byteStride", 12).coerceAtLeast(12)
        if (byteOffset + (count - 1) * stride + 12 > binBytes.size) return null

        val out = FloatArray(count * 3)
        val bb = ByteBuffer.wrap(binBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            val pos = byteOffset + i * stride
            out[i * 3] = bb.getFloat(pos)
            out[i * 3 + 1] = bb.getFloat(pos + 4)
            out[i * 3 + 2] = bb.getFloat(pos + 8)
        }
        return out
    }

    private fun readColorAccessor(
        acc: JSONObject,
        bufferViews: JSONArray,
        binBytes: ByteArray,
        binOffset: Int
    ): FloatArray? {
        val bvIdx = acc.optInt("bufferView", -1)
        if (bvIdx < 0) return null
        val bv = bufferViews.getJSONObject(bvIdx)
        val count = acc.optInt("count", 0)
        val type = acc.optString("type", "VEC4")
        val comps = if (type == "VEC3") 3 else 4
        val compType = acc.optInt("componentType", 5126)
        val compBytes = when (compType) {
            5121 -> 1 // UNSIGNED_BYTE
            5123 -> 2 // UNSIGNED_SHORT
            5126 -> 4 // FLOAT
            else -> return null
        }
        val elemBytes = comps * compBytes
        val byteOffset = binOffset + bv.optInt("byteOffset", 0) + acc.optInt("byteOffset", 0)
        val stride = bv.optInt("byteStride", elemBytes).coerceAtLeast(elemBytes)
        if (byteOffset + (count - 1) * stride + elemBytes > binBytes.size) return null

        val out = FloatArray(count * 3)
        val bb = ByteBuffer.wrap(binBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            val pos = byteOffset + i * stride
            for (c in 0 until 3) {
                val raw = when (compType) {
                    5121 -> (binBytes[pos + c].toInt() and 0xFF) / 255f
                    5123 -> (bb.getShort(pos + c * 2).toInt() and 0xFFFF) / 65535f
                    else -> bb.getFloat(pos + c * 4)
                }
                out[i * 3 + c] = raw.coerceIn(0f, 1f)
            }
        }
        return out
    }

    private fun readIndexAccessor(
        acc: JSONObject,
        bufferViews: JSONArray,
        binBytes: ByteArray,
        binOffset: Int
    ): IntArray? {
        val bvIdx = acc.optInt("bufferView", -1)
        if (bvIdx < 0) return null
        val bv = bufferViews.getJSONObject(bvIdx)
        val count = acc.optInt("count", 0)
        val compType = acc.optInt("componentType", 5123)
        val byteOffset = binOffset + bv.optInt("byteOffset", 0) + acc.optInt("byteOffset", 0)
        val bb = ByteBuffer.wrap(binBytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = IntArray(count)
        return when (compType) {
            5123 -> {
                if (byteOffset + count * 2 > binBytes.size) return null
                for (i in 0 until count) out[i] = bb.getShort(byteOffset + i * 2).toInt() and 0xFFFF
                out
            }
            5125 -> {
                if (byteOffset + count * 4 > binBytes.size) return null
                for (i in 0 until count) out[i] = bb.getInt(byteOffset + i * 4)
                out
            }
            5121 -> {
                if (byteOffset + count > binBytes.size) return null
                for (i in 0 until count) out[i] = binBytes[byteOffset + i].toInt() and 0xFF
                out
            }
            else -> null
        }
    }
}

/**
 * Era-scoped GLB cache for shared logistics assets + per-machine GLB models.
 */
class LogisticsGlbCache {
    var workerCarrier: GlbModel? = null
        private set
    var workerCarrierHeavy: GlbModel? = null
        private set
    var managerOverseer: GlbModel? = null
        private set
    var warehouse: GlbModel? = null
        private set
    var crate: GlbModel? = null
        private set

    fun loadForEra(context: Context, eraId: String) {
        val base = "models/eras/$eraId"
        workerCarrier = GlbLoader.loadSafe(context, "$base/worker_carrier.glb")
        workerCarrierHeavy = GlbLoader.loadSafe(context, "$base/worker_carrier_heavy.glb")
        managerOverseer = GlbLoader.loadSafe(context, "$base/manager_overseer.glb")
        warehouse = GlbLoader.loadSafe(context, "$base/warehouse.glb")
        crate = GlbLoader.loadSafe(context, "$base/crate.glb")
            ?: GlbLoader.loadMachineModel(context, "collection_crate")
    }
}
