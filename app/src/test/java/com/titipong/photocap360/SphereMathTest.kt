package com.titipong.photocap360

import com.titipong.photocap360.SphereMath.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

class SphereMathTest {

    /** Rotation (device → world) for a phone held upright, back camera looking at (yaw, pitch), with roll. */
    internal fun poseFor(yaw: Double, pitch: Double, roll: Double = 0.0): FloatArray {
        val f = SphereMath.dirFromYawPitch(yaw, pitch)
        val worldUp = Vec3(0.0, 0.0, 1.0)
        var up = worldUp - f * f.dot(worldUp)
        if (up.norm() < 1e-6) up = SphereMath.dirFromYawPitch(yaw, 0.0) * (if (pitch > 0) -1.0 else 1.0)
        up = up.normalized()
        val z = f * -1.0
        var x = up.cross(z).normalized()
        var y = up
        if (roll != 0.0) {
            val nx = x * cos(roll) + y * sin(roll)
            val ny = y * cos(roll) - x * sin(roll)
            x = nx; y = ny
        }
        return floatArrayOf(
            x.x.toFloat(), y.x.toFloat(), z.x.toFloat(),
            x.y.toFloat(), y.y.toFloat(), z.y.toFloat(),
            x.z.toFloat(), y.z.toFloat(), z.z.toFloat(),
        )
    }

    @Test fun forwardAndProjectionCentre() {
        val r = poseFor(0.7, 0.3)
        val f = SphereMath.cameraForward(r)
        assertEquals(0.7, SphereMath.yawOf(f), 1e-5)
        assertEquals(0.3, SphereMath.pitchOf(f), 1e-5)
        val p = SphereMath.project(r, f, 0.5, 0.6)!!
        assertEquals(0.0, p[0], 1e-6); assertEquals(0.0, p[1], 1e-6)
    }

    @Test fun rightOfCameraProjectsRight_upProjectsUp() {
        val r = poseFor(0.0, 0.0)
        val right = SphereMath.project(r, SphereMath.dirFromYawPitch(0.1, 0.0), 0.5, 0.6)!!
        assertTrue("turning right → +x", right[0] > 0)
        val up = SphereMath.project(r, SphereMath.dirFromYawPitch(0.0, 0.1), 0.5, 0.6)!!
        assertTrue("looking up → +y", up[1] > 0)
    }

    @Test fun rollIsMeasured() {
        assertEquals(0.0, SphereMath.rollError(poseFor(1.0, 0.2)), 1e-4)
        assertEquals(0.2, SphereMath.rollError(poseFor(1.0, 0.2, 0.2)), 1e-4)
    }

    @Test fun targetLayoutForTypicalPhone() {
        val t = SphereMath.buildTargets(50.0, 65.0, 0.35)
        val byRow = t.groupBy { it.row }.mapValues { it.value.size }
        assertEquals(12, byRow[0])          // horizon: ceil(360 / (50·0.65)) = 12
        assertEquals(1, t.count { it.pitch > 89 * SphereMath.DEG })
        assertEquals(1, t.count { it.pitch < -89 * SphereMath.DEG })
        assertEquals(32, t.size)            // 12 + 9 + 1 + 9 + 1
    }

    @Test fun targetsCoverWholeSphere() {
        val h = 50.0; val v = 65.0
        val th = SphereMath.tanHalf(h); val tv = SphereMath.tanHalf(v)
        val poses = SphereMath.buildTargets(h, v, 0.35).map { poseFor(it.yaw, it.pitch) }
        var missing = 0
        for (i in 0 until 2000) {
            // Fibonacci sphere sample
            val z = 1 - 2 * (i + 0.5) / 2000
            val a = i * PI * (3 - Math.sqrt(5.0))
            val rr = Math.sqrt(1 - z * z)
            val d = Vec3(rr * cos(a), rr * sin(a), z)
            val seen = poses.any { p ->
                val q = SphereMath.project(p, d, th, tv); q != null && abs(q[0]) < 1 && abs(q[1]) < 1
            }
            if (!seen) missing++
        }
        assertEquals("every direction is seen by some frame", 0, missing)
    }

    @Test fun cropTanKeepsAspect() {
        val (l, s) = SphereMath.cropTan(0.8, 0.45, 4.0 / 3.0) // 16:9 sensor → 4:3 image crops long side
        assertEquals(4.0 / 3.0, l / s, 1e-9); assertEquals(0.45, s, 1e-9)
    }

    /** End-to-end: render synthetic frames of a known scene, stitch, compare with ground truth. */
    @Test fun stitchReproducesScene() {
        fun scene(d: Vec3): Int {
            val r = ((d.x + 1) * 127.5).toInt(); val g = ((d.y + 1) * 127.5).toInt(); val b = ((d.z + 1) * 127.5).toInt()
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val hFov = 50.0; val vFov = 65.0
        val th = SphereMath.tanHalf(hFov); val tv = SphereMath.tanHalf(vFov)
        val w = 90; val h = 120
        val yaw0 = 0.4
        val frames = SphereMath.buildTargets(hFov, vFov, 0.35).map { t ->
            val r = poseFor(yaw0 + t.yaw, t.pitch, 0.05)
            val px = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                val nx = (x + 0.5) / w * 2 - 1
                val ny = 1 - (y + 0.5) / h * 2
                px[y * w + x] = scene(SphereMath.rotate(r, Vec3(nx * th, ny * tv, -1.0)).normalized())
            }
            FrameImage(px, w, h, r, th, tv)
        }
        val ow = 256; val oh = 128
        val out = IntArray(ow * oh)
        StitchCore(frames, yaw0).renderRows(ow, oh, 0, oh, out)
        var maxErr = 0
        for (v in 0 until oh) for (u in 0 until ow) {
            val lon = (u + 0.5) / ow * 2 * PI - PI
            val lat = PI / 2 - (v + 0.5) / oh * PI
            val c = out[v * ow + u]
            assertTrue("pixel ($u,$v) covered", c ushr 24 == 0xFF)
            val e = scene(SphereMath.dirFromYawPitch(lon + yaw0, lat))
            for (s in intArrayOf(0, 8, 16)) maxErr = max(maxErr, abs(((c shr s) and 0xFF) - ((e shr s) and 0xFF)))
        }
        assertTrue("max colour error $maxErr", maxErr <= 6)

        // Panorama centre column looks where the first shot was taken.
        val centre = SphereMath.dirFromYawPitch(0.0 + yaw0, 0.0)
        assertEquals(yaw0, atan2(centre.x, centre.y), 1e-9)
    }

    @Test fun xmpInjection() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        val out = PanoSaver.injectXmp(jpeg, 4096, 2048)
        assertEquals(0xFF.toByte(), out[2]); assertEquals(0xE1.toByte(), out[3])
        val len = ((out[4].toInt() and 0xFF) shl 8) or (out[5].toInt() and 0xFF)
        assertEquals(out.size - 4 - 2, len) // segment covers everything except SOI, marker and EOI
        assertNotNull(String(out, Charsets.UTF_8).indexOf("equirectangular").takeIf { it > 0 })
    }
}
