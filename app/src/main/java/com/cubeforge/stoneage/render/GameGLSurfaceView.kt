package com.cubeforge.stoneage.render

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.cubeforge.stoneage.model.BigNumber
import com.cubeforge.stoneage.model.CollectResult
import com.cubeforge.stoneage.sim.GameEngine
import kotlin.math.hypot

/**
 * Interactive 3D GLSurfaceView supporting:
 *  - Clash-of-Clans 1-finger pan & 2-finger pinch-zoom
 *  - Direct 3D taps on Collection Vessels (manual collect), Input Feeders (manual feed),
 *    Central Era Warehouse (open warehouse modal), Golden Anomaly, and Factory platforms.
 */
class GameGLSurfaceView(
    context: Context,
    private val engine: GameEngine,
    private val onFactorySelected: (Int) -> Unit,
    private val onAnomalyCollected: (BigNumber, Float, Float) -> Unit,
    private val onVesselCollected: (Int, CollectResult, Float, Float) -> Unit,
    private val onFactoryFed: (Int, Double, Float, Float) -> Unit,
    private val onWarehouseTapped: () -> Unit
) : GLSurfaceView(context) {

    val camera = ClashCamera()
    private val renderer: FactoryRenderer

    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var isDragging = false
    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                queueEvent { camera.zoomByScaleFactor(factor) }
                isDragging = true
                return true
            }
        }
    )

    init {
        setEGLContextClientVersion(3)
        preserveEGLContextOnPause = true
        renderer = FactoryRenderer(context, engine, camera)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun focusOnFactory(factoryIndex: Int) {
        val fac = engine.factories.getOrNull(factoryIndex) ?: return
        queueEvent {
            camera.focusOn(fac.worldX, fac.worldZ)
        }
    }

    fun focusOnWarehouse() {
        val wh = engine.logistics.warehouse
        queueEvent {
            camera.focusOn(wh.worldX, wh.worldZ)
        }
    }

    fun triggerMachineUpgradeFlash(factoryIndex: Int, machineIndex: Int) {
        queueEvent {
            renderer.triggerUpgradeFlash(factoryIndex, machineIndex)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                isDragging = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress) {
                    val idx = event.findPointerIndex(activePointerId)
                    if (idx >= 0) {
                        val x = event.getX(idx)
                        val y = event.getY(idx)
                        val dx = x - lastX
                        val dy = y - lastY
                        if (hypot((x - downX).toDouble(), (y - downY).toDouble()) > 14.0) {
                            isDragging = true
                        }
                        if (isDragging) {
                            queueEvent { camera.panByScreenDelta(dx, dy) }
                        }
                        lastX = x
                        lastY = y
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                if (!isDragging && !scaleDetector.isInProgress) {
                    val tapX = event.x
                    val tapY = event.y
                    queueEvent {
                        val hit = camera.screenToGroundPoint(tapX, tapY) ?: return@queueEvent
                        val wx = hit.first
                        val wz = hit.second

                        // 1. Check Central Warehouse tap
                        if (engine.logistics.pickWarehouseAt(wx, wz)) {
                            val wh = engine.logistics.warehouse
                            camera.focusOn(wh.worldX, wh.worldZ)
                            post { onWarehouseTapped() }
                            return@queueEvent
                        }

                        // 2. Check Collection Vessel tap (instant manual collect)
                        val vesselFacIdx = engine.logistics.pickVesselAt(wx, wz)
                        if (vesselFacIdx >= 0) {
                            val res = engine.logistics.collectVesselManually(vesselFacIdx)
                            if (res.collectedUnits > 0.0) {
                                post { onVesselCollected(vesselFacIdx, res, tapX, tapY) }
                                return@queueEvent
                            }
                        }

                        // 3. Check Input Feeder tap (instant manual feed from warehouse)
                        val feederFacIdx = engine.logistics.pickFeederAt(wx, wz)
                        if (feederFacIdx >= 1) {
                            val fed = engine.logistics.feedFactoryManually(feederFacIdx)
                            if (fed > 0.0) {
                                post { onFactoryFed(feederFacIdx, fed, tapX, tapY) }
                                return@queueEvent
                            }
                        }

                        // 4. Check Factory platform & Golden Anomaly tap
                        val tappedIdx = engine.pickFactoryAt(wx, wz)
                        if (tappedIdx >= 0) {
                            if (engine.goldenAnomaly.active && engine.goldenAnomaly.factoryIndex == tappedIdx) {
                                val reward = engine.collectGoldenAnomaly()
                                if (reward != null) {
                                    post { onAnomalyCollected(reward, tapX, tapY) }
                                    return@queueEvent
                                }
                            }
                            synchronized(engine.lock) {
                                engine.selectedFactoryIndex = tappedIdx
                            }
                            val fac = engine.factories[tappedIdx]
                            camera.focusOn(fac.worldX, fac.worldZ)
                            post { onFactorySelected(tappedIdx) }
                        }
                    }
                }
                activePointerId = MotionEvent.INVALID_POINTER_ID
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                if (pointerId == activePointerId) {
                    val newIndex = if (pointerIndex == 0) 1 else 0
                    lastX = event.getX(newIndex)
                    lastY = event.getY(newIndex)
                    activePointerId = event.getPointerId(newIndex)
                }
            }
        }
        return true
    }
}
