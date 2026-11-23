package com.cubeforge.stoneage.render

import android.content.Context
import android.opengl.GLES30
import com.cubeforge.stoneage.model.FactoryLogisticsState
import com.cubeforge.stoneage.model.WorkerAgent
import com.cubeforge.stoneage.model.WorkerPhase
import com.cubeforge.stoneage.model.WorkerRouteState
import com.cubeforge.stoneage.sim.GameEngine
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Renders the shared Era-Agnostic Logistics System in OpenGL ES 3.0:
 *  - Per-factory Collection Vessels (rising product level + amber pulse at >= 80% full)
 *  - Per-factory Input Feeders (factories >= 2, pulsing indicator when awaiting raw materials)
 *  - Central Era Warehouse with dynamically stacked crates
 *  - Walking 3D Transport Workers (bounce + tilt, heavy sled when carryCapacity >= 3)
 *  - Overseer Managers standing beside hired factories with animated staff
 *  - Parabolic Flying Crates for manual transfers
 *
 * Automatically uses assets/models/eras/<eraId>/model.glb when present, with full procedural 3D fallback.
 * Strictly ZERO heap allocations inside draw methods.
 */
class LogisticsRenderer(
    private val context: Context,
    private val engine: GameEngine
) {
    private val glbCache = LogisticsGlbCache()

    fun onSurfaceCreated() {
        glbCache.loadForEra(context, engine.era.id)
    }

    fun drawContactShadows(renderer: FactoryRenderer) {
        val log = engine.logistics
        val wh = log.warehouse

        // Warehouse ground contact shadow
        renderer.drawShadowDisc(wh.worldX - 0.25f, 0.037f, wh.worldZ - 0.20f, 4.9f, 4.9f, 0.30f)

        // Vessels, feeders, workers, and managers shadows
        for (i in engine.factories.indices) {
            if (!engine.factories[i].unlocked) continue
            val st = log.factoryStates[i]

            renderer.drawShadowDisc(st.vesselX - 0.12f, 0.037f, st.vesselZ - 0.10f, 1.85f, 1.85f, 0.28f)
            if (i > 0) {
                renderer.drawShadowDisc(st.feederX - 0.12f, 0.037f, st.feederZ - 0.10f, 1.70f, 1.70f, 0.26f)
            }
            if (st.manager.hired) {
                renderer.drawShadowDisc(st.managerX - 0.08f, 0.037f, st.managerZ - 0.08f, 1.15f, 1.15f, 0.26f)
            }

            val wCount = st.workerRoute.workerCount.coerceIn(0, WorkerRouteState.MAX_WORKERS)
            for (w in 0 until wCount) {
                val agent = st.workerRoute.agents[w]
                if (!agent.active) continue
                val shadowSize = if (st.workerRoute.isHeavyCarrier) 1.35f else 0.95f
                renderer.drawShadowDisc(agent.worldX - 0.06f, 0.038f, agent.worldZ - 0.06f, shadowSize, shadowSize, 0.26f)
            }
        }
    }

    fun drawOpaqueLogistics(renderer: FactoryRenderer, t: Float) {
        val log = engine.logistics

        // 1. Central Era Warehouse
        drawCentralEraWarehouse(renderer, t)

        // 2. Per-factory vessels, feeders, managers, and workers
        for (i in engine.factories.indices) {
            val fac = engine.factories[i]
            if (!fac.unlocked) continue
            val st = log.factoryStates[i]

            drawCollectionVessel(renderer, st, t)
            if (i > 0 && fac.def.input != null) {
                val prevSt = log.factoryStates[i - 1]
                drawInputFeeder(renderer, st, prevSt, t)
            }
            if (st.manager.hired) {
                drawManager(renderer, st, t)
            }

            val wCount = st.workerRoute.workerCount.coerceIn(0, WorkerRouteState.MAX_WORKERS)
            for (w in 0 until wCount) {
                val agent = st.workerRoute.agents[w]
                if (!agent.active) continue
                drawWorker(renderer, agent, st.workerRoute.isHeavyCarrier, t)
            }
        }

        // 3. Parabolic Flying Crates
        drawFlyingCrates(renderer, t)
    }

    private fun drawCentralEraWarehouse(renderer: FactoryRenderer, t: Float) {
        val wh = engine.logistics.warehouse
        val wx = wh.worldX
        val wz = wh.worldZ
        val fill = wh.fillRatio()

        val glb = glbCache.warehouse
        if (glb != null) {
            drawGlbModel(renderer, glb, wx, 0.04f, wz, 1.0f, 0f, 0f, 0f, 0f, false, t)
        } else {
            // Procedural Stone-Age Depot Fallback
            // Raised stone floor
            renderer.drawBox(wx, 0.16f, wz, 3.8f, 0.32f, 3.6f, 0f, 0.54f, 0.51f, 0.46f, 0f)
            // Back & side stone walls (front +Z open so stacked crates are visible!)
            renderer.drawBox(wx, 0.95f, wz - 1.55f, 3.6f, 1.35f, 0.36f, 0f, 0.62f, 0.58f, 0.52f, 0f)
            renderer.drawBox(wx - 1.65f, 0.95f, wz - 0.15f, 0.36f, 1.35f, 2.8f, 0f, 0.58f, 0.54f, 0.48f, 0f)
            renderer.drawBox(wx + 1.65f, 0.95f, wz - 0.15f, 0.36f, 1.35f, 2.8f, 0f, 0.58f, 0.54f, 0.48f, 0f)
            // Front timber pillars
            renderer.drawBox(wx - 1.60f, 1.05f, wz + 1.45f, 0.32f, 1.55f, 0.32f, 0f, 0.46f, 0.29f, 0.16f, 0f)
            renderer.drawBox(wx + 1.60f, 1.05f, wz + 1.45f, 0.32f, 1.55f, 0.32f, 0f, 0.46f, 0.29f, 0.16f, 0f)
            // Pitched warm thatch/timber roof
            renderer.drawPyramid(wx, 2.28f, wz, 4.5f, 1.15f, 4.3f, 45f, 0.72f, 0.52f, 0.28f, 0f)
            // Amber crest beacon when warehouse has stock
            val beaconGlow = if (fill > 0.05f) 0.25f + 0.15f * abs(sin(t * 3.2f)) else 0.05f
            renderer.drawBox(wx, 1.72f, wz + 1.52f, 1.4f, 0.24f, 0.22f, 0f, 0.96f, 0.72f, 0.22f, beaconGlow)
        }

        // Dynamically stacked crates inside/front of warehouse based on inventory fill (up to 9 crates)
        val crateCount = (fill * 9f).toInt().coerceIn(if (wh.totalStored() >= 0.5) 1 else 0, 9)
        for (c in 0 until crateCount) {
            val layer = c / 3
            val col = c % 3
            val cx = wx - 0.85f + col * 0.85f
            val cy = 0.56f + layer * 0.48f
            val cz = wz + 0.35f - layer * 0.22f
            drawCrateUnit(renderer, cx, cy, cz, 0.58f, (c * 14f) % 30f - 15f, 0.85f, 0.62f, 0.28f, 0.06f)
        }
    }

    private fun drawCollectionVessel(renderer: FactoryRenderer, st: FactoryLogisticsState, t: Float) {
        val vx = st.vesselX
        val vz = st.vesselZ
        val ratio = st.vessel.fillRatio
        val isNearFull = ratio >= 0.80f || st.vessel.isFull
        val pulseGlow = if (isNearFull) 0.30f + 0.35f * abs(sin(t * 6.0f)) else 0f

        // Pulsing amber warning ring when >= 80% full
        if (isNearFull) {
            val ringScale = 1.72f + 0.16f * abs(sin(t * 6.0f))
            renderer.drawCylinder(vx, 0.09f, vz, ringScale, 0.08f, ringScale, 0f, 1.0f, 0.64f, 0.10f, pulseGlow)
        }

        val facDef = engine.factories.getOrNull(st.factoryIndex)?.def
        val vesselGlb = if (facDef != null) renderer.getFactoryCrateGlb(facDef.crateModelId) else null
        if (vesselGlb != null) {
            drawGlbModel(renderer, vesselGlb, vx, 0.08f, vz, 1.0f, 0f, 0f, 0f, pulseGlow * 0.35f, false, t)
        } else {
            renderer.drawBox(vx, 0.24f, vz, 1.45f, 0.42f, 1.45f, 0f, 0.50f, 0.47f, 0.43f, pulseGlow * 0.25f)
            renderer.drawBox(vx, 0.48f, vz - 0.62f, 1.45f, 0.28f, 0.18f, 0f, 0.60f, 0.42f, 0.24f, pulseGlow * 0.3f)
            renderer.drawBox(vx, 0.48f, vz + 0.62f, 1.45f, 0.28f, 0.18f, 0f, 0.60f, 0.42f, 0.24f, pulseGlow * 0.3f)
            renderer.drawBox(vx - 0.62f, 0.48f, vz, 0.18f, 0.28f, 1.10f, 0f, 0.60f, 0.42f, 0.24f, pulseGlow * 0.3f)
            renderer.drawBox(vx + 0.62f, 0.48f, vz, 0.18f, 0.28f, 1.10f, 0f, 0.60f, 0.42f, 0.24f, pulseGlow * 0.3f)
        }

        // Rising product fill inside vessel
        if (ratio > 0.02f) {
            val fillH = 0.15f + ratio * 0.82f
            val fillEmissive = if (isNearFull) 0.20f + pulseGlow * 0.45f else 0.06f
            drawCrateUnit(
                renderer,
                vx, 0.42f + fillH * 0.5f, vz,
                0.96f, 0f,
                st.colorR, st.colorG, st.colorB,
                fillEmissive,
                scaleYOverride = fillH
            )
        }
    }

    private fun drawInputFeeder(
        renderer: FactoryRenderer,
        st: FactoryLogisticsState,
        prevSt: FactoryLogisticsState,
        t: Float
    ) {
        val fx = st.feederX
        val fz = st.feederZ
        val needsInput = st.inputStock < 1.0
        val pulse = if (needsInput) 0.25f + 0.30f * abs(sin(t * 4.8f)) else 0f

        if (needsInput) {
            // Pulsing teal-amber beacon inviting player to tap & feed raw materials
            renderer.drawCylinder(fx, 0.09f, fz, 1.62f, 0.08f, 1.62f, 0f, 0.22f, 0.82f, 0.92f, pulse)
        }

        // Wooden/stone input hopper base
        renderer.drawBox(fx, 0.26f, fz, 1.32f, 0.44f, 1.32f, 0f, 0.44f, 0.34f, 0.24f, pulse * 0.2f)

        val fillRatio = (st.inputStock / 20.0).toFloat().coerceIn(0f, 1f)
        if (fillRatio > 0.02f) {
            val h = 0.14f + fillRatio * 0.65f
            renderer.drawBox(fx, 0.44f + h * 0.5f, fz, 0.88f, h, 0.88f, 0f, prevSt.colorR, prevSt.colorG, prevSt.colorB, 0.05f)
        }
    }

    private fun drawWorker(
        renderer: FactoryRenderer,
        agent: WorkerAgent,
        isHeavyCarrier: Boolean,
        t: Float
    ) {
        val isMoving = agent.phase != WorkerPhase.IDLE
        val bounce = if (isMoving) abs(sin(agent.walkPhase)) * 0.18f else abs(sin(t * 2.5f)) * 0.03f
        val tilt = if (isMoving) sin(agent.walkPhase) * 9f else 0f

        val glb = if (isHeavyCarrier) {
            glbCache.workerCarrierHeavy ?: glbCache.workerCarrier
        } else {
            glbCache.workerCarrier
        }

        if (glb != null) {
            drawGlbModel(
                renderer, glb,
                agent.worldX, 0.05f + bounce, agent.worldZ,
                1.0f, agent.facingAngleDeg, tilt, 0f, 0f, false, t
            )
        } else {
            // Stylized procedural Stone-Age Worker Carrier fallback
            val wx = agent.worldX
            val wz = agent.worldZ
            val yaw = agent.facingAngleDeg
            val rad = Math.toRadians(yaw.toDouble()).toFloat()
            val fwdX = sin(rad)
            val fwdZ = cos(rad)

            // Tunic body
            renderer.drawCylinder(wx, 0.48f + bounce, wz, 0.48f, 0.62f, 0.48f, yaw, 0.66f, 0.43f, 0.24f, 0f)
            // Head
            renderer.drawBox(wx, 0.96f + bounce, wz, 0.38f, 0.36f, 0.38f, yaw + tilt * 0.6f, 0.88f, 0.70f, 0.54f, 0f)
            // Hair / headband accent
            renderer.drawBox(wx, 1.12f + bounce, wz, 0.40f, 0.10f, 0.40f, yaw + tilt * 0.6f, 0.24f, 0.16f, 0.11f, 0f)

            // Heavy sled (unlocked at capacity >= 3) pulled cleanly behind the worker — no wheels!
            if (isHeavyCarrier) {
                val sledX = wx - fwdX * 0.72f
                val sledZ = wz - fwdZ * 0.72f
                renderer.drawBox(sledX, 0.12f, sledZ, 0.74f, 0.14f, 0.92f, yaw, 0.54f, 0.34f, 0.18f, 0f)
            }
        }

        // Carried crate(s) colored with the product color
        if (agent.carrying) {
            val rad = Math.toRadians(agent.facingAngleDeg.toDouble()).toFloat()
            val fwdX = sin(rad)
            val fwdZ = cos(rad)
            if (isHeavyCarrier) {
                // Stacked crates riding on the heavy sled behind the worker
                val sledX = agent.worldX - fwdX * 0.72f
                val sledZ = agent.worldZ - fwdZ * 0.72f
                drawCrateUnit(renderer, sledX, 0.38f, sledZ, 0.56f, agent.facingAngleDeg, agent.carryColorR, agent.carryColorG, agent.carryColorB, 0.12f)
                drawCrateUnit(renderer, sledX, 0.76f, sledZ, 0.46f, agent.facingAngleDeg + 12f, agent.carryColorR, agent.carryColorG, agent.carryColorB, 0.12f)
            } else {
                // Single crate carried in front of the worker's arms
                val cx = agent.worldX + fwdX * 0.46f
                val cz = agent.worldZ + fwdZ * 0.46f
                drawCrateUnit(renderer, cx, 0.66f + bounce, cz, 0.48f, agent.facingAngleDeg, agent.carryColorR, agent.carryColorG, agent.carryColorB, 0.12f)
            }
        }
    }

    private fun drawManager(renderer: FactoryRenderer, st: FactoryLogisticsState, t: Float) {
        val mx = st.managerX
        val mz = st.managerZ
        val motivateWave = sin(t * 4.5f + st.factoryIndex) * 0.16f

        val glb = glbCache.managerOverseer
        if (glb != null) {
            drawGlbModel(renderer, glb, mx, 0.05f, mz, 1.0f, 0f, 0f, motivateWave, 0f, true, t)
            return
        }

        // Procedural Overseer Manager fallback: fur cloak + golden crown accent + waving stone staff
        renderer.drawCylinder(mx, 0.10f, mz, 0.95f, 0.14f, 0.95f, 0f, 0.96f, 0.76f, 0.22f, 0.22f)
        renderer.drawCylinder(mx, 0.58f, mz, 0.58f, 0.78f, 0.58f, 0f, 0.42f, 0.22f, 0.14f, 0.05f)
        renderer.drawBox(mx, 1.14f, mz, 0.42f, 0.40f, 0.42f, 0f, 0.90f, 0.72f, 0.55f, 0f)
        // Overseer headdress / crest
        renderer.drawPyramid(mx, 1.46f, mz, 0.46f, 0.30f, 0.46f, t * 20f, 0.98f, 0.78f, 0.20f, 0.25f)
        // Animated motivating staff (AnimatedPart equivalent)
        renderer.drawCylinder(mx + 0.42f, 0.85f + motivateWave, mz + 0.15f, 0.10f, 1.35f, 0.10f, 0f, 0.58f, 0.38f, 0.20f, 0f)
        renderer.drawPyramid(mx + 0.42f, 1.60f + motivateWave, mz + 0.15f, 0.28f, 0.30f, 0.28f, t * 90f, 1.0f, 0.58f, 0.14f, 0.45f)
    }

    private fun drawFlyingCrates(renderer: FactoryRenderer, t: Float) {
        val pool = engine.logistics.flyingCrates
        for (i in pool.indices) {
            val fc = pool[i]
            if (!fc.active) continue
            val u = fc.t.coerceIn(0f, 1f)
            val x = fc.startX + (fc.endX - fc.startX) * u
            val z = fc.startZ + (fc.endZ - fc.startZ) * u
            val linearY = fc.startY + (fc.endY - fc.startY) * u
            // Smooth parabolic arc peaking at u = 0.5
            val arcY = linearY + 4f * fc.peakHeight * u * (1f - u)
            drawCrateUnit(renderer, x, arcY, z, 0.62f, u * 240f + t * 40f, fc.colorR, fc.colorG, fc.colorB, 0.28f)
        }
    }

    private fun drawCrateUnit(
        renderer: FactoryRenderer,
        x: Float, y: Float, z: Float,
        size: Float,
        rotY: Float,
        r: Float, g: Float, b: Float,
        emissive: Float,
        scaleYOverride: Float = -1f
    ) {
        val glb = glbCache.crate
        val sy = if (scaleYOverride > 0f) scaleYOverride else size * 0.88f
        if (glb != null) {
            for (part in glb.parts) {
                val mr = (part.r * 0.45f + r * 0.55f).coerceIn(0f, 1f)
                val mg = (part.g * 0.45f + g * 0.55f).coerceIn(0f, 1f)
                val mb = (part.b * 0.45f + b * 0.55f).coerceIn(0f, 1f)
                renderer.drawCustomMesh(part.mesh, x, y, z, size, sy, size, rotY, mr, mg, mb, emissive)
            }
        } else {
            renderer.drawBox(x, y, z, size, sy, size, rotY, r, g, b, emissive)
        }
    }

    private fun drawGlbModel(
        renderer: FactoryRenderer,
        model: GlbModel,
        x: Float, y: Float, z: Float,
        scale: Float,
        rotY: Float,
        animRotOffset: Float,
        animYOffset: Float,
        emissive: Float,
        animateParts: Boolean,
        t: Float
    ) {
        for (part in model.parts) {
            val py = if (animateParts && part.isAnimatedPart) y + animYOffset else y
            val pr = if (animateParts && part.isAnimatedPart) rotY + sin(t * 5f) * 18f else rotY + animRotOffset
            renderer.drawCustomMesh(part.mesh, x, py, z, scale, scale, scale, pr, part.r, part.g, part.b, emissive)
        }
    }
}
