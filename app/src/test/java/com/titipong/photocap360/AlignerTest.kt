package com.titipong.photocap360

import com.titipong.photocap360.SphereMath.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

class AlignerTest {

    private val deg = PI / 180

    // ---- synthetic textured world: 2-octave 3D value noise on the unit sphere
    private fun hash(x: Int, y: Int, z: Int): Double {
        var h = x * 374761393 + y * 668265263 + z * 1274126177
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFFFF) / 16777215.0
    }

    private fun noise(x: Double, y: Double, z: Double): Double {
        val xi = floor(x).toInt(); val yi = floor(y).toInt(); val zi = floor(z).toInt()
        fun s(t: Double) = t * t * (3 - 2 * t)
        val fx = s(x - xi); val fy = s(y - yi); val fz = s(z - zi)
        fun l(a: Double, b: Double, t: Double) = a + (b - a) * t
        val x00 = l(hash(xi, yi, zi), hash(xi + 1, yi, zi), fx)
        val x10 = l(hash(xi, yi + 1, zi), hash(xi + 1, yi + 1, zi), fx)
        val x01 = l(hash(xi, yi, zi + 1), hash(xi + 1, yi, zi + 1), fx)
        val x11 = l(hash(xi, yi + 1, zi + 1), hash(xi + 1, yi + 1, zi + 1), fx)
        return l(l(x00, x10, fy), l(x01, x11, fy), fz)
    }

    private fun scene(d: Vec3) =
        0.1 + 0.55 * noise(d.x * 14 + 50, d.y * 14 + 50, d.z * 14 + 50) +
            0.35 * noise(d.x * 40 + 99, d.y * 40 + 99, d.z * 40 + 99)

    private fun gray(v: Double): Int {
        val g = (v * 255).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (g shl 16) or (g shl 8) or g
    }

    private fun render(r: FloatArray, th: Double, tv: Double, w: Int, h: Int): IntArray {
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val nx = (x + 0.5) / w * 2 - 1
            val ny = 1 - (y + 0.5) / h * 2
            px[y * w + x] = gray(scene(SphereMath.rotate(r, Vec3(nx * th, ny * tv, -1.0)).normalized()))
        }
        return px
    }

    private class Setup(val truth: List<FloatArray>, val frames: List<FrameImage>, val k: Double, val yaw0: Double)

    private fun setup(): Setup {
        val hFov = 50.0; val vFov = 65.0
        val th = SphereMath.tanHalf(hFov); val tv = SphereMath.tanHalf(vFov)
        val rnd = Random(7)
        val yaw0 = 0.3
        val poses = SphereMathTest()
        val truth = SphereMath.buildTargets(hFov, vFov, 0.35).map {
            poses.poseFor(yaw0 + it.yaw, it.pitch, (rnd.nextDouble() - 0.5) * 0.06)
        }
        val k = 1.03 // the FOV we "believe" is 3 % too wide
        val w = 300; val h = 400
        val frames = truth.map { t ->
            val px = render(t, th, tv, w, h)
            // gyro error: random axis, up to 2.5°
            var ax = rnd.nextGaussian(); var ay = rnd.nextGaussian(); var az = rnd.nextGaussian()
            val nrm = sqrt(ax * ax + ay * ay + az * az)
            val ang = rnd.nextDouble() * 2.5 * deg
            ax *= ang / nrm; ay *= ang / nrm; az *= ang / nrm
            val pert = Rot.mul(Rot.exp(ax, ay, az), Rot.ofFloat(t))
            FrameImage(px, w, h, Rot.toFloat(pert), th * k, tv * k)
        }
        return Setup(truth, frames, k, yaw0)
    }

    /** Worst error of relative rotations between overlapping frames (independent of global rotation). */
    private fun maxRelErrDeg(truth: List<FloatArray>, est: List<FloatArray>): Double {
        var worst = 0.0
        for (i in truth.indices) for (j in i + 1 until truth.size) {
            val ti = Rot.ofFloat(truth[i]); val tj = Rot.ofFloat(truth[j])
            val fwdDot = ti[2] * tj[2] + ti[5] * tj[5] + ti[8] * tj[8]
            if (fwdDot < kotlin.math.cos(60 * deg)) continue
            val tRel = Rot.mul(Rot.t(ti), tj)
            val eRel = Rot.mul(Rot.t(Rot.ofFloat(est[i])), Rot.ofFloat(est[j]))
            worst = max(worst, Rot.angle(Rot.mul(eRel, Rot.t(tRel))))
        }
        return worst / deg
    }

    @Test fun recoversRotationsAndFov() {
        val s = setup()
        val before = maxRelErrDeg(s.truth, s.frames.map { it.r })
        val res = Aligner(s.frames, estimateFov = true).run()
        val after = maxRelErrDeg(s.truth, res.rotations)
        println("STAT align matches=${res.matches} inliers=${res.inliers} rms=%.4f° scale·k=%.5f relErr before=%.3f° after=%.3f°"
            .format(res.rmsDeg, res.fovScale * s.k, before, after))
        assertTrue("alignment applied", res.applied)
        assertEquals("FOV recovered", 1.0, res.fovScale * s.k, 0.005)
        assertTrue("relative rotation error $after° (was $before°)", after < 0.15)
    }

    @Test fun alignedStitchHasLessGhosting() {
        val s = setup()
        val res = Aligner(s.frames, estimateFov = true).run()
        val aligned = s.frames.mapIndexed { i, f ->
            FrameImage(f.pixels, f.width, f.height, res.rotations[i], f.tanH * res.fovScale, f.tanV * res.fovScale)
        }
        // Ghosting = two frames disagreeing about the same direction.
        // Measure mean |difference| between two overlapping frames over the sphere.
        fun seamError(fr: List<FrameImage>): Double {
            var sum = 0.0; var cnt = 0
            val ow = 360; val oh = 180
            for (v in 10 until oh - 10 step 2) for (u in 0 until ow step 2) {
                val lon = (u + 0.5) / ow * 2 * PI - PI
                val lat = PI / 2 - (v + 0.5) / oh * PI
                val d = SphereMath.dirFromYawPitch(lon + s.yaw0, lat)
                val vals = ArrayList<Double>()
                for (f in fr) {
                    val q = SphereMath.project(f.r, d, f.tanH, f.tanV) ?: continue
                    if (abs(q[0]) > 0.9 || abs(q[1]) > 0.9) continue
                    val x = ((q[0] + 1) / 2 * f.width - 0.5).toInt().coerceIn(0, f.width - 1)
                    val y = ((1 - q[1]) / 2 * f.height - 0.5).toInt().coerceIn(0, f.height - 1)
                    vals += (f.pixels[y * f.width + x] and 0xFF).toDouble()
                }
                if (vals.size >= 2) { sum += abs(vals[0] - vals[1]); cnt++ }
            }
            return sum / cnt
        }
        val eBefore = seamError(s.frames)
        val eAfter = seamError(aligned)
        println("STAT seam mean|Δ| before=%.2f after=%.2f (0-255)".format(eBefore, eAfter))
        assertTrue("seam error $eAfter vs $eBefore", eAfter < eBefore * 0.4)
    }

    @Test fun fovStaysFixedByDefault() {
        val s = setup()
        val res = Aligner(s.frames).run()
        println("STAT fixed-FOV align applied=${res.applied} rms=%.3f° scale=%.5f".format(res.rmsDeg, res.fovScale))
        assertEquals(1.0, res.fovScale, 1e-3)
    }
}
