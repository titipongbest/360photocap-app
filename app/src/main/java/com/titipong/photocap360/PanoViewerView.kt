package com.titipong.photocap360

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Minimal 360° viewer: textured sphere seen from the inside, drag to look, pinch to zoom. */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class PanoViewerView(ctx: Context, private val pano: Bitmap) : GLSurfaceView(ctx), GLSurfaceView.Renderer {

    @Volatile private var yaw = 0f      // degrees, + = look right
    @Volatile private var pitch = 0f    // degrees, + = look up
    @Volatile private var fov = 75f

    private var lastX = 0f; private var lastY = 0f
    private val scale = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            fov = (fov / d.scaleFactor).coerceIn(30f, 110f); return true
        }
    })

    private var program = 0
    private var tex = 0
    private lateinit var vbuf: FloatBuffer
    private lateinit var ibuf: ShortBuffer
    private var indexCount = 0
    private val proj = FloatArray(16); private val view = FloatArray(16); private val mvp = FloatArray(16)

    init {
        setEGLContextClientVersion(2)
        setRenderer(this)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scale.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y }
            MotionEvent.ACTION_MOVE -> if (!scale.isInProgress && e.pointerCount == 1) {
                val k = fov / height
                yaw -= (e.x - lastX) * k
                pitch = (pitch + (e.y - lastY) * k).coerceIn(-89f, 89f)
                lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_POINTER_UP -> { lastX = e.x; lastY = e.y }
        }
        requestRender()
        return true
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        buildSphere(48, 96)
        program = link(
            """
            uniform mat4 uMvp; attribute vec3 aPos; attribute vec2 aUv; varying vec2 vUv;
            void main() { vUv = aUv; gl_Position = uMvp * vec4(aPos, 1.0); }
            """.trimIndent(),
            """
            precision mediump float; uniform sampler2D uTex; varying vec2 vUv;
            void main() { gl_FragColor = texture2D(uTex, vUv); }
            """.trimIndent(),
        )
        val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0); tex = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val maxSize = IntArray(1); GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxSize, 0)
        val limit = if (maxSize[0] > 0) min(maxSize[0], 4096) else 2048
        val texBmp = if (pano.width > limit) Bitmap.createScaledBitmap(pano, limit, limit / 2, true) else pano
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, texBmp, 0)
        if (texBmp !== pano) texBmp.recycle()
        GLES20.glClearColor(0f, 0f, 0f, 1f)
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) = GLES20.glViewport(0, 0, w, h)

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val aspect = width.toFloat() / max(1, height)
        Matrix.perspectiveM(proj, 0, fov, aspect, 0.1f, 10f)
        val y = Math.toRadians(yaw.toDouble()); val p = Math.toRadians(pitch.toDouble())
        val cx = (cos(p) * sin(y)).toFloat(); val cy = sin(p).toFloat(); val cz = (-cos(p) * cos(y)).toFloat()
        Matrix.setLookAtM(view, 0, 0f, 0f, 0f, cx, cy, cz, 0f, 1f, 0f)
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMvp"), 1, false, mvp, 0)
        val aPos = GLES20.glGetAttribLocation(program, "aPos")
        val aUv = GLES20.glGetAttribLocation(program, "aUv")
        vbuf.position(0)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 20, vbuf)
        GLES20.glEnableVertexAttribArray(aPos)
        vbuf.position(3)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 20, vbuf)
        GLES20.glEnableVertexAttribArray(aUv)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        ibuf.position(0)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, ibuf)
    }

    /**
     * Same mapping as the stitcher: u = (lon + π)/2π, v = (π/2 − lat)/π.
     * GL view space: x right, y up, −z forward ⇒ position = (cos lat · sin lon, sin lat, −cos lat · cos lon).
     */
    private fun buildSphere(stacks: Int, slices: Int) {
        val verts = FloatArray((stacks + 1) * (slices + 1) * 5)
        var k = 0
        for (i in 0..stacks) {
            val v = i.toFloat() / stacks
            val lat = PI / 2 - v * PI
            for (j in 0..slices) {
                val u = j.toFloat() / slices
                val lon = u * 2 * PI - PI
                verts[k++] = (cos(lat) * sin(lon)).toFloat()
                verts[k++] = sin(lat).toFloat()
                verts[k++] = (-cos(lat) * cos(lon)).toFloat()
                verts[k++] = u
                verts[k++] = v
            }
        }
        val idx = ShortArray(stacks * slices * 6)
        k = 0
        for (i in 0 until stacks) for (j in 0 until slices) {
            val a = (i * (slices + 1) + j).toShort()
            val b = (a + slices + 1).toShort()
            idx[k++] = a; idx[k++] = b; idx[k++] = (a + 1).toShort()
            idx[k++] = (a + 1).toShort(); idx[k++] = b; idx[k++] = (b + 1).toShort()
        }
        indexCount = idx.size
        vbuf = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(verts)
        ibuf = ByteBuffer.allocateDirect(idx.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().put(idx)
    }

    private fun link(vs: String, fs: String): Int {
        fun compile(type: Int, src: String) = GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, src); GLES20.glCompileShader(it)
        }
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, compile(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(it, compile(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(it)
        }
    }
}
