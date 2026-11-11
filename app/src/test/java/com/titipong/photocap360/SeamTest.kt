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
import kotlin.math.min
import kotlin.math.sqrt

private object TestScene {
    fun hash(x: Int, y: Int, z: Int): Double {
        var h = x * 374761393 + y * 668265263 + z * 1274126177
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFFFF) / 16777215.0
    }

    fun noise(x: Double, y: Double, z: Double): Double {
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

    /** Distant, richly textured background. Values stay ≤ 0.8 so gains don't clip. */
    fun background(d: Vec3) = 0.8 * (0.1 + 0.55 * noise(d.x * 14 + 50, d.y * 14 + 50, d.z * 14 + 50) +
        0.35 * noise(d.x * 40 + 99, d.y * 40 + 99, d.z * 40 + 99))

    /** "Furniture": blobs on a sphere around the rotation centre, null where there is none. */
    fun foreground(d: Vec3): Double? {
        val m = noise(d.x * 3 + 11, d.y * 3 + 11, d.z * 3 + 11)
        return if (m > 0.62) 0.8 * (0.15 + 0.7 * noise(d.x * 22 + 5, d.y * 22 + 5, d.z * 22 + 5)) else null
    }

    fun gray(v: Double, gain: Double = 1.0): Int {
        val g = (v * gain * 255).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (g shl 16) or (g shl 8) or g
    }

    /**
     * Frame rendered from a camera that sits [camOffset] metres in front of the rotation centre.
     * Foreground blobs live on a sphere of radius [rho] around the centre → parallax between frames.
     */
    fun render(
        r: FloatArray, th: Double, tv: Double, w: Int, h: Int,
        gain: Double = 1.0, camOffset: Double = 0.0, rho: Double = 1.0,
    ): IntArray {
        val c = SphereMath.cameraForward(r) * camOffset
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val nx = (x + 0.5) / w * 2 - 1
            val ny = 1 - (y + 0.5) / h * 2
            val d = SphereMath.rotate(r, Vec3(nx * th, ny * tv, -1.0)).normalized()
            var v: Double? = null
            if (camOffset > 0) {
                val cd = c.dot(d)
                val t = -cd + sqrt(cd * cd - c.dot(c) + rho * rho)
                v = foreground((c + d * t).normalized())
            }
            px[y * w + x] = gray(v ?: background(d), gain)
        }
        return px
    }
}

class SeamTest {

    private val hFov = 50.0; private val vFov = 65.0
    private val th = SphereMath.tanHalf(hFov); private val tv = SphereMath.tanHalf(vFov)
    private val yaw0 = 0.3

    private fun poses() = SphereMath.buildTargets(hFov, vFov, 0.35).map {
        SphereMathTest().poseFor(yaw0 + it.yaw, it.pitch)
    }

    @Test fun gainsEqualiseExposure() {
        val rnd = Random(3)
        val trueGain = DoubleArray(poses().size) { 0.85 + 0.3 * rnd.nextDouble() }
        val frames = poses().mapIndexed { i, r ->
            FrameImage(TestScene.render(r, th, tv, 160, 213, gain = trueGain[i]), 160, 213, r, th, tv)
        }
        val core = StitchCore(frames, yaw0)
        core.prepare(512, 256)
        // After compensation every frame's brightness·gain should be the same constant.
        val prod = frames.indices.map { core.gains[it][1] * trueGain[it] }
        val spread = prod.max() / prod.min()
        println("STAT gains spread(max/min of g·trueGain)=%.4f  raw spread=%.4f".format(spread, trueGain.max() / trueGain.min()))
        assertTrue("exposure spread $spread", spread < 1.06)
    }

    @Test fun seamFinderReproducesSceneAndCoversEverything() {
        val frames = poses().map { r ->
            FrameImage(TestScene.render(r, th, tv, 160, 213), 160, 213, r, th, tv)
        }
        val ow = 256; val oh = 128
        val core = StitchCore(frames, yaw0)
        core.prepare(ow, oh)
        val out = IntArray(ow * oh)
        core.renderRows(ow, oh, 0, oh, out)
        var maxErr = 0
        for (v in 0 until oh) for (u in 0 until ow) {
            val lon = (u + 0.5) / ow * 2 * PI - PI
            val lat = PI / 2 - (v + 0.5) / oh * PI
            val c = out[v * ow + u]
            assertTrue("pixel ($u,$v) covered", c ushr 24 == 0xFF)
            val e = TestScene.gray(TestScene.background(SphereMath.dirFromYawPitch(lon + yaw0, lat)))
            maxErr = max(maxErr, abs(((c shr 8) and 0xFF) - ((e shr 8) and 0xFF)))
        }
        assertTrue("max error $maxErr", maxErr <= 8)
    }

    @Test fun seamsAvoidParallaxObjects() {
        val frames = poses().map { r ->
            FrameImage(TestScene.render(r, th, tv, 200, 267, camOffset = 0.2, rho = 0.9), 200, 267, r, th, tv)
        }
        val ow = 512; val oh = 256

        fun totalVariation(img: IntArray): Double {
            var tvSum = 0.0
            for (v in 0 until oh - 1) for (u in 0 until ow) {
                val c = (img[v * ow + u] shr 8) and 0xFF
                tvSum += abs(c - ((img[v * ow + (u + 1) % ow] shr 8) and 0xFF))
                tvSum += abs(c - ((img[(v + 1) * ow + u] shr 8) and 0xFF))
            }
            return tvSum
        }

        val plain = StitchCore(frames, yaw0)
        val a = IntArray(ow * oh); plain.renderRows(ow, oh, 0, oh, a)

        val seam = StitchCore(frames, yaw0)
        seam.prepare(ow, oh)
        val b = IntArray(ow * oh); seam.renderRows(ow, oh, 0, oh, b)

        val st = seam.seamStats!!
        println("STAT seams energy before=%.0f after=%.0f (%.0f%%) passes=%d".format(
            st.seamEnergyBefore, st.seamEnergyAfter, 100 * st.seamEnergyAfter / st.seamEnergyBefore, st.passes))
        println("STAT totalVariation plain=%.0f seamFinder=%.0f".format(totalVariation(a), totalVariation(b)))
        assertTrue("seam energy should drop", st.seamEnergyAfter < st.seamEnergyBefore * 0.85)
        for (px in b) assertEquals(0xFF, px ushr 24)
    }
}
