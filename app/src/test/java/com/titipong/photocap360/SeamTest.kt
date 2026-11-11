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

    /**
     * A box room (x ∈ [−2, 2.5] m, y ∈ [−3, 2] m, floor −1.3 m, ceiling +1.4 m) seen from a camera
     * [camOffset] metres in front of the rotation centre: every surface is near, parallax varies smoothly.
     */
    fun renderRoom(r: FloatArray, th: Double, tv: Double, w: Int, h: Int, camOffset: Double): IntArray {
        val c = SphereMath.cameraForward(r) * camOffset
        val lo = doubleArrayOf(-2.0, -3.0, -1.3); val hi = doubleArrayOf(2.5, 2.0, 1.4)
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val nx = (x + 0.5) / w * 2 - 1
            val ny = 1 - (y + 0.5) / h * 2
            val d = SphereMath.rotate(r, Vec3(nx * th, ny * tv, -1.0)).normalized()
            val cc = doubleArrayOf(c.x, c.y, c.z); val dd = doubleArrayOf(d.x, d.y, d.z)
            var t = Double.MAX_VALUE
            for (a in 0..2) {
                if (abs(dd[a]) < 1e-9) continue
                val ta = ((if (dd[a] > 0) hi[a] else lo[a]) - cc[a]) / dd[a]
                if (ta > 0 && ta < t) t = ta
            }
            val p = c + d * t
            val v = 0.8 * (0.1 + 0.55 * noise(p.x * 4 + 50, p.y * 4 + 50, p.z * 4 + 50) +
                0.35 * noise(p.x * 13 + 99, p.y * 13 + 99, p.z * 13 + 99))
            px[y * w + x] = gray(v)
        }
        return px
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
        gain: Double = 1.0, camOffset: Double = 0.0, rho: Double = 1.0, vignette: Double = 0.0,
        tiltX: Double = 0.0, tiltY: Double = 0.0,
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
            val vig = (1 - vignette * (nx * nx + ny * ny) / 2) * (1 + tiltX * nx + tiltY * ny)
            px[y * w + x] = gray(v ?: background(d), gain * vig)
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

    /**
     * Random per-frame shading (vignetting 20–35 %, brightness tilt ±15 %, exposure ±8 %): with sharp
     * seams each frame's shading shows up as a step at the seam ("patches"). Multi-band spreads it out.
     * Metrics:
     *  - patchiness: R = (4×4 block mean of output)/(same of truth); mean (∇R)². A brightness step
     *    of Δ costs Δ², the same change spread over k blocks costs only Δ²/k, so steps dominate.
     *  - detail: mean |∇out − ∇truth| at full resolution (must not get noticeably worse).
     */
    @Test fun multiBandRemovesPatches() {
        val rnd = Random(5)
        val frames = poses().map { r ->
            // Per-frame uneven shading, like phone tone-mapping/HDR: gains alone can't remove it.
            val g = 0.92 + 0.16 * rnd.nextDouble()
            val vig = 0.2 + 0.15 * rnd.nextDouble()
            val tx = (rnd.nextDouble() - 0.5) * 0.3; val ty = (rnd.nextDouble() - 0.5) * 0.3
            FrameImage(TestScene.render(r, th, tv, 200, 267, gain = g * 0.85, vignette = vig, tiltX = tx, tiltY = ty), 200, 267, r, th, tv)
        }
        val ow = 512; val oh = 256
        fun render(multi: Boolean): IntArray {
            val core = StitchCore(frames, yaw0)
            core.multiBand = multi
            core.prepare(ow, oh)
            return IntArray(ow * oh).also { core.renderRows(ow, oh, 0, oh, it) }
        }
        val truth = IntArray(ow * oh) { i ->
            val u = i % ow; val v = i / ow
            val lon = (u + 0.5) / ow * 2 * PI - PI
            val lat = PI / 2 - (v + 0.5) / oh * PI
            TestScene.gray(TestScene.background(SphereMath.dirFromYawPitch(lon + yaw0, lat)))
        }
        fun gradErr(img: IntArray): Double {
            var sum = 0.0; var n = 0
            for (v in oh / 10 until oh - oh / 10) for (u in 0 until ow) {
                val i = v * ow + u; val r = v * ow + (u + 1) % ow; val d = i + ow
                fun g(a: IntArray, k: Int) = ((a[k] shr 8) and 0xFF).toDouble()
                sum += abs((g(img, r) - g(img, i)) - (g(truth, r) - g(truth, i)))
                sum += abs((g(img, d) - g(img, i)) - (g(truth, d) - g(truth, i)))
                n += 2
            }
            return sum / n
        }
        fun patchiness(img: IntArray): Double {
            val bw = ow / 4; val bh = oh / 4
            val ratio = DoubleArray(bw * bh)
            for (by in 0 until bh) for (bx in 0 until bw) {
                var so = 0.0; var st = 0.0
                for (y in 0 until 4) for (x in 0 until 4) {
                    val i = (by * 4 + y) * ow + bx * 4 + x
                    so += (img[i] shr 8) and 0xFF; st += (truth[i] shr 8) and 0xFF
                }
                ratio[by * bw + bx] = so / max(1.0, st)
            }
            var sum = 0.0; var n = 0
            for (by in bh / 10 until bh - bh / 10 - 1) for (bx in 0 until bw) {
                val i = by * bw + bx
                val dx = ratio[by * bw + (bx + 1) % bw] - ratio[i]; val dy = ratio[i + bw] - ratio[i]
                sum += dx * dx + dy * dy; n++
            }
            return 1e4 * sum / n
        }
        val sharp = render(false); val multi = render(true)
        for (px in multi) assertEquals(0xFF, px ushr 24)
        val pSharp = patchiness(sharp); val pMulti = patchiness(multi)
        val eSharp = gradErr(sharp); val eMulti = gradErr(multi)
        println("STAT multiband patchiness sharp=%.3f multi=%.3f · detail gradErr sharp=%.3f multi=%.3f".format(pSharp, pMulti, eSharp, eMulti))
        assertTrue("patchiness multi $pMulti vs sharp $pSharp", pMulti < pSharp * 0.7)
        assertTrue("detail multi $eMulti vs sharp $eSharp", eMulti < eSharp * 1.15)
    }

    @Test fun nadirPatchIsSmoothAndUniformAtPole() {
        val w = 64; val h = 32
        val rnd = Random(9)
        // Covered except the bottom 6 rows (the floor under the photographer).
        val out = IntArray(w * h) { i -> if (i / w < h - 6) TestScene.gray(0.3 + 0.4 * rnd.nextDouble()) else 0 }
        StitchCore.fillPoles(out, w, h)
        for (px in out) assertEquals(0xFF, px ushr 24)
        val last = (h - 1) * w
        val g = (0 until w).map { (out[last + it] shr 8) and 0xFF }
        assertTrue("pole row should be one colour, spread ${g.max() - g.min()}", g.max() - g.min() <= 2)
    }

    /** New capture layout: no nadir shot, lower row tilted down, 4 floor shots at −72°. */
    @Test fun floorRingCoversNearlyEverything() {
        val targets = SphereMath.buildTargets(hFov, vFov, 0.35, includeNadir = false, lowerExtraDeg = 6.0, floorRingDeg = 72.0)
        val frames = targets.map {
            val r = SphereMathTest().poseFor(yaw0 + it.yaw, it.pitch)
            FrameImage(TestScene.render(r, th, tv, 80, 107), 80, 107, r, th, tv)
        }
        val ow = 256; val oh = 128
        val out = IntArray(ow * oh)
        StitchCore(frames, yaw0).renderRows(ow, oh, 0, oh, out)
        val holes = out.count { it ushr 24 == 0 }
        println("STAT floor ring: ${targets.size} shots, uncovered %.2f%%".format(100.0 * holes / out.size))
        assertTrue("uncovered ${holes * 100.0 / out.size}%", holes < out.size * 0.02)
    }

    /** Camera 20 cm in front of the rotation centre inside a small room: the mesh warp must cut the leftover error. */
    @Test fun meshWarpReducesParallax() {
        val frames = poses().map { r ->
            FrameImage(TestScene.renderRoom(r, th, tv, 240, 320, camOffset = 0.2), 240, 320, r, th, tv)
        }
        val res = Aligner(frames, parallaxModel = false).run()
        println("STAT mesh warp: applied=${res.applied} rms=%.3f° warp %.3f° → %.3f° (${res.warps.count { it != null }} frames)"
            .format(res.rmsDeg, res.warpRmsBeforeDeg, res.warpRmsAfterDeg))
        assertTrue(res.applied)
        assertTrue("warp should reduce the error", res.warpRmsAfterDeg < res.warpRmsBeforeDeg * 0.7)
    }

    /** A free FOV must stay within ±10 % even when parallax pulls it (no collapse). */
    @Test fun freeFovIsBounded() {
        val frames = poses().map { r ->
            FrameImage(TestScene.renderRoom(r, th, tv, 200, 267, camOffset = 0.35), 200, 267, r, th, tv)
        }
        val res = Aligner(frames, fovMode = Aligner.FovMode.FREE).run()
        println("STAT free FOV in room: scale=%.4f applied=${res.applied} maxDev=%.2f°".format(res.fovScale, res.maxDevDeg))
        assertTrue("scale ${res.fovScale}", res.fovScale in 0.9..1.11)
    }

    /** Painting a spot with frame 0 must make the output there come from frame 0, not its neighbour. */
    @Test fun paintForcesFrame() {
        val targets = SphereMath.buildTargets(hFov, vFov, 0.35)
        val frames = targets.mapIndexed { k, t ->
            val r = SphereMathTest().poseFor(yaw0 + t.yaw, t.pitch)
            val g = 20 + (k * 37) % 220
            FrameImage(IntArray(60 * 80) { (0xFF shl 24) or (g shl 16) or (g shl 8) or g }, 60, 80, r, th, tv)
        }
        val gray0 = 20
        val gray1 = 20 + 37 % 220
        // Horizon, 20° to the right of frame 0's centre: frame 1 (30° right) is more central there.
        val lon = yaw0 + 20 * SphereMath.DEG; val lat = 0.0
        val ow = 256; val oh = 128
        val u = ((lon - yaw0 + PI) / (2 * PI) * ow).toInt(); val v = ((PI / 2 - lat) / PI * oh).toInt()
        fun renderWith(paint: List<StitchCore.Paint>): Int {
            val core = StitchCore(frames, yaw0)
            core.multiBand = false; core.gainCompensation = false; core.paint = paint
            core.prepare(ow, oh)
            val out = IntArray(ow * oh)
            core.renderRows(ow, oh, 0, oh, out)
            return (out[v * ow + u] shr 8) and 0xFF
        }
        val auto = renderWith(emptyList())
        val painted = renderWith(listOf(StitchCore.Paint(0, 4 * SphereMath.DEG, doubleArrayOf(lon, lat))))
        println("STAT paint: auto=$auto (frame1=$gray1) painted=$painted (frame0=$gray0)")
        assertEquals(gray1, auto)
        assertEquals(gray0, painted)
    }

    /**
     * Room with the camera 20 cm in front of the rotation centre and a drifting gyro.
     * The room model must fit far better than rotation-only, stay level, and find plausible
     * distances (walls 2–3 m → ρ ≈ 0.07–0.1; floor 1.3 m / ceiling 1.4 m → κ ≈ 0.15).
     */
    @Test fun roomModelFitsParallax() {
        val rnd = Random(11)
        var drift = 0.0
        val truth = poses()
        val frames = truth.map { r ->
            drift += rnd.nextGaussian() * 0.8 * SphereMath.DEG
            val pert = Rot.mul(Rot.exp(rnd.nextGaussian() * 0.003, rnd.nextGaussian() * 0.003, drift), Rot.ofFloat(r))
            FrameImage(TestScene.renderRoom(r, th, tv, 240, 320, camOffset = 0.2), 240, 320, Rot.toFloat(pert), th, tv)
        }
        val plain = Aligner(frames, parallaxModel = false, meshWarp = false).run()
        val room = Aligner(frames, meshWarp = false).run()
        // Tilt of the result vs the truth (levelness), worst frame.
        val tilt = truth.indices.maxOf { k ->
            val p = Rot.log(Rot.mul(Rot.ofFloat(room.rotations[k]), Rot.t(Rot.ofFloat(truth[k]))))
            sqrt(p[0] * p[0] + p[1] * p[1]) / SphereMath.DEG
        }
        println("STAT room model: rms plain=%.3f° room=%.3f° · ρ=%.3f κ=%.3f · tilt vs truth %.2f° · applied=${room.applied}"
            .format(plain.rmsDeg, room.rmsDeg, room.rhoWall, room.kappa, tilt))
        assertTrue(room.applied)
        assertTrue("room model should fit much better", room.rmsDeg < 0.6 * plain.rmsDeg)
        assertTrue("ρ ${room.rhoWall}", room.rhoWall in 0.04..0.14)
        assertTrue("κ ${room.kappa}", room.kappa in 0.08..0.25)
        assertTrue("stays level: $tilt°", tilt < 0.6)
    }
}
