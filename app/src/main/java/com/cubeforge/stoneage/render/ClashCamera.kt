package com.cubeforge.stoneage.render

import android.opengl.Matrix
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Clash of Clans-style 3D isometric-perspective camera tuned for the 2-factory Stone Age valley
 * (Factory 1: Wood, Factory 2: Charcoal, plus Central Warehouse) with smooth pan, pinch-zoom, and raycasting.
 */
class ClashCamera {

    var targetX: Float = 0.5f
    var targetZ: Float = -3.8f
    var distance: Float = 13.5f

    val pitchDeg: Float = 46f
    val yawDeg: Float = 24f

    var eyeX: Float = 0f
        private set
    var eyeY: Float = 14f
        private set
    var eyeZ: Float = 12f
        private set

    private var velX: Float = 0f
    private var velZ: Float = 0f
    private var focusActive: Boolean = false
    private var focusX: Float = 0f
    private var focusZ: Float = 0f

    private val minX = -10f
    private val maxX = 11f
    private val minZ = -14f
    private val maxZ = 16f
    private val minDist = 6.5f
    private val maxDist = 42f

    val viewMatrix = FloatArray(16)
    val projMatrix = FloatArray(16)
    val vpMatrix = FloatArray(16)
    private val invVpMatrix = FloatArray(16)

    private var viewportW: Int = 1080
    private var viewportH: Int = 1920

    fun setViewport(width: Int, height: Int) {
        viewportW = width.coerceAtLeast(1)
        viewportH = height.coerceAtLeast(1)
        val aspect = viewportW.toFloat() / viewportH.toFloat()
        Matrix.perspectiveM(projMatrix, 0, 42f, aspect, 0.8f, 160f)
    }

    fun panByScreenDelta(dxPx: Float, dyPx: Float) {
        focusActive = false
        val sensitivity = (distance / viewportH.toFloat()) * 1.45f
        val yawRad = Math.toRadians(yawDeg.toDouble()).toFloat()
        val cosY = cos(yawRad)
        val sinY = sin(yawRad)

        val moveX = (-dxPx * cosY - dyPx * sinY) * sensitivity
        val moveZ = (dxPx * sinY - dyPx * cosY) * sensitivity

        targetX = (targetX + moveX).coerceIn(minX, maxX)
        targetZ = (targetZ + moveZ).coerceIn(minZ, maxZ)

        velX = moveX * 0.55f
        velZ = moveZ * 0.55f
    }

    fun zoomByScaleFactor(scaleFactor: Float) {
        if (scaleFactor <= 0.01f) return
        distance = (distance / scaleFactor).coerceIn(minDist, maxDist)
    }

    fun focusOn(worldX: Float, worldZ: Float, desiredDistance: Float? = null) {
        focusActive = true
        focusX = worldX.coerceIn(minX, maxX)
        focusZ = worldZ.coerceIn(minZ, maxZ)
        velX = 0f
        velZ = 0f
        if (desiredDistance != null) {
            distance = desiredDistance.coerceIn(minDist, maxDist)
        }
    }

    fun update(dt: Float) {
        if (focusActive) {
            val lerp = (dt * 7.5f).coerceIn(0.05f, 0.35f)
            targetX += (focusX - targetX) * lerp
            targetZ += (focusZ - targetZ) * lerp
            if (abs(focusX - targetX) < 0.05f && abs(focusZ - targetZ) < 0.05f) {
                focusActive = false
            }
        } else if (abs(velX) > 0.001f || abs(velZ) > 0.001f) {
            targetX = (targetX + velX).coerceIn(minX, maxX)
            targetZ = (targetZ + velZ).coerceIn(minZ, maxZ)
            velX *= 0.88f
            velZ *= 0.88f
        }

        val pitchRad = Math.toRadians(pitchDeg.toDouble()).toFloat()
        val yawRad = Math.toRadians(yawDeg.toDouble()).toFloat()

        val horiz = distance * cos(pitchRad)
        eyeX = targetX + horiz * sin(yawRad)
        eyeY = distance * sin(pitchRad)
        eyeZ = targetZ + horiz * cos(yawRad)

        Matrix.setLookAtM(
            viewMatrix, 0,
            eyeX, eyeY, eyeZ,
            targetX, 0f, targetZ,
            0f, 1f, 0f
        )
        Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)
    }

    fun screenToGroundPoint(screenX: Float, screenY: Float): Pair<Float, Float>? {
        if (!Matrix.invertM(invVpMatrix, 0, vpMatrix, 0)) return null

        val ndcX = (2f * screenX / viewportW.toFloat()) - 1f
        val ndcY = 1f - (2f * screenY / viewportH.toFloat())

        val nearNdc = floatArrayOf(ndcX, ndcY, -1f, 1f)
        val farNdc = floatArrayOf(ndcX, ndcY, 1f, 1f)
        val nearWorld = FloatArray(4)
        val farWorld = FloatArray(4)

        Matrix.multiplyMV(nearWorld, 0, invVpMatrix, 0, nearNdc, 0)
        Matrix.multiplyMV(farWorld, 0, invVpMatrix, 0, farNdc, 0)

        if (abs(nearWorld[3]) < 1e-6f || abs(farWorld[3]) < 1e-6f) return null

        val nx = nearWorld[0] / nearWorld[3]
        val ny = nearWorld[1] / nearWorld[3]
        val nz = nearWorld[2] / nearWorld[3]

        val fx = farWorld[0] / farWorld[3]
        val fy = farWorld[1] / farWorld[3]
        val fz = farWorld[2] / farWorld[3]

        val dy = fy - ny
        if (abs(dy) < 1e-5f) return null
        val t = -ny / dy
        if (t < 0f) return null

        return Pair(nx + t * (fx - nx), nz + t * (fz - nz))
    }
}
