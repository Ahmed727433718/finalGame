package com.cubeforge.stoneage.render

import android.content.Context
import android.graphics.Color
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.cubeforge.stoneage.model.FactoryState
import com.cubeforge.stoneage.model.MachineArchetype
import com.cubeforge.stoneage.model.MachinePlacement
import com.cubeforge.stoneage.sim.GameEngine
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Restructured OpenGL ES 3.0 3D renderer for Stone Age Wood & Charcoal Factories + Shared Logistics:
 *  - Supports dynamic machine count per factory (4 machines: 1 FEEDER + 3 OVERHEAD/SIDE)
 *  - Loads real Blender .glb models from `assets/models/machines/<modelId>.glb` (or `_lod1.glb`)
 *    rendering baked Clash-of-Clans COLOR_0 vertex colors + animating Anim_* / AnimatedPart nodes!
 *  - Falls back cleanly to stylized procedural 3D meshes if any .glb is not yet copied into assets.
 *  - Strictly ZERO heap allocations inside onDrawFrame.
 */
class FactoryRenderer(
    private val context: Context,
    private val engine: GameEngine,
    val camera: ClashCamera
) : GLSurfaceView.Renderer {

    private var programId: Int = 0
    private var uMvpHandle: Int = -1
    private var uModelHandle: Int = -1
    private var uColorHandle: Int = -1
    private var uEmissiveHandle: Int = -1
    private var uAlphaHandle: Int = -1
    private var uCameraPosHandle: Int = -1
    private var aPosHandle: Int = 0
    private var aNormHandle: Int = 1
    private var aColorHandle: Int = 2

    private lateinit var boxMesh: SimpleMesh
    private lateinit var cylMesh: SimpleMesh
    private lateinit var pyrMesh: SimpleMesh
    private lateinit var shadowQuadMesh: SimpleMesh
    private lateinit var shadowDiscMesh: SimpleMesh

    private val logisticsRenderer = LogisticsRenderer(context, engine)
    private val machineGlbCache = HashMap<String, GlbModel>()
    private val crateGlbCache = HashMap<String, GlbModel>()

    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val upgradeFlashRemaining: FloatArray
    private val maxMachinesPerFactory: Int
    private val factoryAccentR: FloatArray
    private val factoryAccentG: FloatArray
    private val factoryAccentB: FloatArray
    private var lastFrameNanos: Long = 0L

    companion object {
        private const val UPGRADE_FLASH_DURATION_SEC = 0.35f

        private const val VERTEX_SHADER = """#version 300 es
            layout(location = 0) in vec3 aPosition;
            layout(location = 1) in vec3 aNormal;
            layout(location = 2) in vec3 aColor;
            uniform mat4 uMVP;
            uniform mat4 uModel;
            out vec3 vNormal;
            out vec3 vWorldPos;
            out vec3 vColor;
            void main() {
                vec4 worldPos = uModel * vec4(aPosition, 1.0);
                vWorldPos = worldPos.xyz;
                vNormal = normalize(mat3(uModel) * aNormal);
                vColor = aColor;
                gl_Position = uMVP * vec4(aPosition, 1.0);
            }
        """

        private const val FRAGMENT_SHADER = """#version 300 es
            precision mediump float;
            in vec3 vNormal;
            in vec3 vWorldPos;
            in vec3 vColor;
            uniform vec3 uColor;
            uniform float uEmissive;
            uniform float uAlpha;
            uniform vec3 uCameraPos;
            out vec4 fragColor;
            void main() {
                vec3 fogColor = vec3(0.77, 0.85, 0.88);
                vec3 toCam = uCameraPos - vWorldPos;
                float dist = length(toCam);
                float fogLinear = clamp((dist - 24.0) / 62.0, 0.0, 0.78);
                float fogFactor = fogLinear * fogLinear * (3.0 - 2.0 * fogLinear);

                if (uAlpha < 0.99) {
                    vec3 shadowCol = mix(uColor, fogColor, fogFactor * 0.65);
                    fragColor = vec4(shadowCol, uAlpha * (1.0 - fogFactor * 0.85));
                    return;
                }

                vec3 baseCol = uColor * vColor;
                vec3 N = normalize(vNormal);
                vec3 V = normalize(toCam);
                vec3 sunDir = normalize(vec3(0.42, 0.78, 0.46));

                float diff = max(dot(N, sunDir), 0.0);
                float hemi = 0.5 + 0.5 * N.y;
                vec3 ambient = mix(vec3(0.32, 0.26, 0.21), vec3(0.62, 0.72, 0.84), hemi);

                float fresnel = 1.0 - max(dot(N, V), 0.0);
                float rim = fresnel * fresnel * fresnel * (0.45 + 0.55 * hemi);

                vec3 warmSun = vec3(1.0, 0.95, 0.84);
                vec3 rimTint = vec3(1.0, 0.88, 0.66);

                vec3 lit = baseCol * (ambient * 0.72 + warmSun * diff * 0.68) + rimTint * rim * 0.22;
                vec3 emissiveTint = mix(baseCol, vec3(1.0, 0.93, 0.68), 0.45);
                vec3 finalCol = lit + emissiveTint * uEmissive;
                finalCol = mix(finalCol, fogColor, fogFactor);
                fragColor = vec4(finalCol, 1.0);
            }
        """
    }

    init {
        val count = engine.factories.size
        maxMachinesPerFactory = engine.factories.maxOfOrNull { it.machines.size }?.coerceAtLeast(1) ?: 4
        upgradeFlashRemaining = FloatArray(count * maxMachinesPerFactory)
        factoryAccentR = FloatArray(count)
        factoryAccentG = FloatArray(count)
        factoryAccentB = FloatArray(count)

        for (i in 0 until count) {
            val argb = try {
                Color.parseColor(engine.factories[i].def.colorHex)
            } catch (_: Exception) {
                Color.rgb(210, 120, 40)
            }
            factoryAccentR[i] = Color.red(argb) / 255f
            factoryAccentG[i] = Color.green(argb) / 255f
            factoryAccentB[i] = Color.blue(argb) / 255f
        }
    }

    fun triggerUpgradeFlash(factoryIndex: Int, machineIndex: Int) {
        val slot = factoryIndex * maxMachinesPerFactory + machineIndex
        if (slot in upgradeFlashRemaining.indices) {
            upgradeFlashRemaining[slot] = UPGRADE_FLASH_DURATION_SEC
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.77f, 0.85f, 0.88f, 1.0f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        programId = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        uMvpHandle = GLES30.glGetUniformLocation(programId, "uMVP")
        uModelHandle = GLES30.glGetUniformLocation(programId, "uModel")
        uColorHandle = GLES30.glGetUniformLocation(programId, "uColor")
        uEmissiveHandle = GLES30.glGetUniformLocation(programId, "uEmissive")
        uAlphaHandle = GLES30.glGetUniformLocation(programId, "uAlpha")
        uCameraPosHandle = GLES30.glGetUniformLocation(programId, "uCameraPos")
        aPosHandle = 0
        aNormHandle = 1
        aColorHandle = 2

        boxMesh = ProceduralMeshes.createUnitBox()
        cylMesh = ProceduralMeshes.createCylinder(12)
        pyrMesh = ProceduralMeshes.createPyramid()
        shadowQuadMesh = ProceduralMeshes.createShadowQuad()
        shadowDiscMesh = ProceduralMeshes.createShadowDisc(14)

        // Load Blender .glb models for every configured machine and factory collection crate
        machineGlbCache.clear()
        crateGlbCache.clear()
        for (fac in engine.factories) {
            val cId = fac.def.crateModelId
            if (!crateGlbCache.containsKey(cId)) {
                GlbLoader.loadMachineModel(context, cId)?.let { crateGlbCache[cId] = it }
            }
            for (m in fac.machines) {
                val mId = m.def.modelId
                if (!machineGlbCache.containsKey(mId)) {
                    GlbLoader.loadMachineModel(context, mId)?.let { machineGlbCache[mId] = it }
                }
            }
        }

        GlbLoader.loadMachineModel(context, "conveyor_straight")?.let { machineGlbCache["conveyor_straight"] = it }
        logisticsRenderer.onSurfaceCreated()
        lastFrameNanos = System.nanoTime()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        camera.setViewport(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.1f)
        lastFrameNanos = now

        engine.update(dt)
        camera.update(dt)

        for (i in upgradeFlashRemaining.indices) {
            if (upgradeFlashRemaining[i] > 0f) {
                val next = upgradeFlashRemaining[i] - dt
                upgradeFlashRemaining[i] = if (next > 0f) next else 0f
            }
        }

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glUseProgram(programId)
        if (uCameraPosHandle >= 0) {
            GLES30.glUniform3f(uCameraPosHandle, camera.eyeX, camera.eyeY, camera.eyeZ)
        }

        synchronized(engine.lock) {
            val t = engine.simTimeSec
            val overclockGlow = if (engine.overclockRemainingSec > 0f) 0.22f else 0f

            GLES30.glDisable(GLES30.GL_BLEND)
            GLES30.glDepthMask(true)
            drawTerrainBase()

            val f0 = engine.factories[0]
            drawTerrainContactShadows(f0)

            GLES30.glDisable(GLES30.GL_BLEND)
            GLES30.glDepthMask(true)
            drawTerrainProps()
            drawResourceNode(f0.worldX - 6.8f, f0.worldZ - 0.8f, t)

            for (i in engine.factories.indices) {
                val fac = engine.factories[i]
                val isSelected = (i == engine.selectedFactoryIndex)
                val readyToUnlock = !fac.unlocked && engine.canUnlockFactory(i)
                drawFactory(i, fac, isSelected, readyToUnlock, t, overclockGlow)
            }

            drawVisualBeltProducts()
            logisticsRenderer.drawOpaqueLogistics(this, t)

            if (engine.goldenAnomaly.active) {
                val fac = engine.factories[engine.goldenAnomaly.factoryIndex]
                val bob = sin(engine.goldenAnomaly.bobPhase) * 0.35f
                drawPrimitive(
                    mesh = pyrMesh,
                    x = fac.worldX,
                    y = 2.9f + bob,
                    z = fac.worldZ,
                    sx = 1.05f, sy = 1.25f, sz = 1.05f,
                    rotY = t * 140f,
                    r = 1.0f, g = 0.84f, b = 0.12f,
                    emissive = 0.48f
                )
            }
        }
    }

    // ==================== PACKAGE-INTERNAL HELPERS FOR LOGISTICS RENDERER ====================

    internal fun drawBox(
        x: Float, y: Float, z: Float,
        sx: Float, sy: Float, sz: Float,
        rotY: Float, r: Float, g: Float, b: Float, emissive: Float
    ) = drawPrimitive(boxMesh, x, y, z, sx, sy, sz, rotY, r, g, b, emissive)

    internal fun drawCylinder(
        x: Float, y: Float, z: Float,
        sx: Float, sy: Float, sz: Float,
        rotY: Float, r: Float, g: Float, b: Float, emissive: Float
    ) = drawPrimitive(cylMesh, x, y, z, sx, sy, sz, rotY, r, g, b, emissive)

    internal fun drawPyramid(
        x: Float, y: Float, z: Float,
        sx: Float, sy: Float, sz: Float,
        rotY: Float, r: Float, g: Float, b: Float, emissive: Float
    ) = drawPrimitive(pyrMesh, x, y, z, sx, sy, sz, rotY, r, g, b, emissive)

    internal fun drawCustomMesh(
        mesh: SimpleMesh,
        x: Float, y: Float, z: Float,
        sx: Float, sy: Float, sz: Float,
        rotY: Float, r: Float, g: Float, b: Float, emissive: Float
    ) = drawPrimitive(mesh, x, y, z, sx, sy, sz, rotY, r, g, b, emissive)

    internal fun drawShadowDisc(x: Float, y: Float, z: Float, sx: Float, sz: Float, alpha: Float) =
        drawShadow(shadowDiscMesh, x, y, z, sx, sz, alpha)

    internal fun getFactoryCrateGlb(crateModelId: String): GlbModel? = crateGlbCache[crateModelId]

    // ==================== TERRAIN & FACTORIES ====================

    private fun drawTerrainBase() {
        // Lush Clash-of-Clans valley grass
        drawPrimitive(boxMesh, 0f, -0.5f, 0f, 44f, 1.0f, 44f, 0f, 0.44f, 0.65f, 0.30f, 0f)
        // Warm sunlit dirt compound covering both factories & warehouse
        drawPrimitive(boxMesh, 0.8f, 0.01f, 0f, 19.5f, 0.04f, 20.5f, 0f, 0.72f, 0.60f, 0.42f, 0f)
    }

    private fun drawTerrainContactShadows(f0: FactoryState) {
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glDepthMask(false)

        val platformW = GameEngine.FACTORY_HALF_WIDTH * 2f
        val platformD = 4.2f

        for (i in engine.factories.indices) {
            val fac = engine.factories[i]
            drawShadow(
                mesh = shadowQuadMesh,
                x = fac.worldX - 0.30f,
                y = 0.036f,
                z = fac.worldZ - 0.45f,
                sx = platformW + 0.90f,
                sz = platformD + 0.80f,
                alpha = 0.28f
            )
        }

        for (k in -2..2) {
            val z = k * 5.8f
            drawTreeShadow(-11.4f, z + 0.8f, 1.15f)
            drawTreeShadow(12.2f, z - 0.9f, 1.05f)
        }

        drawShadow(shadowDiscMesh, f0.worldX - 7.05f, 0.036f, f0.worldZ - 1.0f, 4.6f, 4.6f, 0.28f)
        logisticsRenderer.drawContactShadows(this)

        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun drawTreeShadow(x: Float, z: Float, scale: Float) {
        drawShadow(shadowDiscMesh, x - 0.30f * scale, 0.036f, z - 0.24f * scale, 2.05f * scale, 2.05f * scale, 0.30f)
    }

    private fun drawTerrainProps() {
        for (k in -2..2) {
            val z = k * 5.8f
            drawTree(-11.4f, z + 0.8f, 1.15f)
            drawTree(12.2f, z - 0.9f, 1.05f)
            drawPrimitive(boxMesh, -9.8f, 0.32f, z - 2.1f, 1.1f, 0.65f, 1.0f, 22f, 0.54f, 0.52f, 0.50f, 0f)
            drawPrimitive(boxMesh, 10.6f, 0.30f, z + 2.0f, 1.0f, 0.60f, 1.1f, -18f, 0.52f, 0.50f, 0.48f, 0f)
        }
    }

    private fun drawTree(x: Float, z: Float, scale: Float) {
        drawPrimitive(cylMesh, x, 0.6f * scale, z, 0.42f * scale, 1.2f * scale, 0.42f * scale, 0f, 0.46f, 0.30f, 0.18f, 0f)
        drawPrimitive(pyrMesh, x, 1.65f * scale, z, 1.6f * scale, 1.5f * scale, 1.6f * scale, 15f, 0.25f, 0.54f, 0.24f, 0f)
        drawPrimitive(pyrMesh, x, 2.35f * scale, z, 1.15f * scale, 1.2f * scale, 1.15f * scale, 45f, 0.32f, 0.62f, 0.26f, 0f)
    }

    private fun drawResourceNode(x: Float, z: Float, t: Float) {
        drawPrimitive(cylMesh, x, 0.1f, z, 4.0f, 0.2f, 4.0f, 0f, 0.40f, 0.58f, 0.28f, 0f)
        drawTree(x - 0.9f, z - 0.8f, 1.2f)
        drawTree(x + 0.9f, z - 0.4f, 1.05f)
        drawTree(x - 0.2f, z + 0.8f, 0.95f)
        val pulse = 0.04f * sin(t * 2.5f)
        drawPrimitive(cylMesh, x + 1.4f, 0.35f + pulse, z + 0.5f, 0.42f, 1.3f, 0.42f, 90f, 0.66f, 0.42f, 0.22f, 0f)
    }

    private fun drawFactory(
        factoryIndex: Int,
        fac: FactoryState,
        isSelected: Boolean,
        readyToUnlock: Boolean,
        t: Float,
        overclockGlow: Float
    ) {
        val fx = fac.worldX
        val fz = fac.worldZ
        val platformW = GameEngine.FACTORY_HALF_WIDTH * 2f
        val platformD = 4.2f
        val platformZ = fz - 0.35f // Slightly shifted back to cradle SIDE machines cleanly

        if (isSelected) {
            drawPrimitive(boxMesh, fx, 0.025f, platformZ, platformW + 0.22f, 0.05f, platformD + 0.22f, 0f, 0.92f, 0.72f, 0.28f, 0.14f)
        } else if (readyToUnlock) {
            val pulse = 0.25f + 0.2f * abs(sin(t * 4f))
            drawPrimitive(boxMesh, fx, 0.06f, platformZ, platformW + 0.50f, 0.12f, platformD + 0.50f, 0f, 0.18f, 0.86f, 0.44f, pulse)
        }

        if (!fac.unlocked) {
            drawPrimitive(boxMesh, fx, 0.14f, platformZ, platformW, 0.24f, platformD, 0f, 0.46f, 0.48f, 0.50f, 0f)
            val totemG = if (readyToUnlock) 0.85f else 0.55f
            val totemR = if (readyToUnlock) 0.20f else 0.55f
            drawPrimitive(pyrMesh, fx, 0.95f, fz, 1.2f, 1.35f, 1.2f, if (readyToUnlock) t * 70f else 0f, totemR, totemG, 0.35f, if (readyToUnlock) 0.35f else 0f)
            return
        }

        // Factory stone base pad (subtle warm stone for Wood, darker volcanic stone for Charcoal)
        val padR = if (factoryIndex == 0) 0.76f else 0.42f
        val padG = if (factoryIndex == 0) 0.69f else 0.40f
        val padB = if (factoryIndex == 0) 0.58f else 0.39f
        drawPrimitive(boxMesh, fx, 0.04f, platformZ, platformW, 0.08f, platformD, 0f, padR, padG, padB, 0f)

        // Stone-Age Conveyor Belt at exact Blender height Z=0.34m (uses real conveyor_straight.glb!)
        val beltGlb = machineGlbCache["conveyor_straight"]
        if (beltGlb != null) {
            for (seg in 0 until 6) {
                val bx = fx - 1.75f + seg * 1.20f
                drawMachineGlb(beltGlb, bx, 0.08f, fz, 1.0f, t * 2.0f, 0f)
            }
        } else {
            drawPrimitive(boxMesh, fx + 0.85f, 0.17f, fz, 8.2f, 0.34f, 0.84f, 0f, 0.52f, 0.50f, 0.48f, 0f)
            drawPrimitive(boxMesh, fx + 0.85f, 0.34f, fz, 8.0f, 0.05f, 0.68f, 0f, 0.64f, 0.42f, 0.22f, 0f)
        }

        val accentR = factoryAccentR[factoryIndex]
        val accentG = factoryAccentG[factoryIndex]
        val accentB = factoryAccentB[factoryIndex]
        val bottleneckIdx = fac.bottleneckMachineIndex()

        // Machine contact shadows
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glDepthMask(false)
        for (mIdx in fac.machines.indices) {
            val mDef = fac.machines[mIdx].def
            val mx = fx + mDef.localX
            val mz = fz + mDef.localZ
            drawShadow(shadowDiscMesh, mx - 0.12f, 0.088f, mz - 0.10f, 2.05f, 1.95f, 0.28f)
        }
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)

        // Draw all 4 machines (real Blender GLB if loaded, else crisp procedural fallback)
        for (mIdx in fac.machines.indices) {
            val machine = fac.machines[mIdx]
            val mDef = machine.def
            val mx = fx + mDef.localX
            val mz = fz + mDef.localZ
            val isBottleneck = (mIdx == bottleneckIdx)
            val speedAnim = (1.5f + machine.effectiveSpeed().toFloat() * 0.4f).coerceAtMost(8f)
            val phase = t * speedAnim + mIdx * 1.1f

            val flashSlot = factoryIndex * maxMachinesPerFactory + mIdx
            val flashRem = if (flashSlot in upgradeFlashRemaining.indices) upgradeFlashRemaining[flashSlot] else 0f
            val flashNorm = if (flashRem > 0f) (flashRem / UPGRADE_FLASH_DURATION_SEC).coerceIn(0f, 1f) else 0f
            val flashPulse = flashNorm * flashNorm
            val popScale = if (flashNorm > 0f) 1f + sin(flashNorm * PI.toFloat()) * 0.12f else 1f

            if (flashNorm > 0f) {
                val ringScale = 1.65f + (1f - flashNorm) * 1.20f
                drawPrimitive(cylMesh, mx, 0.11f, mz, ringScale, 0.06f, ringScale, 0f, 1.0f, 0.86f, 0.28f, 0.75f * flashNorm)
            } else if (isBottleneck) {
                val glow = 0.28f + 0.26f * abs(sin(t * 5.0f))
                drawPrimitive(cylMesh, mx, 0.10f, mz, 1.85f, 0.06f, 1.85f, 0f, 1.0f, 0.52f, 0.08f, glow)
            }

            val combinedEmissive = overclockGlow + flashPulse * 0.85f
            val glbModel = machineGlbCache[mDef.modelId]

            if (glbModel != null) {
                drawMachineGlb(glbModel, mx, 0.08f, mz, popScale, phase, combinedEmissive)
            } else {
                val levelBoost = (1f + (machine.level - 1) * 0.014f).coerceAtMost(1.38f) * popScale
                drawProceduralFallbackMachine(
                    archetype = mDef.archetype,
                    placement = mDef.placement,
                    x = mx, y = 0.08f, z = mz,
                    scaleY = levelBoost,
                    scaleXZ = popScale,
                    phase = phase,
                    ar = accentR, ag = accentG, ab = accentB,
                    emissive = combinedEmissive
                )
            }
        }
    }

    private fun drawMachineGlb(
        model: GlbModel,
        x: Float, y: Float, z: Float,
        scale: Float,
        phase: Float,
        emissive: Float
    ) {
        val bob = abs(sin(phase))
        val wave = sin(phase)
        for (part in model.parts) {
            var dx = 0f
            var dy = 0f
            var dz = 0f
            var extraScale = 1f
            if (part.isAnimatedPart) {
                val n = part.nodeName
                when {
                    n.contains("Pusher", ignoreCase = true) -> dx = bob * 0.12f
                    n.contains("Blade", ignoreCase = true) -> dy = -bob * 0.34f
                    n.contains("Flame", ignoreCase = true) || n.contains("Ember", ignoreCase = true) -> {
                        dy = bob * 0.06f
                    }
                    n.contains("Smoke", ignoreCase = true) || n.contains("Spark", ignoreCase = true) -> {
                        val u = (phase * 0.25f) % 1f
                        dy = u * 0.95f
                        dx = sin(phase + part.pivotX * 3f) * 0.12f
                    }
                    n.contains("WaterCurtain", ignoreCase = true) -> {
                        extraScale = if (wave > 0.15f) 1f else 0.05f
                    }
                    else -> {
                        dy = wave * 0.08f
                    }
                }
            }
            val finalScale = scale * extraScale
            if (finalScale <= 0.02f) continue
            drawPrimitive(
                mesh = part.mesh,
                x = x + dx, y = y + dy, z = z + dz,
                sx = finalScale, sy = finalScale, sz = finalScale,
                rotY = 0f,
                r = part.r, g = part.g, b = part.b,
                emissive = emissive + part.emissiveBoost
            )
        }
    }

    private fun drawProceduralFallbackMachine(
        archetype: MachineArchetype,
        placement: MachinePlacement,
        x: Float, y: Float, z: Float,
        scaleY: Float, scaleXZ: Float,
        phase: Float,
        ar: Float, ag: Float, ab: Float,
        emissive: Float
    ) {
        val bob = abs(sin(phase))
        when (placement) {
            MachinePlacement.FEEDER -> {
                // Raised timber/stone feeder pen with +X open chute at Y=0.34
                drawPrimitive(boxMesh, x - 0.1f, y + 0.18f, z, 1.4f * scaleXZ, 0.34f, 1.3f * scaleXZ, 0f, 0.50f, 0.44f, 0.38f, emissive)
                drawPrimitive(boxMesh, x - 0.6f, y + 0.65f * scaleY, z, 0.25f * scaleXZ, 0.95f * scaleY, 1.25f * scaleXZ, 0f, ar, ag, ab, emissive)
                drawPrimitive(boxMesh, x + 0.45f, y + 0.30f, z, 0.55f * scaleXZ, 0.10f, 0.70f * scaleXZ, 0f, 0.82f, 0.64f, 0.38f, emissive)
                drawPrimitive(boxMesh, x - 0.2f + bob * 0.24f, y + 0.52f, z, 0.28f * scaleXZ, 0.32f, 0.65f * scaleXZ, 0f, 0.56f, 0.35f, 0.18f, emissive)
            }
            MachinePlacement.OVERHEAD -> {
                // Open-corridor gantry straddling belt cleanly (Z = +-0.70 outside belt!)
                drawPrimitive(boxMesh, x, y + 0.82f * scaleY, z - 0.72f, 0.34f * scaleXZ, 1.60f * scaleY, 0.34f * scaleXZ, 0f, 0.52f, 0.48f, 0.44f, emissive)
                drawPrimitive(boxMesh, x, y + 0.82f * scaleY, z + 0.72f, 0.34f * scaleXZ, 1.60f * scaleY, 0.34f * scaleXZ, 0f, 0.52f, 0.48f, 0.44f, emissive)
                drawPrimitive(boxMesh, x, y + 1.56f * scaleY, z, 0.44f * scaleXZ, 0.30f, 1.80f * scaleXZ, 0f, ar, ag, ab, emissive)
                if (archetype == MachineArchetype.WASHER) {
                    drawPrimitive(boxMesh, x, y + 1.72f * scaleY, z, 0.36f * scaleXZ, 0.14f, 1.45f * scaleXZ, 0f, 0.18f, 0.77f, 0.71f, 0.25f + emissive)
                    drawPrimitive(boxMesh, x, y + 0.85f, z, 0.32f * scaleXZ, 0.55f * bob, 0.18f * scaleXZ, 0f, 0.18f, 0.77f, 0.71f, 0.35f + emissive)
                } else {
                    drawPrimitive(boxMesh, x, y + 1.05f - bob * 0.38f, z, 0.48f * scaleXZ, 0.36f, 0.64f * scaleXZ, 0f, 0.40f, 0.42f, 0.46f, emissive)
                }
            }
            MachinePlacement.SIDE -> {
                // Kiln / Ziggurat sitting behind belt facing player (+Z)
                drawPrimitive(cylMesh, x, y + 0.65f * scaleY, z, 1.50f * scaleXZ, 1.25f * scaleY, 1.50f * scaleXZ, 0f, ar, ag, ab, emissive)
                drawPrimitive(pyrMesh, x, y + 0.52f + bob * 0.10f, z + 0.58f, 0.52f * scaleXZ, 0.62f, 0.42f * scaleXZ, phase * 45f, 1.0f, 0.40f, 0.08f, 0.55f + bob * 0.25f + emissive)
            }
        }
    }

    private fun drawVisualBeltProducts() {
        for (c in engine.visualCrates) {
            if (!c.active) continue
            val fac = engine.factories.getOrNull(c.fromFactoryIndex) ?: continue
            val startX = fac.worldX - 2.2f
            val endX = fac.worldX + 4.8f
            val cx = startX + (endX - startX) * c.t
            drawPrimitive(boxMesh, cx, 0.46f, fac.worldZ, 0.46f, 0.26f, 0.30f, 0f, c.colorR, c.colorG, c.colorB, 0.08f)
        }
    }

    private fun drawShadow(
        mesh: SimpleMesh,
        x: Float, y: Float, z: Float,
        sx: Float, sz: Float,
        alpha: Float
    ) {
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, x, y, z)
        Matrix.scaleM(modelMatrix, 0, sx, 1f, sz)
        Matrix.multiplyMM(mvpMatrix, 0, camera.vpMatrix, 0, modelMatrix, 0)

        if (uMvpHandle >= 0) GLES30.glUniformMatrix4fv(uMvpHandle, 1, false, mvpMatrix, 0)
        if (uModelHandle >= 0) GLES30.glUniformMatrix4fv(uModelHandle, 1, false, modelMatrix, 0)
        if (uColorHandle >= 0) GLES30.glUniform3f(uColorHandle, 0.11f, 0.09f, 0.07f)
        if (uEmissiveHandle >= 0) GLES30.glUniform1f(uEmissiveHandle, 0f)
        if (uAlphaHandle >= 0) GLES30.glUniform1f(uAlphaHandle, alpha)
        mesh.draw(aPosHandle, aNormHandle, aColorHandle)
    }

    private fun drawPrimitive(
        mesh: SimpleMesh,
        x: Float, y: Float, z: Float,
        sx: Float, sy: Float, sz: Float,
        rotY: Float,
        r: Float, g: Float, b: Float,
        emissive: Float
    ) {
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, x, y, z)
        if (rotY != 0f) {
            Matrix.rotateM(modelMatrix, 0, rotY, 0f, 1f, 0f)
        }
        Matrix.scaleM(modelMatrix, 0, sx, sy, sz)
        Matrix.multiplyMM(mvpMatrix, 0, camera.vpMatrix, 0, modelMatrix, 0)

        if (uMvpHandle >= 0) GLES30.glUniformMatrix4fv(uMvpHandle, 1, false, mvpMatrix, 0)
        if (uModelHandle >= 0) GLES30.glUniformMatrix4fv(uModelHandle, 1, false, modelMatrix, 0)
        if (uColorHandle >= 0) GLES30.glUniform3f(uColorHandle, r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
        if (uEmissiveHandle >= 0) GLES30.glUniform1f(uEmissiveHandle, emissive)
        if (uAlphaHandle >= 0) GLES30.glUniform1f(uAlphaHandle, 1.0f)
        mesh.draw(aPosHandle, aNormHandle, aColorHandle)
    }

    private fun buildProgram(vertSrc: String, fragSrc: String): Int {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vertSrc)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fragSrc)
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vs)
        GLES30.glAttachShader(prog, fs)
        GLES30.glLinkProgram(prog)
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, src)
        GLES30.glCompileShader(shader)
        return shader
    }
}
