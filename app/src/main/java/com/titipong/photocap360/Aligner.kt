package com.titipong.photocap360

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.IntStream
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Grayscale float image (0..1).
 * Pixel-centre convention shared with the stitcher:
 *   nx = (x + ½)/w · 2 − 1      ny = 1 − (y + ½)/h · 2
 */
class GrayImage(val w: Int, val h: Int, val px: FloatArray) {

    fun pxOf(nx: Double) = (nx + 1) / 2 * w - 0.5
    fun pyOf(ny: Double) = (1 - ny) / 2 * h - 0.5
    fun nxOf(x: Double) = (x + 0.5) / w * 2 - 1
    fun nyOf(y: Double) = 1 - (y + 0.5) / h * 2

    /** Bilinear sample, NaN outside the image. */
    fun sample(x: Double, y: Double): Float {
        if (x < 0.0 || y < 0.0 || x > w - 1.0 || y > h - 1.0) return Float.NaN
        val x0 = min(x.toInt(), w - 2)
        val y0 = min(y.toInt(), h - 2)
        val fx = (x - x0).toFloat()
        val fy = (y - y0).toFloat()
        val i = y0 * w + x0
        val a = px[i]; val b = px[i + 1]; val c = px[i + w]; val d = px[i + w + 1]
        val top = a + (b - a) * fx
        val bot = c + (d - c) * fx
        return top + (bot - top) * fy
    }

    private fun sampleClamped(x: Double, y: Double) =
        sample(x.coerceIn(0.0, w - 1.0), y.coerceIn(0.0, h - 1.0))

    /** Area-ish resample that keeps the normalized coordinate mapping exact. */
    fun resized(nw: Int, nh: Int): GrayImage {
        val fx = w.toDouble() / nw
        val fy = h.toDouble() / nh
        val ox = if (fx > 1) fx / 4 else 0.0
        val oy = if (fy > 1) fy / 4 else 0.0
        val out = FloatArray(nw * nh)
        for (y in 0 until nh) {
            val sy = (y + 0.5) * fy - 0.5
            for (x in 0 until nw) {
                val sx = (x + 0.5) * fx - 0.5
                out[y * nw + x] = (sampleClamped(sx - ox, sy - oy) + sampleClamped(sx + ox, sy - oy) +
                    sampleClamped(sx - ox, sy + oy) + sampleClamped(sx + ox, sy + oy)) * 0.25f
            }
        }
        return GrayImage(nw, nh, out)
    }

    fun half() = resized(max(2, w / 2), max(2, h / 2))

    companion object {
        fun fromArgb(p: IntArray, w: Int, h: Int): GrayImage {
            val out = FloatArray(w * h)
            for (i in out.indices) {
                val c = p[i]
                out[i] = (0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)) / 255f
            }
            return GrayImage(w, h, out)
        }
    }
}

/** 3×3 rotation helpers, row-major DoubleArray(9). */
object Rot {
    fun mul(a: DoubleArray, b: DoubleArray): DoubleArray {
        val o = DoubleArray(9)
        for (r in 0..2) for (c in 0..2) o[r * 3 + c] = a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
        return o
    }

    fun t(a: DoubleArray) = doubleArrayOf(a[0], a[3], a[6], a[1], a[4], a[7], a[2], a[5], a[8])

    /** Rodrigues: exp([w]×). */
    fun exp(wx: Double, wy: Double, wz: Double): DoubleArray {
        val th = sqrt(wx * wx + wy * wy + wz * wz)
        if (th < 1e-12) return doubleArrayOf(1.0, -wz, wy, wz, 1.0, -wx, -wy, wx, 1.0)
        val kx = wx / th; val ky = wy / th; val kz = wz / th
        val c = cos(th); val s = sin(th); val v = 1 - c
        return doubleArrayOf(
            c + kx * kx * v, kx * ky * v - kz * s, kx * kz * v + ky * s,
            ky * kx * v + kz * s, c + ky * ky * v, ky * kz * v - kx * s,
            kz * kx * v - ky * s, kz * ky * v + kx * s, c + kz * kz * v,
        )
    }

