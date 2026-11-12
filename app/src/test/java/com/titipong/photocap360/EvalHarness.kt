package com.titipong.photocap360

import com.titipong.photocap360.SphereMath.Vec3
import java.util.Random
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Ground-truth evaluation for the paper. Synthetic box room (TestScene.renderRoom), camera 20 cm in
 * front of the rotation centre. For every overlapping frame pair and a grid of pixels in frame i,
 * the true scene point is ray-cast and projected into frame j with the TRUE poses and camera
 * centres; the error is the angle between that and where each configuration's model (rotations,
 * FOV, room model, camera positions, mesh warps) maps the pixel. Nothing here uses the matches the
 * aligner fitted, so it measures generalisation, not fit.
 *
 * Not a unit test (too slow for CI); run its main() from the test classpath:
 *   args: scene (drift|sway) noise(0|1) seeds
 * Prints one RES line per seed: median/P90 error (deg) for each configuration.
 * Results are reported in the paper (Table 3).
 */
object EvalHarness {
    private val hFov = 50.0; private val vFov = 65.0
    private val th = SphereMath.tanHalf(hFov); private val tv = SphereMath.tanHalf(vFov)
    private const val W = 240; private const val H = 320
    private const val OFFSET = 0.2
    private val lo = doubleArrayOf(-2.0, -3.0, -1.3); private val hi = doubleArrayOf(2.5, 2.0, 1.4)

    class Cfg(val name: String, val frames: List<FrameImage>, val rw: Double, val kp: Double)

    private fun boxT(c: Vec3, d: Vec3): Double {
        val cc = doubleArrayOf(c.x, c.y, c.z); val dd = doubleArrayOf(d.x, d.y, d.z)
        var t = Double.MAX_VALUE
        for (a in 0..2) {
            if (abs(dd[a]) < 1e-9) continue
            val ta = ((if (dd[a] > 0) hi[a] else lo[a]) - cc[a]) / dd[a]
            if (ta > 0 && ta < t) t = ta
        }
        return t
    }

    private fun rhoOf(dz: Double, rw: Double, kp: Double) = if (rw == 0.0 && kp == 0.0) 0.0 else min(0.6, max(rw, kp * abs(dz)))

    /** Model: image coords (nx, ny) of frame f → world dir from the rotation centre. */
    private fun modelRay(f: FrameImage, nx: Double, ny: Double, rw: Double, kp: Double): DoubleArray {
        val t = DoubleArray(2)
        var mx = nx; var my = ny
        repeat(4) { f.warpAt(mx, my, t); mx = nx - t[0]; my = ny - t[1] }
        var vx = mx * f.tanH; var vy = my * f.tanV; var vz = -1.0
        val vn = sqrt(vx * vx + vy * vy + vz * vz); vx /= vn; vy /= vn; vz /= vn
        val u = f.offset
        val cx = u?.get(0)?.toDouble() ?: 0.0; val cy = u?.get(1)?.toDouble() ?: 0.0; val cz = -1.0 - (u?.get(2)?.toDouble() ?: 0.0)
        val cdv = cx * vx + cy * vy + cz * vz; val cc = cx * cx + cy * cy + cz * cz
        val r = f.r
        var rho = rw
        val out = DoubleArray(3)
        for (it in 0 until 4) {
            val cv = rho * cdv
            val s = -cv + sqrt(max(0.0, cv * cv - rho * rho * cc + 1))
            val px = rho * cx + s * vx; val py = rho * cy + s * vy; val pz = rho * cz + s * vz
            out[0] = r[0] * px + r[1] * py + r[2] * pz
            out[1] = r[3] * px + r[4] * py + r[5] * pz
            out[2] = r[6] * px + r[7] * py + r[8] * pz
            val r2 = rhoOf(out[2], rw, kp)
            if (abs(r2 - rho) < 1e-9) break
            rho = r2
        }
        return out
    }

    /** Model: world dir → image coords of frame f (with its warp), null if behind. */
    private fun modelProject(f: FrameImage, d: DoubleArray, rw: Double, kp: Double): DoubleArray? {
        val rho = rhoOf(d[2], rw, kp)
        val u = f.offset; val r = f.r
        val px = r[0] * d[0] + r[3] * d[1] + r[6] * d[2] - rho * (u?.get(0) ?: 0f)
        val py = r[1] * d[0] + r[4] * d[1] + r[7] * d[2] - rho * (u?.get(1) ?: 0f)
        val pz = r[2] * d[0] + r[5] * d[1] + r[8] * d[2] + rho * (1 + (u?.get(2) ?: 0f))
        if (-pz < 1e-6) return null
        val nx = px / -pz / f.tanH; val ny = py / -pz / f.tanV
        val t = DoubleArray(2); f.warpAt(nx, ny, t)
        return doubleArrayOf(nx + t[0], ny + t[1])
    }

