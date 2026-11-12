package com.titipong.photocap360

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Relative inverse depth from the AI model for one frame (row-major, upright like the frame). */
class DepthImage(val w: Int, val h: Int, val v: FloatArray)

/**
 * ρ (camera offset / scene distance, see the room model in [Aligner]) for every direction from the
 * rotation centre, on a lon/lat grid. Replaces ρ(d) = max(ρ_wall, κ|d.z|) when stitching with AI.
 * World lon = atan2(d.x, d.y), lat = asin(d.z).
 */
class RhoMap(val w: Int, val h: Int, val v: FloatArray) {
    fun at(dx: Double, dy: Double, dz: Double): Double {
        val lon = atan2(dx, dy)
        val lat = asin(dz.coerceIn(-1.0, 1.0))
        val fx = (lon + PI) / (2 * PI) * w - 0.5
        val fy = ((PI / 2 - lat) / PI * h - 0.5).coerceIn(0.0, h - 1.0)
        val x0f = floor(fx); val y0 = min(fy.toInt(), h - 2)
        val tx = fx - x0f; val ty = fy - y0
        val x0 = ((x0f.toInt() % w) + w) % w; val x1 = (x0 + 1) % w
        val a = v[y0 * w + x0] + (v[y0 * w + x1] - v[y0 * w + x0]) * tx
        val b = v[(y0 + 1) * w + x0] + (v[(y0 + 1) * w + x1] - v[(y0 + 1) * w + x0]) * tx
        return a + (b - a) * ty
    }
}

/**
 * Turns per-frame AI depth into one [RhoMap].
 *
 * 1. Scale: the model's output is inverse depth up to an unknown scale and shift per image,
 *    disp = a/D + b. Since ρ = offset/D, ρ = α·disp + β for some α > 0, β. Fit α, β per frame
 *    against the room model (walls/floor/ceiling dominate every frame) with trimmed least squares,
 *    so furniture — where the AI differs from the box — doesn't bend the fit.
 * 2. Fuse: every depth pixel, with its ρ, gives a point on the unit scene sphere (camera at
 *    ρ·c, same geometry as Aligner.ray); splat its ρ into a lon/lat grid weighted by how central it
 *    is in its frame. Empty cells (straight down/up) take the room model. 3×3 median to finish.
 */
object DepthFusion {

    class Fit(val alpha: Double, val beta: Double, val ok: Boolean)
    class Result(val map: RhoMap, val fits: List<Fit?>, val coverage: Double)

    private const val MAP_W = 512
    private const val MAP_H = 256
    private const val RHO_MIN = 0.005
    private const val RHO_MAX = 0.6
    private const val KEEP = 0.6

    private fun roomRho(dz: Double, rw: Double, kp: Double) = min(RHO_MAX, max(rw, kp * abs(dz)))

    /** Bilinear sample of [d] at image-normalized (nx, ny), NaN outside. */
    fun sample(d: DepthImage, nx: Double, ny: Double): Double {
        val x = (nx + 1) / 2 * d.w - 0.5; val y = (1 - ny) / 2 * d.h - 0.5
        if (x < 0 || y < 0 || x > d.w - 1 || y > d.h - 1) return Double.NaN
        val x0 = min(x.toInt(), d.w - 2); val y0 = min(y.toInt(), d.h - 2)
        val tx = x - x0; val ty = y - y0
        val i = y0 * d.w + x0
        val a = d.v[i] + (d.v[i + 1] - d.v[i]) * tx
        val b = d.v[i + d.w] + (d.v[i + d.w + 1] - d.v[i + d.w]) * tx
        return a + (b - a) * ty
    }

    /** Image-normalized (nx, ny) of frame f → model coords (undo the mesh warp, one fixed-point step). */
    private fun unwarp(f: FrameImage, nx: Double, ny: Double, out: DoubleArray) {
        val t = DoubleArray(2)
        f.warpAt(nx, ny, t)
        out[0] = nx - t[0]; out[1] = ny - t[1]
    }

    /** Scene-sphere direction of model pixel (nx, ny) with camera offset ρ (constant, or room model if rho < 0). */
    private fun dir(f: FrameImage, nx: Double, ny: Double, rho0: Double, rw: Double, kp: Double, out: DoubleArray) {
        var vx = nx * f.tanH; var vy = ny * f.tanV; var vz = -1.0
        val vn = sqrt(vx * vx + vy * vy + vz * vz); vx /= vn; vy /= vn; vz /= vn
        val u = f.offset
        val cx = u?.get(0)?.toDouble() ?: 0.0; val cy = u?.get(1)?.toDouble() ?: 0.0; val cz = -1.0 - (u?.get(2)?.toDouble() ?: 0.0)
        val cdv = cx * vx + cy * vy + cz * vz; val cc = cx * cx + cy * cy + cz * cz
        val r = f.r
        var rho = if (rho0 >= 0) rho0 else rw
        for (it in 0 until (if (rho0 >= 0) 1 else 4)) {
            val cv = rho * cdv
            val s = -cv + sqrt(max(0.0, cv * cv - rho * rho * cc + 1))
            val px = rho * cx + s * vx; val py = rho * cy + s * vy; val pz = rho * cz + s * vz
            out[0] = r[0] * px + r[1] * py + r[2] * pz
            out[1] = r[3] * px + r[4] * py + r[5] * pz
            out[2] = r[6] * px + r[7] * py + r[8] * pz
            if (rho0 >= 0) break
            rho = roomRho(out[2], rw, kp)
        }
    }