    /** Axis-angle vector of R (valid for angles < π). */
    fun log(r: DoubleArray): DoubleArray {
        val ct = ((r[0] + r[4] + r[8] - 1) / 2).coerceIn(-1.0, 1.0)
        val th = acos(ct)
        val k = if (th < 1e-9) 0.5 else th / (2 * sin(th))
        return doubleArrayOf((r[7] - r[5]) * k, (r[2] - r[6]) * k, (r[3] - r[1]) * k)
    }

    fun angle(r: DoubleArray): Double {
        val v = log(r); return sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
    }

    fun ofFloat(f: FloatArray) = DoubleArray(9) { f[it].toDouble() }
    fun toFloat(d: DoubleArray) = FloatArray(9) { d[it].toFloat() }
}

/**
 * Refines the gyroscope orientations and the field of view so overlapping frames line up.
 *
 * 1. Features: Shi-Tomasi corners on a grid in every frame.
 * 2. Guided matching: for each pair of overlapping frames, a corner in frame i is predicted in
 *    frame j from the gyro rotations. A patch around it is warped into j's geometry with the
 *    rotation homography and searched by normalized cross-correlation, coarse-to-fine on a
 *    3-level pyramid (±16 px at ¼ scale ≈ ±7°, then ±2 px), with sub-pixel peak fitting.
 * 3. Bundle adjustment (Levenberg-Marquardt) over a small rotation ω per frame and one shared
 *    log-FOV-scale σ, minimizing for every match the ray difference
 *        r = normalize(Rᵢ·vᵢ) − normalize(Rⱼ·vⱼ),   v = (nx·tanH·e^σ, ny·tanV·e^σ, −1)
 *    with a Huber loss, plus a weak prior pulling each rotation toward the gyro reading
 *    (keeps the horizon level and fixes the global rotation). Outliers are dropped and
 *    the adjustment repeated.
 */
class Aligner(private val frames: List<FrameImage>, private val baseLongEdge: Int = 640) {

    class Match(val i: Int, val ax: Double, val ay: Double, val j: Int, val bx: Double, val by: Double)

    class Result(
        val rotations: List<FloatArray>,
        val fovScale: Double,
        val matches: Int,
        val inliers: Int,
        val rmsDeg: Double,
        val applied: Boolean,
    )

    companion object {
        private const val LEVELS = 3
        private val SEARCH = intArrayOf(2, 2, 16)        // per level, 0 = finest
        private val TPL = intArrayOf(7, 6, 5)
        private val MIN_NCC = doubleArrayOf(0.85, 0.7, 0.6)
        private const val MIN_STD = 0.02
        private const val GRID_X = 12
        private const val GRID_Y = 16
        private const val HUBER = 0.004                  // rad ≈ 0.23°
        private const val PRIOR_W = 4e-4                 // ≈ (0.1° / 5.7°)²
        private const val SCALE_W = 4e-4
        private const val DEG = Math.PI / 180
    }

    private val n = frames.size
    private val rot0 = Array(n) { Rot.ofFloat(frames[it].r) }
    private val th = DoubleArray(n) { frames[it].tanH }
    private val tv = DoubleArray(n) { frames[it].tanV }
    private lateinit var pyr: Array<Array<GrayImage>>