    /** All errors (deg) over overlapping ordered pairs, grid 10×13 per frame. */
    fun errors(cfg: Cfg, truthR: List<FloatArray>, centres: List<Vec3>): DoubleArray {
        val n = truthR.size
        val out = ArrayList<Double>()
        val reach = 2 * atan(sqrt(th * th + tv * tv))
        for (i in 0 until n) for (j in 0 until n) {
            if (i == j) continue
            val fi = SphereMath.cameraForward(truthR[i]); val fj = SphereMath.cameraForward(truthR[j])
            if (acos(fi.dot(fj).coerceIn(-1.0, 1.0)) > reach * 0.95) continue
            for (gy in 0 until 13) for (gx in 0 until 10) {
                val nx = -0.9 + 1.8 * (gx + 0.5) / 10; val ny = -0.9 + 1.8 * (gy + 0.5) / 13
                val d = SphereMath.rotate(truthR[i], Vec3(nx * th, ny * tv, -1.0)).normalized()
                val P = centres[i] + d * boxT(centres[i], d)
                val q = SphereMath.rotateT(truthR[j], P + centres[j] * -1.0)
                if (q.z >= -1e-6) continue
                val tx = q.x / -q.z / th; val ty = q.y / -q.z / tv
                if (abs(tx) > 0.95 || abs(ty) > 0.95) continue
                val md = modelRay(cfg.frames[i], nx, ny, cfg.rw, cfg.kp)
                val mp = modelProject(cfg.frames[j], md, cfg.rw, cfg.kp) ?: continue
                val ex = (mp[0] - tx) * th; val ey = (mp[1] - ty) * tv
                out += Math.toDegrees(sqrt(ex * ex + ey * ey))
            }
        }
        return out.toDoubleArray()
    }

    private fun pct(a: DoubleArray, p: Double): Double { val s = a.sorted(); return s[min(s.size - 1, (p * s.size).toInt())] }

    @JvmStatic fun main(args: Array<String>) {
        val scene = args.getOrElse(0) { "drift" }
        val noisy = args.getOrElse(1) { "0" } == "1"
        val seeds = args.getOrElse(2) { "3" }.toInt()
        val yaw0 = 0.3
        val truth = SphereMath.buildTargets(hFov, vFov, 0.35).map { SphereMathTest().poseFor(yaw0 + it.yaw, it.pitch) }
        for (seed in 1..seeds) {
            val rnd = Random(100L + seed)
            var drift = 0.0
            val centres = ArrayList<Vec3>(); val frames = ArrayList<FrameImage>()
            for (r in truth) {
                drift += rnd.nextGaussian() * 0.8 * SphereMath.DEG
                val pert = Rot.mul(Rot.exp(rnd.nextGaussian() * 0.003, rnd.nextGaussian() * 0.003, drift), Rot.ofFloat(r))
                val sway = if (scene == "sway") Vec3(rnd.nextGaussian() * 0.05, rnd.nextGaussian() * 0.05, rnd.nextGaussian() * 0.03) else Vec3(0.0, 0.0, 0.0)
                val c = SphereMath.cameraForward(r) * OFFSET + sway
                centres += c
                val px = TestScene.renderRoom(r, th, tv, W, H, camOffset = OFFSET, sway = sway)
                if (noisy) for (k in px.indices) {
                    val g = (px[k] and 0xFF) + (rnd.nextGaussian() * 0.03 * 255).toInt()
                    val v = g.coerceIn(0, 255)
                    px[k] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                }
                frames += FrameImage(px, W, H, Rot.toFloat(pert), th, tv)
            }
            val t0 = System.currentTimeMillis()
            val rotOnly = Aligner(frames, parallaxModel = false, meshWarp = false).run()
            val res = Aligner(frames).run()
            val pick = SeamSelect.pick(frames, res.candidates, yaw0)
            val tAlign = System.currentTimeMillis() - t0
            val cfgs = ArrayList<Cfg>()
            cfgs += Cfg("gyro", frames, 0.0, 0.0)
            cfgs += Cfg("rotonly", frames.mapIndexed { k, f -> FrameImage(f.pixels, W, H, rotOnly.rotations[k], th * rotOnly.fovScale, tv * rotOnly.fovScale) }, 0.0, 0.0)
            val c0 = res.candidates.first()
            cfgs += Cfg("room", SeamSelect.frames(frames, Aligner.Candidate("x", c0.rotations, c0.fovScale, c0.rhoWall, c0.kappa, List(frames.size) { null })), c0.rhoWall, c0.kappa)
            for (c in res.candidates) {
                val key = when {
                    c.offsets != null -> "campos"
                    c.name.contains("หยาบ") -> "coarse"
                    c === c0 -> "fine"
                    c.name.contains("gyro") -> null
                    else -> "otherfov"
                } ?: continue
                cfgs += Cfg(key, SeamSelect.frames(frames, c), c.rhoWall, c.kappa)
            }
            val ch = res.candidates[pick.index]
            cfgs += Cfg("select", SeamSelect.frames(frames, ch), ch.rhoWall, ch.kappa)
            val line = StringBuilder("RES scene=$scene noise=${if (noisy) 1 else 0} seed=$seed t=${tAlign}ms applied=${res.applied} pick=${ch.name.replace(' ', '_')} dense=${res.denseMatches}")
            for (c in cfgs) {
                val e = errors(c, truth, centres)
                line.append(" %s=%.3f/%.3f".format(c.name, pct(e, 0.5), pct(e, 0.9)))
            }
            println(line)
        }
    }
}
