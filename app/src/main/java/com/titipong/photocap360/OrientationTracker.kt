package com.titipong.photocap360

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Gyroscope-based orientation.
 *
 * Mode A (preferred): TYPE_GAME_ROTATION_VECTOR – the OS fuses gyroscope + accelerometer,
 *   with NO magnetometer, so it is not disturbed by steel desks / cabinets indoors.
 * Mode B (fallback): integrate raw TYPE_GYROSCOPE ourselves (quaternion), with a small
 *   Mahony-style accelerometer correction to remove pitch/roll drift.
 *
 * Output: rotation matrix R (row-major, device → world) and angular speed |ω| (rad/s).
 */
class OrientationTracker(private val sm: SensorManager) : SensorEventListener {

    private val gameRv = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    val available: Boolean get() = gyro != null
    val usingRawGyro: Boolean get() = gameRv == null

    private val lock = Any()
    private val r = FloatArray(9).also { it[0] = 1f; it[4] = 1f; it[8] = 1f }
    @Volatile var hasOrientation = false; private set
    @Volatile var angularSpeed = 0.0; private set

    var listener: (() -> Unit)? = null

    // Fallback filter state: quaternion (w, x, y, z), device → world
    private var qw = 1.0; private var qx = 0.0; private var qy = 0.0; private var qz = 0.0
    private var lastGyroNs = 0L
    private val lastAccel = DoubleArray(3)
    private var haveAccel = false

    fun start() {
        hasOrientation = false
        lastGyroNs = 0L
        gyro?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        if (gameRv != null) {
            sm.registerListener(this, gameRv, SensorManager.SENSOR_DELAY_GAME)
        } else {
            accel?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        }
    }

    fun stop() = sm.unregisterListener(this)