    fun run(progress: (Float) -> Unit = {}): Result {
        if (n < 2) return unchanged(0)

        // ---- pyramids + features (parallel per frame)
        val pyrTmp = arrayOfNulls<Array<GrayImage>>(n)
        val feats = arrayOfNulls<List<DoubleArray>>(n)
        val built = AtomicInteger()
        IntStream.range(0, n).parallel().forEach { k ->
            val f = frames[k]
            var g = GrayImage.fromArgb(f.pixels, f.width, f.height)
            val long = max(g.w, g.h)
            if (long > baseLongEdge) {
                val s = baseLongEdge.toDouble() / long
                g = g.resized(max(2, (g.w * s).roundToInt()), max(2, (g.h * s).roundToInt()))
            }
            val levels = ArrayList<GrayImage>(LEVELS)
            levels += g
            repeat(LEVELS - 1) { levels += levels.last().half() }
            pyrTmp[k] = levels.toTypedArray()
            feats[k] = detect(g)
            progress(0.2f * built.incrementAndGet() / n)
        }
        pyr = Array(n) { pyrTmp[it]!! }

        // ---- overlapping pairs
        val pairs = ArrayList<IntArray>()
        for (i in 0 until n) for (j in i + 1 until n) {
            val ri = rot0[i]; val rj = rot0[j]
            val dot = ri[2] * rj[2] + ri[5] * rj[5] + ri[8] * rj[8]
            val reach = atan(sqrt(th[i] * th[i] + tv[i] * tv[i])) + atan(sqrt(th[j] * th[j] + tv[j] * tv[j]))
            if (acos(dot.coerceIn(-1.0, 1.0)) < reach * 0.95) pairs += intArrayOf(i, j)
        }

        // ---- guided matching (parallel per pair)
        val found = ConcurrentLinkedQueue<Match>()
        val done = AtomicInteger()
        IntStream.range(0, pairs.size).parallel().forEach { p ->
            val (i, j) = pairs[p]
            for (f in feats[i]!!) matchFeature(i, j, f[0], f[1])?.let { found += it }
            progress(0.2f + 0.65f * done.incrementAndGet() / pairs.size)
        }
        val all = found.toList()
        if (all.size < 3 * n) return unchanged(all.size)

        // ---- bundle adjustment, outlier removal, again
        val rot = Array(n) { rot0[it].copyOf() }
        var sigma = bundleAdjust(all, rot, 0.0, 20)
        val res = DoubleArray(all.size) { residual(all[it], rot, sigma) }
        val med = res.sorted()[res.size / 2]
        val thr = max(0.01, 4 * med)
        val inl = all.filterIndexed { k, _ -> res[k] < thr }
        sigma = bundleAdjust(inl, rot, sigma, 20)
        progress(1f)

        val rms = sqrt(inl.sumOf { val e = residual(it, rot, sigma); e * e } / max(1, inl.size))
        val maxDev = (0 until n).maxOf { Rot.angle(Rot.mul(rot[it], Rot.t(rot0[it]))) }
        val ok = inl.size >= 2 * n && rms < 1.0 * DEG && abs(sigma) < 0.15 && maxDev < 12 * DEG
        return Result(
            rotations = if (ok) rot.map { Rot.toFloat(it) } else frames.map { it.r },
            fovScale = if (ok) exp(sigma) else 1.0,
            matches = all.size,
            inliers = inl.size,
            rmsDeg = rms / DEG,
            applied = ok,
        )
    }

    private fun unchanged(matches: Int) = Result(frames.map { it.r }, 1.0, matches, 0, 0.0, false)

    // ---------------- geometry ----------------

    private fun ray(r: DoubleArray, tanH: Double, tanV: Double, nx: Double, ny: Double, out: DoubleArray) {
        val x = nx * tanH; val y = ny * tanV
        val wx = r[0] * x + r[1] * y - r[2]
        val wy = r[3] * x + r[4] * y - r[5]
        val wz = r[6] * x + r[7] * y - r[8]
        val nrm = sqrt(wx * wx + wy * wy + wz * wz)
        out[0] = wx / nrm; out[1] = wy / nrm; out[2] = wz / nrm
    }

    /** World direction → normalized image coords; false if behind the camera. */
    private fun project(r: DoubleArray, tanH: Double, tanV: Double, d: DoubleArray, out: DoubleArray): Boolean {
        val px = r[0] * d[0] + r[3] * d[1] + r[6] * d[2]
        val py = r[1] * d[0] + r[4] * d[1] + r[7] * d[2]
        val pz = r[2] * d[0] + r[5] * d[1] + r[8] * d[2]
        val depth = -pz
        if (depth < 1e-6) return false
        out[0] = px / depth / tanH; out[1] = py / depth / tanV
        return true
    }

    /** Pixel of j (at gj's level) → pixel of i (at gi's level) using the gyro rotations. */
    private fun jToI(j: Int, i: Int, gj: GrayImage, gi: GrayImage, x: Double, y: Double, d: DoubleArray, q: DoubleArray): Boolean {
        ray(rot0[j], th[j], tv[j], gj.nxOf(x), gj.nyOf(y), d)
        if (!project(rot0[i], th[i], tv[i], d, q)) return false
        q[0] = gi.pxOf(q[0]); q[1] = gi.pyOf(q[1])
        return true
    }

    // ---------------- features ----------------

