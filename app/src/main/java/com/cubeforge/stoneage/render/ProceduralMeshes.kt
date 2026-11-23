package com.cubeforge.stoneage.render

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Interleaved VBO mesh container:
 *   pos(3) + normal(3) + vertexColor(3) = 9 floats = 36 bytes per vertex.
 * Supports both procedural meshes (vertexColor = white 1,1,1) and Blender baked-AO COLOR_0 GLBs!
 */
class SimpleMesh(vertices: FloatArray) {
    val vertexCount: Int = vertices.size / 9
    private val vboId: Int

    init {
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        vboId = ids[0]

        val buf = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buf.put(vertices)
        buf.position(0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            vertices.size * 4,
            buf,
            GLES30.GL_STATIC_DRAW
        )
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    fun draw(posHandle: Int, normHandle: Int, colorHandle: Int) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId)
        GLES30.glEnableVertexAttribArray(posHandle)
        GLES30.glVertexAttribPointer(posHandle, 3, GLES30.GL_FLOAT, false, 36, 0)
        GLES30.glEnableVertexAttribArray(normHandle)
        GLES30.glVertexAttribPointer(normHandle, 3, GLES30.GL_FLOAT, false, 36, 12)
        if (colorHandle >= 0) {
            GLES30.glEnableVertexAttribArray(colorHandle)
            GLES30.glVertexAttribPointer(colorHandle, 3, GLES30.GL_FLOAT, false, 36, 24)
        }
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, vertexCount)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }
}

/**
 * Procedural 3D primitives (Box, Cylinder, Pyramid, ShadowQuad, ShadowDisc) with white vertex color (1,1,1).
 */
object ProceduralMeshes {

    private fun upgrade6To9(v6: FloatArray): FloatArray {
        val count = v6.size / 6
        val v9 = FloatArray(count * 9)
        for (i in 0 until count) {
            val s = i * 6
            val d = i * 9
            v9[d] = v6[s]; v9[d + 1] = v6[s + 1]; v9[d + 2] = v6[s + 2]
            v9[d + 3] = v6[s + 3]; v9[d + 4] = v6[s + 4]; v9[d + 5] = v6[s + 5]
            v9[d + 6] = 1f; v9[d + 7] = 1f; v9[d + 8] = 1f
        }
        return v9
    }

    fun createUnitBox(): SimpleMesh {
        val h = 0.5f
        val v = floatArrayOf(
            // Front (+Z)
            -h, -h,  h,  0f, 0f, 1f,   h, -h,  h,  0f, 0f, 1f,   h,  h,  h,  0f, 0f, 1f,
            -h, -h,  h,  0f, 0f, 1f,   h,  h,  h,  0f, 0f, 1f,  -h,  h,  h,  0f, 0f, 1f,
            // Back (-Z)
             h, -h, -h,  0f, 0f,-1f,  -h, -h, -h,  0f, 0f,-1f,  -h,  h, -h,  0f, 0f,-1f,
             h, -h, -h,  0f, 0f,-1f,  -h,  h, -h,  0f, 0f,-1f,   h,  h, -h,  0f, 0f,-1f,
            // Left (-X)
            -h, -h, -h, -1f, 0f, 0f,  -h, -h,  h, -1f, 0f, 0f,  -h,  h,  h, -1f, 0f, 0f,
            -h, -h, -h, -1f, 0f, 0f,  -h,  h,  h, -1f, 0f, 0f,  -h,  h, -h, -1f, 0f, 0f,
            // Right (+X)
             h, -h,  h,  1f, 0f, 0f,   h, -h, -h,  1f, 0f, 0f,   h,  h, -h,  1f, 0f, 0f,
             h, -h,  h,  1f, 0f, 0f,   h,  h, -h,  1f, 0f, 0f,   h,  h,  h,  1f, 0f, 0f,
            // Top (+Y)
            -h,  h,  h,  0f, 1f, 0f,   h,  h,  h,  0f, 1f, 0f,   h,  h, -h,  0f, 1f, 0f,
            -h,  h,  h,  0f, 1f, 0f,   h,  h, -h,  0f, 1f, 0f,  -h,  h, -h,  0f, 1f, 0f,
            // Bottom (-Y)
            -h, -h, -h,  0f,-1f, 0f,   h, -h, -h,  0f,-1f, 0f,   h, -h,  h,  0f,-1f, 0f,
            -h, -h, -h,  0f,-1f, 0f,   h, -h,  h,  0f,-1f, 0f,  -h, -h,  h,  0f,-1f, 0f
        )
        return SimpleMesh(upgrade6To9(v))
    }

