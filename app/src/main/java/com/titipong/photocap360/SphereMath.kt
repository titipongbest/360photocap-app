package com.titipong.photocap360

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Coordinate conventions (same as Android SensorManager):
 *  - World:  X = east-ish, Y = north-ish (arbitrary heading for game rotation vector), Z = up.
 *  - Device (portrait): X = right, Y = top of phone, Z = out of the screen toward the user.
 *  - The back camera looks along device -Z. In a portrait photo, image-right = +X, image-up = +Y.
 *  - R is a row-major 3x3 matrix mapping device vectors to world vectors: w = R · d.
 */
object SphereMath {

    const val DEG = (PI / 180.0)

    data class Vec3(val x: Double, val y: Double, val z: Double) {
        operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
        operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
        operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
        fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
        fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
        fun norm() = sqrt(dot(this))
        fun normalized(): Vec3 { val n = norm(); return if (n < 1e-12) this else this * (1.0 / n) }
    }

    /** World direction for heading [yaw] (rad, clockwise from +Y seen from above) and [pitch] (rad, up positive). */
    fun dirFromYawPitch(yaw: Double, pitch: Double): Vec3 {
        val c = cos(pitch)
        return Vec3(c * sin(yaw), c * cos(yaw), sin(pitch))
    }

    /** w = R · d */
    fun rotate(r: FloatArray, d: Vec3) = Vec3(
        r[0] * d.x + r[1] * d.y + r[2] * d.z,
        r[3] * d.x + r[4] * d.y + r[5] * d.z,
        r[6] * d.x + r[7] * d.y + r[8] * d.z,
    )

    /** d = Rᵀ · w  (world → device) */
    fun rotateT(r: FloatArray, w: Vec3) = Vec3(
        r[0] * w.x + r[3] * w.y + r[6] * w.z,
        r[1] * w.x + r[4] * w.y + r[7] * w.z,
        r[2] * w.x + r[5] * w.y + r[8] * w.z,
    )

    /** Direction the back camera looks at, in world coords. */
    fun cameraForward(r: FloatArray) = Vec3(-r[2].toDouble(), -r[5].toDouble(), -r[8].toDouble())

    /** Top of the phone (image up), in world coords. */
    fun cameraUp(r: FloatArray) = Vec3(r[1].toDouble(), r[4].toDouble(), r[7].toDouble())

    fun yawOf(v: Vec3) = atan2(v.x, v.y)
    fun pitchOf(v: Vec3) = atan2(v.z, sqrt(v.x * v.x + v.y * v.y))

    fun angleBetween(a: Vec3, b: Vec3): Double {
        val c = a.normalized().dot(b.normalized()).coerceIn(-1.0, 1.0)
        return acos(c)
    }

    /**
     * Roll error (rad) – how far the phone is tilted sideways from upright.
     * Undefined near zenith/nadir, returns 0 there.
     */
    fun rollError(r: FloatArray): Double {
        val f = cameraForward(r)
        if (abs(f.z) > 0.96) return 0.0
        val worldUp = Vec3(0.0, 0.0, 1.0)
        val desiredUp = (worldUp - f * f.dot(worldUp)).normalized()
        return angleBetween(cameraUp(r), desiredUp)
    }

    /**
     * Pinhole projection of a world direction into the camera image.
     * Returns normalized image coords (nx, ny) in [-1, 1] when inside the frame
     * (nx right, ny up) or null when behind the camera.
     * [tanHalfH]/[tanHalfV] = tan(FOV/2) of the portrait image horizontally/vertically.
     */
    fun project(r: FloatArray, w: Vec3, tanHalfH: Double, tanHalfV: Double): DoubleArray? {
        val p = rotateT(r, w)
        val depth = -p.z
        if (depth <= 1e-6) return null
        return doubleArrayOf((p.x / depth) / tanHalfH, (p.y / depth) / tanHalfV)
    }

    // ---------------- Target layout ----------------

    data class Target(val index: Int, val row: Int, val yaw: Double, val pitch: Double)

    /**
     * Places capture targets on the sphere.
     *
     *  - Row pitch step   = vFov · (1 − overlap)
     *  - Shots per row    N = ceil(360° · cos φ / (hFov · (1 − overlap)))
     *  - The zenith/nadir caps are one shot each; rows are spread so their outer edge
     *    meets the cap with [overlap].
     */
    fun buildTargets(
        hFovDeg: Double,
        vFovDeg: Double,
        overlap: Double = 0.35,
        includeNadir: Boolean = true,
        /** Tilt the downward rows this much further down (covers more floor when there's no nadir shot). */
        lowerExtraDeg: Double = 0.0,
    ): List<Target> {
        val step = vFovDeg * (1 - overlap)
        // Upper edge rows must reach before the zenith shot takes over.
        val capRadius = hFovDeg / 2.0                // narrow side of the zenith frame
        val reach = 90.0 - capRadius * (1 - overlap) // highest latitude rows must cover
        val extraRows = max(0, ceil((reach - vFovDeg / 2.0) / step).toInt())
        val topPitch = if (extraRows == 0) 0.0 else reach - vFovDeg / 2.0

        val pitchesUp = (1..extraRows).map { topPitch * it / extraRows }
        val order = mutableListOf<Pair<Int, Double>>() // (rowId, pitch) in shooting order
        order += 0 to 0.0
        pitchesUp.forEachIndexed { i, p -> order += (i + 1) to p }
        order += (extraRows + 1) to 90.0
        pitchesUp.forEachIndexed { i, p -> order += (extraRows + 2 + i) to -(p + lowerExtraDeg) }
        if (includeNadir) order += (2 * extraRows + 2) to -90.0

        val out = mutableListOf<Target>()
        for ((rowId, pitchDeg) in order) {
            if (abs(pitchDeg) >= 89.9) {
                out += Target(out.size, rowId, 0.0, pitchDeg * DEG)
                continue
            }
            val n = max(3, ceil(360.0 * cos(pitchDeg * DEG) / (hFovDeg * (1 - overlap))).toInt())
            // Offset alternate rows by half a step so seams don't line up.
            val offset = if (rowId % 2 == 1) 0.5 else 0.0
            for (k in 0 until n) {
                val yawDeg = (k + offset) * 360.0 / n
                out += Target(out.size, rowId, wrapPi(yawDeg * DEG), pitchDeg * DEG)
            }
        }
        return out
    }

    fun wrapPi(a: Double): Double {
        var x = a
        while (x > PI) x -= 2 * PI
        while (x < -PI) x += 2 * PI
        return x
    }

    /**
     * When the delivered image aspect differs from the sensor, one axis is cropped.
     * Given the sensor's tan(half FOV) on the long/short axes and the image aspect
     * (long/short), returns the effective (tanLong, tanShort).
     */
    fun cropTan(tanLong: Double, tanShort: Double, imageAspect: Double): Pair<Double, Double> {
        val sensorAspect = tanLong / tanShort
        return if (imageAspect < sensorAspect) {
            tanShort * imageAspect to tanShort  // long axis cropped
        } else {
            tanLong to tanLong / imageAspect   // short axis cropped
        }
    }

    fun fovDeg(tanHalf: Double) = 2 * atan(tanHalf) / DEG
    fun tanHalf(fovDeg: Double) = tan(fovDeg * DEG / 2)
}