    private fun detect(g: GrayImage): List<DoubleArray> {
        val w = g.w; val h = g.h; val p = g.px
        val ixx = FloatArray(w * h); val iyy = FloatArray(w * h); val ixy = FloatArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val k = y * w + x
            val gx = (p[k + 1] - p[k - 1]) * 0.5f
            val gy = (p[k + w] - p[k - w]) * 0.5f
            ixx[k] = gx * gx; iyy[k] = gy * gy; ixy[k] = gx * gy
        }
        val margin = (TPL[LEVELS - 1] + 1) * (1 shl (LEVELS - 1))
        if (w <= 2 * margin + GRID_X || h <= 2 * margin + GRID_Y) return emptyList()
        val cw = (w - 2 * margin).toDouble() / GRID_X
        val ch = (h - 2 * margin).toDouble() / GRID_Y
        val best = FloatArray(GRID_X * GRID_Y); val bx = IntArray(best.size); val by = IntArray(best.size)
        var gmax = 0f
        val rr = 2
        for (y in margin until h - margin) for (x in margin until w - margin) {
            var a = 0f; var b = 0f; var c = 0f
            for (dy in -rr..rr) {
                var k = (y + dy) * w + x - rr
                for (dx in -rr..rr) { a += ixx[k]; b += ixy[k]; c += iyy[k]; k++ }
            }
            val half = (a - c) / 2
            val lam = (a + c) / 2 - sqrt(half * half + b * b)
            val cell = min(GRID_X - 1, ((x - margin) / cw).toInt()) + GRID_X * min(GRID_Y - 1, ((y - margin) / ch).toInt())
            if (lam > best[cell]) { best[cell] = lam; bx[cell] = x; by[cell] = y }
            if (lam > gmax) gmax = lam
        }
        val out = ArrayList<DoubleArray>()
        for (c in best.indices) if (best[c] > 0.02f * gmax && best[c] > 1e-7f) out += doubleArrayOf(bx[c].toDouble(), by[c].toDouble())
        return out
    }

    // ---------------- matching ----------------

    private fun matchFeature(i: Int, j: Int, fx: Double, fy: Double): Match? {
        val g0 = pyr[i][0]
        val anx = g0.nxOf(fx); val any = g0.nyOf(fy)
        val d = DoubleArray(3); val q = DoubleArray(2)
        ray(rot0[i], th[i], tv[i], anx, any, d)
        if (!project(rot0[j], th[j], tv[j], d, q)) return null
        if (abs(q[0]) > 0.95 || abs(q[1]) > 0.95) return null
        var cnx = q[0]; var cny = q[1]

        for (lvl in LEVELS - 1 downTo 0) {
            val gi = pyr[i][lvl]; val gj = pyr[j][lvl]
            val s = SEARCH[lvl]; val r = TPL[lvl]
            val cx = gj.pxOf(cnx).roundToInt(); val cy = gj.pyOf(cny).roundToInt()
            val oxMin = max(-s, r - cx); val oxMax = min(s, gj.w - 1 - r - cx)
            val oyMin = max(-s, r - cy); val oyMax = min(s, gj.h - 1 - r - cy)
            if (oxMin > oxMax || oyMin > oyMax) return null

            // Template = i's neighbourhood of the feature, warped into j's pixel grid.
            val pix = gi.pxOf(anx); val piy = gi.pyOf(any)
            if (!jToI(j, i, gj, gi, cx.toDouble(), cy.toDouble(), d, q)) return null
            val hcx = q[0]; val hcy = q[1]
            val side = 2 * r + 1; val nT = side * side
            val tpl = FloatArray(nT)
            var k = 0; var sum = 0.0
            for (dy in -r..r) for (dx in -r..r) {
                if (!jToI(j, i, gj, gi, (cx + dx).toDouble(), (cy + dy).toDouble(), d, q)) return null
                val v = gi.sample(pix + q[0] - hcx, piy + q[1] - hcy)
                if (v.isNaN()) return null
                tpl[k++] = v; sum += v
            }
            val mean = (sum / nT).toFloat()
            var ss = 0.0
            for (t in 0 until nT) { tpl[t] -= mean; ss += (tpl[t] * tpl[t]).toDouble() }
            if (sqrt(ss / nT) < MIN_STD) return null
            val inv = (1 / sqrt(ss)).toFloat()
            for (t in 0 until nT) tpl[t] *= inv

            // NCC search
            val sw = 2 * s + 1
            val score = DoubleArray(sw * sw) { -2.0 }
            var best = -2.0; var bx = 0; var by = 0
            for (oy in oyMin..oyMax) for (ox in oxMin..oxMax) {
                var sJ = 0.0; var sJJ = 0.0; var sTJ = 0.0; var t = 0
                for (dy in -r..r) {
                    var idx = (cy + oy + dy) * gj.w + (cx + ox - r)
                    for (dx in -r..r) {
                        val v = gj.px[idx++]
                        sJ += v; sJJ += v * v; sTJ += tpl[t++] * v
                    }
                }
                val varJ = sJJ - sJ * sJ / nT
                val ncc = if (varJ > 1e-9) sTJ / sqrt(varJ) else -1.0
                score[(oy + s) * sw + (ox + s)] = ncc
                if (ncc > best) { best = ncc; bx = ox; by = oy }
            }
            if (best < MIN_NCC[lvl]) return null
            if (lvl == LEVELS - 1) {
                // Peak on the edge of the search window → true match is outside. Ambiguous → repetitive texture.
                if (bx == oxMin || bx == oxMax || by == oyMin || by == oyMax) return null
                var second = -2.0
                for (oy in oyMin..oyMax) for (ox in oxMin..oxMax) {
                    if (max(abs(ox - bx), abs(oy - by)) > 2) second = max(second, score[(oy + s) * sw + (ox + s)])
                }
                if (best - second < 0.1) return null
            }
            var sx = 0.0; var sy = 0.0
            if (lvl == 0) {
                if (bx > oxMin && bx < oxMax) sx = parabola(score[(by + s) * sw + (bx - 1 + s)], best, score[(by + s) * sw + (bx + 1 + s)])
                if (by > oyMin && by < oyMax) sy = parabola(score[(by - 1 + s) * sw + (bx + s)], best, score[(by + 1 + s) * sw + (bx + s)])
            }
            cnx = gj.nxOf(cx + bx + sx); cny = gj.nyOf(cy + by + sy)
        }
        return Match(i, anx, any, j, cnx, cny)
    }

    private fun parabola(l: Double, c: Double, r: Double): Double {
        val den = l - 2 * c + r
        return if (den >= 0) 0.0 else (0.5 * (l - r) / den).coerceIn(-0.5, 0.5)
    }

    // ---------------- bundle adjustment ----------------

    private fun dirs(m: Match, rot: Array<DoubleArray>, s: Double, ui: DoubleArray, uj: DoubleArray) {
        ray(rot[m.i], th[m.i] * s, tv[m.i] * s, m.ax, m.ay, ui)
        ray(rot[m.j], th[m.j] * s, tv[m.j] * s, m.bx, m.by, uj)
    }

    private fun residual(m: Match, rot: Array<DoubleArray>, sigma: Double): Double {
        val ui = DoubleArray(3); val uj = DoubleArray(3)
        dirs(m, rot, exp(sigma), ui, uj)
        val a = ui[0] - uj[0]; val b = ui[1] - uj[1]; val c = ui[2] - uj[2]
        return sqrt(a * a + b * b + c * c)
    }

    private fun cost(ms: List<Match>, rot: Array<DoubleArray>, sigma: Double): Double {
        var c = 0.0
        for (m in ms) {
            val e = residual(m, rot, sigma)
            c += if (e < HUBER) e * e else 2 * HUBER * e - HUBER * HUBER
        }
        for (k in 0 until n) {
            val p = Rot.log(Rot.mul(rot[k], Rot.t(rot0[k])))
            c += PRIOR_W * (p[0] * p[0] + p[1] * p[1] + p[2] * p[2])
        }
        return c + SCALE_W * sigma * sigma
    }

    /** Levenberg-Marquardt. Updates [rot] in place, returns the new σ. */
    private fun bundleAdjust(ms: List<Match>, rot: Array<DoubleArray>, sigma0: Double, iters: Int): Double {
        val np = 3 * n + 1
        val cs = 3 * n
        var sigma = sigma0
        var lambda = 1e-3
        var cur = cost(ms, rot, sigma)
        val ui = DoubleArray(3); val uj = DoubleArray(3); val ui2 = DoubleArray(3); val uj2 = DoubleArray(3)
        val cols = IntArray(7); val vals = DoubleArray(7)
        val eps = 1e-6

        for (it in 0 until iters) {
            val a = DoubleArray(np * np); val b = DoubleArray(np)
            val s = exp(sigma); val s2 = exp(sigma + eps)
            for (m in ms) {
                dirs(m, rot, s, ui, uj)
                dirs(m, rot, s2, ui2, uj2)
                val r0 = ui[0] - uj[0]; val r1 = ui[1] - uj[1]; val r2 = ui[2] - uj[2]
                val e = sqrt(r0 * r0 + r1 * r1 + r2 * r2)
                val w = if (e < HUBER) 1.0 else HUBER / e
                val res = doubleArrayOf(r0, r1, r2)
                for (row in 0..2) {
                    // d(ω×u)/dω = −[u]×  →  Jᵢ = −[uᵢ]×,  Jⱼ = +[uⱼ]×
                    skewRow(ui, row, -1.0, vals, 0)
                    skewRow(uj, row, 1.0, vals, 3)
                    vals[6] = ((ui2[row] - uj2[row]) - res[row]) / eps
                    for (c in 0..2) { cols[c] = 3 * m.i + c; cols[3 + c] = 3 * m.j + c }
                    cols[6] = cs
                    for (p in 0 until 7) {
                        val vp = vals[p]; if (vp == 0.0) continue
                        b[cols[p]] += w * vp * res[row]
                        for (q in 0 until 7) a[cols[p] * np + cols[q]] += w * vp * vals[q]
                    }
                }
            }
            for (k in 0 until n) {
                val p = Rot.log(Rot.mul(rot[k], Rot.t(rot0[k])))
                for (c in 0..2) { a[(3 * k + c) * np + 3 * k + c] += PRIOR_W; b[3 * k + c] += PRIOR_W * p[c] }
            }
            a[cs * np + cs] += SCALE_W; b[cs] += SCALE_W * sigma

            var improved = false
            for (attempt in 0 until 6) {
                val damped = a.copyOf()
                for (d in 0 until np) damped[d * np + d] += lambda * a[d * np + d] + 1e-12
                val delta = solve(damped, b, np)
                if (delta == null) { lambda *= 10; continue }
                val cand = Array(n) { k ->
                    Rot.mul(Rot.exp(-delta[3 * k], -delta[3 * k + 1], -delta[3 * k + 2]), rot[k])
                }
                val candSigma = sigma - delta[cs]
                val c = cost(ms, cand, candSigma)
                if (c < cur) {
                    val gain = cur - c
                    for (k in 0 until n) rot[k] = cand[k]
                    sigma = candSigma; cur = c; lambda = max(lambda / 3, 1e-9)
                    improved = true
                    if (gain < 1e-12 * (1 + cur)) return sigma
                    break
                }
                lambda *= 5
            }
            if (!improved) break
        }
        return sigma
    }

    /** Row [row] of sign·[u]×, written to out[off..off+2]. */
    private fun skewRow(u: DoubleArray, row: Int, sign: Double, out: DoubleArray, off: Int) {
        when (row) {
            0 -> { out[off] = 0.0; out[off + 1] = -u[2] * sign; out[off + 2] = u[1] * sign }
            1 -> { out[off] = u[2] * sign; out[off + 1] = 0.0; out[off + 2] = -u[0] * sign }
            else -> { out[off] = -u[1] * sign; out[off + 1] = u[0] * sign; out[off + 2] = 0.0 }
        }
    }

    /** Cholesky solve A·x = b (A symmetric positive definite, row-major). */
    private fun solve(a: DoubleArray, b: DoubleArray, n: Int): DoubleArray? {
        val l = a.copyOf()
        for (j in 0 until n) {
            var s = l[j * n + j]
            for (k in 0 until j) s -= l[j * n + k] * l[j * n + k]
            if (s <= 0) return null
            val d = sqrt(s)
            l[j * n + j] = d
            for (i in j + 1 until n) {
                var t = l[i * n + j]
                for (k in 0 until j) t -= l[i * n + k] * l[j * n + k]
                l[i * n + j] = t / d
            }
        }
        val y = DoubleArray(n)
        for (i in 0 until n) {
            var t = b[i]; for (k in 0 until i) t -= l[i * n + k] * y[k]; y[i] = t / l[i * n + i]
        }
        val x = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            var t = y[i]; for (k in i + 1 until n) t -= l[k * n + i] * x[k]; x[i] = t / l[i * n + i]
        }
        return x
    }
}