    fun createCylinder(segments: Int = 12): SimpleMesh {
        val list = ArrayList<Float>(segments * 12 * 6)
        val r = 0.5f
        val h = 0.5f
        fun push(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float) {
            list.add(x); list.add(y); list.add(z)
            list.add(nx); list.add(ny); list.add(nz)
        }
        for (i in 0 until segments) {
            val a0 = (2.0 * PI * i / segments).toFloat()
            val a1 = (2.0 * PI * (i + 1) / segments).toFloat()
            val c0 = cos(a0); val s0 = sin(a0)
            val c1 = cos(a1); val s1 = sin(a1)
            val x0 = r * c0; val z0 = r * s0
            val x1 = r * c1; val z1 = r * s1
            push(x0, -h, z0, c0, 0f, s0); push(x1, -h, z1, c1, 0f, s1); push(x1, h, z1, c1, 0f, s1)
            push(x0, -h, z0, c0, 0f, s0); push(x1, h, z1, c1, 0f, s1); push(x0, h, z0, c0, 0f, s0)
            push(0f, h, 0f, 0f, 1f, 0f); push(x0, h, z0, 0f, 1f, 0f); push(x1, h, z1, 0f, 1f, 0f)
            push(0f, -h, 0f, 0f, -1f, 0f); push(x1, -h, z1, 0f, -1f, 0f); push(x0, -h, z0, 0f, -1f, 0f)
        }
        return SimpleMesh(upgrade6To9(list.toFloatArray()))
    }

    fun createPyramid(): SimpleMesh {
        val h = 0.5f
        val list = ArrayList<Float>(18 * 6)
        fun pushTri(
            x0: Float, y0: Float, z0: Float,
            x1: Float, y1: Float, z1: Float,
            x2: Float, y2: Float, z2: Float
        ) {
            val ux = x1 - x0; val uy = y1 - y0; val uz = z1 - z0
            val vx = x2 - x0; val vy = y2 - y0; val vz = z2 - z0
            var nx = uy * vz - uz * vy
            var ny = uz * vx - ux * vz
            var nz = ux * vy - uy * vx
            val len = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-5f)
            nx /= len; ny /= len; nz /= len
            list.add(x0); list.add(y0); list.add(z0); list.add(nx); list.add(ny); list.add(nz)
            list.add(x1); list.add(y1); list.add(z1); list.add(nx); list.add(ny); list.add(nz)
            list.add(x2); list.add(y2); list.add(z2); list.add(nx); list.add(ny); list.add(nz)
        }
        pushTri(-h, -h,  h,  h, -h,  h, 0f, h, 0f)
        pushTri( h, -h,  h,  h, -h, -h, 0f, h, 0f)
        pushTri( h, -h, -h, -h, -h, -h, 0f, h, 0f)
        pushTri(-h, -h, -h, -h, -h,  h, 0f, h, 0f)
        pushTri(-h, -h, -h,  h, -h, -h,  h, -h,  h)
        pushTri(-h, -h, -h,  h, -h,  h, -h, -h,  h)
        return SimpleMesh(upgrade6To9(list.toFloatArray()))
    }

    fun createShadowQuad(): SimpleMesh {
        val h = 0.5f
        val v = floatArrayOf(
            -h, 0f,  h, 0f, 1f, 0f,   h, 0f,  h, 0f, 1f, 0f,   h, 0f, -h, 0f, 1f, 0f,
            -h, 0f,  h, 0f, 1f, 0f,   h, 0f, -h, 0f, 1f, 0f,  -h, 0f, -h, 0f, 1f, 0f
        )
        return SimpleMesh(upgrade6To9(v))
    }

    fun createShadowDisc(segments: Int = 14): SimpleMesh {
        val list = ArrayList<Float>(segments * 3 * 6)
        val r = 0.5f
        for (i in 0 until segments) {
            val a0 = (2.0 * PI * i / segments).toFloat()
            val a1 = (2.0 * PI * (i + 1) / segments).toFloat()
            list.add(0f); list.add(0f); list.add(0f); list.add(0f); list.add(1f); list.add(0f)
            list.add(r * cos(a1)); list.add(0f); list.add(r * sin(a1)); list.add(0f); list.add(1f); list.add(0f)
            list.add(r * cos(a0)); list.add(0f); list.add(r * sin(a0)); list.add(0f); list.add(1f); list.add(0f)
        }
        return SimpleMesh(upgrade6To9(list.toFloatArray()))
    }
}