    fun fit(f: FrameImage, d: DepthImage, rw: Double, kp: Double): Fit {
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
        val m = DoubleArray(2); val w = DoubleArray(3)
        val step = max(1, min(d.w, d.h) / 40)
        var y = step / 2
        while (y < d.h) {
            var x = step / 2
            while (x < d.w) {
                unwarp(f, (x + 0.5) / d.w * 2 - 1, 1 - (y + 0.5) / d.h * 2, m)
                dir(f, m[0], m[1], -1.0, rw, kp, w)
                xs += d.v[y * d.w + x].toDouble(); ys += roomRho(w[2], rw, kp)
                x += step
            }
            y += step
        }
        if (xs.size < 20) return Fit(0.0, 0.0, false)
        var use = xs.indices.toList()
        var a = 0.0; var b = 0.0
        repeat(5) {
            var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
            for (k in use) { val xv = xs[k]; val yv = ys[k]; sx += xv; sy += yv; sxx += xv * xv; sxy += xv * yv }
            val nn = use.size.toDouble()
            val den = nn * sxx - sx * sx
            if (abs(den) < 1e-12) return Fit(0.0, 0.0, false)
            a = (nn * sxy - sx * sy) / den; b = (sy - a * sx) / nn
            val res = xs.indices.map { abs(a * xs[it] + b - ys[it]) }
            use = xs.indices.sortedBy { res[it] }.take(max(20, (xs.size * KEEP).toInt()))
        }
        // A frame that sees only walls has a near-constant room ρ: the slope is then undetermined.
        // Fall back to pure scale (β = 0): ρ = α·disp with α from the trimmed ratio.
        val my = ys.average()
        val sdy = sqrt(ys.sumOf { (it - my) * (it - my) } / ys.size)
        if (a <= 0 || sdy < 0.1 * my) {
            var ratio = xs.indices.map { ys[it] / max(1e-6, xs[it]) }.sorted()[xs.size / 2]
            repeat(3) {
                val keep = xs.indices.sortedBy { abs(ratio * xs[it] - ys[it]) }.take(max(20, (xs.size * KEEP).toInt()))
                var sxy = 0.0; var sxx = 0.0
                for (k in keep) { sxy += xs[k] * ys[k]; sxx += xs[k] * xs[k] }
                if (sxx > 1e-12) ratio = sxy / sxx
            }
            return Fit(ratio, 0.0, ratio > 0)
        }
        return Fit(a, b, true)
    }

    /** Initial per-frame fits against the room model. */
    fun fits(frames: List<FrameImage>, depth: List<DepthImage?>, rw: Double, kp: Double): List<Fit?> =
        frames.indices.map { k -> depth.getOrNull(k)?.let { fit(frames[k], it, rw, kp) } }

    fun build(
        frames: List<FrameImage>, depth: List<DepthImage?>, rw: Double, kp: Double,
        given: List<Fit?>? = null,
    ): Result {
        val sum = DoubleArray(MAP_W * MAP_H); val wsum = DoubleArray(MAP_W * MAP_H)
        val fits = ArrayList<Fit?>()
        val m = DoubleArray(2); val p = DoubleArray(3)
        for ((k, f) in frames.withIndex()) {
            val d = depth.getOrNull(k)
            if (d == null) { fits += null; continue }
            val ft = given?.getOrNull(k) ?: fit(f, d, rw, kp)
            fits += ft
            if (!ft.ok) continue
            for (y in 0 until d.h) for (x in 0 until d.w) {
                val ix = (x + 0.5) / d.w * 2 - 1; val iy = 1 - (y + 0.5) / d.h * 2
                val rho = (ft.alpha * d.v[y * d.w + x] + ft.beta).coerceIn(RHO_MIN, RHO_MAX)
                unwarp(f, ix, iy, m)
                dir(f, m[0], m[1], rho, rw, kp, p)
                val wt = 0.05 + min(1 - abs(ix), 1 - abs(iy))
                val lon = atan2(p[0], p[1]); val lat = asin(p[2].coerceIn(-1.0, 1.0))
                val cx = (((lon + PI) / (2 * PI) * MAP_W).toInt() % MAP_W + MAP_W) % MAP_W
                val cy = ((PI / 2 - lat) / PI * MAP_H).toInt().coerceIn(0, MAP_H - 1)
                sum[cy * MAP_W + cx] += wt * rho; wsum[cy * MAP_W + cx] += wt
            }
        }
        val raw = FloatArray(MAP_W * MAP_H)
        var covered = 0
        for (cy in 0 until MAP_H) for (cx in 0 until MAP_W) {
            val i = cy * MAP_W + cx
            raw[i] = if (wsum[i] > 0) { covered++; (sum[i] / wsum[i]).toFloat() } else {
                val lat = PI / 2 - (cy + 0.5) / MAP_H * PI
                roomRho(kotlin.math.sin(lat), rw, kp).toFloat()
            }
        }
        // 3×3 median (wraps in longitude).
        val out = FloatArray(raw.size); val win = FloatArray(9)
        for (cy in 0 until MAP_H) for (cx in 0 until MAP_W) {
            var n = 0
            for (dy in -1..1) {
                val yy = (cy + dy).coerceIn(0, MAP_H - 1)
                for (dx in -1..1) win[n++] = raw[yy * MAP_W + ((cx + dx + MAP_W) % MAP_W)]
            }
            win.sort(); out[cy * MAP_W + cx] = win[4]
        }
        return Result(RhoMap(MAP_W, MAP_H, out), fits, covered.toDouble() / raw.size)
    }
}