    /** Copy of the current rotation matrix. */
    fun rotation(): FloatArray = synchronized(lock) { r.copyOf() }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                synchronized(lock) { SensorManager.getRotationMatrixFromVector(r, e.values) }
                hasOrientation = true
                listener?.invoke()
            }
            Sensor.TYPE_GYROSCOPE -> {
                val wx = e.values[0].toDouble(); val wy = e.values[1].toDouble(); val wz = e.values[2].toDouble()
                angularSpeed = sqrt(wx * wx + wy * wy + wz * wz)
                if (usingRawGyro) integrateGyro(wx, wy, wz, e.timestamp)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                lastAccel[0] = e.values[0].toDouble()
                lastAccel[1] = e.values[1].toDouble()
                lastAccel[2] = e.values[2].toDouble()
                if (!haveAccel) { haveAccel = true; initFromGravity() }
            }
        }
    }

    // ---------------- Fallback: raw gyro integration ----------------

    private fun initFromGravity() {
        val (ax, ay, az) = Triple(lastAccel[0], lastAccel[1], lastAccel[2])
        val n = sqrt(ax * ax + ay * ay + az * az)
        if (n < 1e-3) return
        // Row 2 of R = world up expressed in device coords (accelerometer at rest points up).
        val up = SphereMath.Vec3(ax / n, ay / n, az / n)
        var fwd = SphereMath.Vec3(0.0, 0.0, -1.0)
        if (abs(fwd.dot(up)) > 0.95) fwd = SphereMath.Vec3(0.0, 1.0, 0.0)
        val north = (fwd - up * fwd.dot(up)).normalized()  // row 1
        val east = north.cross(up)                          // row 0 (X = Y × Z)
        val m = doubleArrayOf(east.x, east.y, east.z, north.x, north.y, north.z, up.x, up.y, up.z)
        matToQuat(m)
        publishQuat()
    }

    private fun integrateGyro(wx0: Double, wy0: Double, wz0: Double, ts: Long) {
        if (!haveAccel) return
        if (lastGyroNs == 0L) { lastGyroNs = ts; return }
        val dt = (ts - lastGyroNs) * 1e-9
        lastGyroNs = ts
        if (dt <= 0 || dt > 0.2) return

        var wx = wx0; var wy = wy0; var wz = wz0
        // Mahony correction: estimated "up" in device = Rᵀ·(0,0,1) = third row of R.
        val ax = lastAccel[0]; val ay = lastAccel[1]; val az = lastAccel[2]
        val an = sqrt(ax * ax + ay * ay + az * az)
        if (an > 8.0 && an < 11.6) { // only trust accel when ~1 g (not swinging)
            val mx = ax / an; val my = ay / an; val mz = az / an
            val vx = 2 * (qx * qz - qw * qy)
            val vy = 2 * (qy * qz + qw * qx)
            val vz = qw * qw - qx * qx - qy * qy + qz * qz
            val ex = my * vz - mz * vy
            val ey = mz * vx - mx * vz
            val ez = mx * vy - my * vx
            val kp = 0.6
            wx += kp * ex; wy += kp * ey; wz += kp * ez
        }

        // q ← q ⊗ exp(ω·dt/2)   (ω in device frame, q maps device → world)
        val wn = sqrt(wx * wx + wy * wy + wz * wz)
        val half = wn * dt / 2
        val s = if (wn > 1e-9) sin(half) / wn else dt / 2
        val dw = cos(half); val dx = wx * s; val dy = wy * s; val dz = wz * s
        val nw = qw * dw - qx * dx - qy * dy - qz * dz
        val nx = qw * dx + qx * dw + qy * dz - qz * dy
        val ny = qw * dy - qx * dz + qy * dw + qz * dx
        val nz = qw * dz + qx * dy - qy * dx + qz * dw
        val n = sqrt(nw * nw + nx * nx + ny * ny + nz * nz)
        qw = nw / n; qx = nx / n; qy = ny / n; qz = nz / n
        publishQuat()
    }

    private fun publishQuat() {
        synchronized(lock) {
            r[0] = (1 - 2 * (qy * qy + qz * qz)).toFloat()
            r[1] = (2 * (qx * qy - qw * qz)).toFloat()
            r[2] = (2 * (qx * qz + qw * qy)).toFloat()
            r[3] = (2 * (qx * qy + qw * qz)).toFloat()
            r[4] = (1 - 2 * (qx * qx + qz * qz)).toFloat()
            r[5] = (2 * (qy * qz - qw * qx)).toFloat()
            r[6] = (2 * (qx * qz - qw * qy)).toFloat()
            r[7] = (2 * (qy * qz + qw * qx)).toFloat()
            r[8] = (1 - 2 * (qx * qx + qy * qy)).toFloat()
        }
        hasOrientation = true
        listener?.invoke()
    }

    private fun matToQuat(m: DoubleArray) {
        val tr = m[0] + m[4] + m[8]
        if (tr > 0) {
            val s = sqrt(tr + 1.0) * 2
            qw = 0.25 * s; qx = (m[7] - m[5]) / s; qy = (m[2] - m[6]) / s; qz = (m[3] - m[1]) / s
        } else if (m[0] > m[4] && m[0] > m[8]) {
            val s = sqrt(1.0 + m[0] - m[4] - m[8]) * 2
            qw = (m[7] - m[5]) / s; qx = 0.25 * s; qy = (m[1] + m[3]) / s; qz = (m[2] + m[6]) / s
        } else if (m[4] > m[8]) {
            val s = sqrt(1.0 + m[4] - m[0] - m[8]) * 2
            qw = (m[2] - m[6]) / s; qx = (m[1] + m[3]) / s; qy = 0.25 * s; qz = (m[5] + m[7]) / s
        } else {
            val s = sqrt(1.0 + m[8] - m[0] - m[4]) * 2
            qw = (m[3] - m[1]) / s; qx = (m[2] + m[6]) / s; qy = (m[5] + m[7]) / s; qz = 0.25 * s
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
