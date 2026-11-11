package com.titipong.photocap360

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.ViewConfiguration
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 360° viewer: textured sphere seen from the inside.
 *  - Touch mode: drag to look, pinch to zoom.
 *  - Gyro mode ([gyro]): the phone is a window into the scene (rotation vector sensor); a horizontal
 *    drag still turns the heading.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class PanoViewerView(ctx: Context, private val pano: Bitmap) :
    GLSurfaceView(ctx), GLSurfaceView.Renderer, SensorEventListener {

    @Volatile private var yaw = 0f      // degrees, + = look right
    @Volatile private var pitch = 0f    // degrees, + = look up
    @Volatile private var fov = 75f

    /** Called on a short tap. */
    var onTap: (() -> Unit)? = null

    private val sensors = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotSensor: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    val hasGyro: Boolean get() = rotSensor != null

    /** Device rotation → screen-aligned rotation matrix (device→world, 3×3, row-major). */
    private val devR = FloatArray(9)
    private val scrR = FloatArray(9)
    @Volatile private var haveSensor = false
    /** Extra heading (rad, about the vertical) so gyro mode starts where the touch view was looking. */
    @Volatile private var headingOffset = 0.0
    private var needOffset = false

    var gyro = false
        set(value) {
            if (field == value) return
            field = value
            if (value && rotSensor != null) {
                haveSensor = false; needOffset = true
                sensors.registerListener(this, rotSensor, SensorManager.SENSOR_DELAY_GAME)
            } else {
                sensors.unregisterListener(this)
                if (!value) syncTouchFromGyro()
            }
            requestRender()
        }

    private var lastX = 0f; private var lastY = 0f
    private var downX = 0f; private var downY = 0f; private var moved = false
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
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

    override fun onPause() {
        super.onPause()
        sensors.unregisterListener(this)
    }

    override fun onResume() {
        super.onResume()
        if (gyro && rotSensor != null) sensors.registerListener(this, rotSensor, SensorManager.SENSOR_DELAY_GAME)
    }

    // ---------------- touch ----------------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scale.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y; downX = e.x; downY = e.y; moved = false }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(e.x - downX, e.y - downY) > slop) moved = true
                if (!scale.isInProgress && e.pointerCount == 1) {
                    val k = fov / height
                    if (gyro) {
                        headingOffset -= Math.toRadians(((e.x - lastX) * k).toDouble())
                    } else {
                        yaw -= (e.x - lastX) * k
                        pitch = (pitch + (e.y - lastY) * k).coerceIn(-89f, 89f)
                    }
                }
                lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_POINTER_DOWN -> moved = true
            MotionEvent.ACTION_POINTER_UP -> { lastX = e.x; lastY = e.y }
            MotionEvent.ACTION_UP -> if (!moved) onTap?.invoke()
        }
        requestRender()
        return true
    }

    // ---------------- sensor ----------------

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(devR, e.values)
        // Make the matrix follow the screen, not the device's natural (portrait) axes.
        @Suppress("DEPRECATION")
        val rot = display?.rotation ?: Surface.ROTATION_0
        val (ax, ay) = when (rot) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        synchronized(scrR) { SensorManager.remapCoordinateSystem(devR, ax, ay, scrR) }
        haveSensor = true
        if (needOffset) {
            // Start gyro mode looking where the touch view was looking.
            val f = gyroForward()
            headingOffset = Math.toRadians(yaw.toDouble()) - atan2(f[0], -f[2])
            needOffset = false
        }
        requestRender()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /**
     * Sensor world (X east, Y north, Z up) → sphere coords used by [buildSphere]
     * (x right, y up, −z = panorama centre): (x, y, z)_gl = (X, Z, −Y).
     * Returns the screen's viewing direction (−Z of the screen) in sphere coords, before the heading offset.
     */
    private fun gyroForward(): DoubleArray {
        synchronized(scrR) {
            val wx = -scrR[2].toDouble(); val wy = -scrR[5].toDouble(); val wz = -scrR[8].toDouble()
            return doubleArrayOf(wx, wz, -wy)
        }
    }

    private fun gyroUp(): DoubleArray {
        synchronized(scrR) {
            val wx = scrR[1].toDouble(); val wy = scrR[4].toDouble(); val wz = scrR[7].toDouble()
            return doubleArrayOf(wx, wz, -wy)
        }
    }

    /** Rotate about the vertical (sphere y) axis by the heading offset. */
    private fun turn(v: DoubleArray): DoubleArray {
        val c = cos(headingOffset); val s = sin(headingOffset)
        // yaw convention: positive = look right = towards +x from −z
        return doubleArrayOf(c * v[0] - s * v[2], v[1], s * v[0] + c * v[2])
    }

    /** Leaving gyro mode: keep looking in the same direction with touch controls. */
    private fun syncTouchFromGyro() {
        if (!haveSensor) return
        val f = turn(gyroForward())
        yaw = Math.toDegrees(atan2(f[0], -f[2])).toFloat()
        pitch = Math.toDegrees(atan2(f[1], hypot(f[0], f[2]))).toFloat().coerceIn(-89f, 89f)
    }

    // ---------------- GL ----------------

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
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        if (gyro && haveSensor) {
            val f = turn(gyroForward()); val u = turn(gyroUp())
            Matrix.setLookAtM(view, 0, 0f, 0f, 0f, f[0].toFloat(), f[1].toFloat(), f[2].toFloat(),
                u[0].toFloat(), u[1].toFloat(), u[2].toFloat())
        } else {
            val y = Math.toRadians(yaw.toDouble()); val p = Math.toRadians(pitch.toDouble())
            val cx = (cos(p) * sin(y)).toFloat(); val cy = sin(p).toFloat(); val cz = (-cos(p) * cos(y)).toFloat()
            Matrix.setLookAtM(view, 0, 0f, 0f, 0f, cx, cy, cz, 0f, 1f, 0f)
        }

        val aspect = width.toFloat() / max(1, height)
        Matrix.perspectiveM(proj, 0, fov, aspect, 0.1f, 10f)
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)
        drawSphere()
    }

    private fun drawSphere() {
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
