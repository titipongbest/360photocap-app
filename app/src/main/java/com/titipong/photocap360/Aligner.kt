package com.titipong.photocap360

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.IntStream
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
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
class Aligner(
    private val frames: List<FrameImage>,
    private val baseLongEdge: Int = 640,
    /**
     * Whether to also estimate a common FOV scale.
     * With the phone held in front of the body, parallax makes near objects shift more than the
     * rotation and a free solver "explains" that by shrinking the FOV (≈ offset/distance, e.g.
     * 0.2 m / 3 m ≈ 7 %), which wastes coverage. A genuinely wrong Camera2 FOV, on the other hand,
     * leaves a large error everywhere. AUTO solves both ways and uses the free FOV only when it
     * fits far better (rms < 60 % of the fixed-FOV fit).
     */
    private val fovMode: FovMode = FovMode.AUTO,
    /** Fit per-frame mesh warps after the rotations (reduces parallax at the seams). */
    private val meshWarp: Boolean = true,
    /**
     * Model the camera sitting in front of the rotation centre inside a box-shaped room (see [rhoOf]).
     * Without it, a rotation-only fit is biased by parallax (frames tilt/drift to "explain" it).
     */
    private val parallaxModel: Boolean = true,
) {
    enum class FovMode { FIXED, FREE, AUTO }


    class Match(val i: Int, val ax: Double, val ay: Double, val j: Int, val bx: Double, val by: Double)

    class Result(
        val rotations: List<FloatArray>,
        val fovScale: Double,
        val matches: Int,
        val inliers: Int,
        val rmsDeg: Double,
        val applied: Boolean,
        /** Inlier matches touching each frame (few → that frame is held only by the gyro). */
        val perFrame: IntArray = IntArray(0),
        /** Why the result was not applied (empty when applied). */
        val reason: String = "",
        /** Largest correction applied to any frame relative to the gyroscope (deg). */
        val maxDevDeg: Double = 0.0,
        /** True if the FOV was re-estimated (AUTO picked the free fit). */
        val fovEstimated: Boolean = false,
        /** rms (deg) of the fixed-FOV and free-FOV fits, NaN if not computed. */
        val rmsFixedDeg: Double = Double.NaN,
        val rmsFreeDeg: Double = Double.NaN,
        /** Room model: ρ (camera offset / wall distance) and κ (offset / floor-ceiling height). */
        val rhoWall: Double = 0.0,
        val kappa: Double = 0.0,
        /** Largest tilt (pitch/roll) change relative to the gyro (deg). */
        val maxTiltDeg: Double = 0.0,
        /** Per-frame mesh warps (null = none) and the match error before/after warping (deg). */
        val warps: List<FloatArray?> = emptyList(),
        val warpRmsBeforeDeg: Double = Double.NaN,
        val warpRmsAfterDeg: Double = Double.NaN,
        /** Dense seam matches (regular grid in the overlaps, after the rotations are known). */
        val denseMatches: Int = 0,
        /** Per-frame camera positions (option ค): rms with them (deg, NaN = not tried) and largest |u|. */
        val offsetRmsDeg: Double = Double.NaN,
        val maxOffset: Double = 0.0,
        /**
         * Alternatives for [SeamSelect] to choose from by seam quality; the first is this result
         * itself. Empty when the alignment wasn't applied.
         */
        val candidates: List<Candidate> = emptyList(),
    )

    /** One complete alignment hypothesis (what the stitcher needs to render it). */
    class Candidate(
        val name: String,
        val rotations: List<FloatArray>,
        val fovScale: Double,
        val rhoWall: Double,
        val kappa: Double,
        val warps: List<FloatArray?>,
        /** Per-frame camera position (see [ray]); null = all at the nominal spot. */
        val offsets: List<FloatArray>? = null,
    )

    /** Geometry used to predict where a point of frame i lands in frame j. */
    private class Geo(val rot: Array<DoubleArray>, val s: Double, val rw: Double, val kp: Double, val off: Array<DoubleArray>? = null)

    companion object {
        private const val LEVELS = 3
        private val SEARCH = intArrayOf(2, 2, 16)        // per level, 0 = finest
        private val TPL = intArrayOf(7, 6, 5)
        private val MIN_NCC = doubleArrayOf(0.85, 0.7, 0.6)
        private const val MIN_STD = 0.012
        private const val GRID_X = 15
        private const val GRID_Y = 20
        private const val HUBER = 0.004                  // rad ≈ 0.23°
        private const val PRIOR_W = 4e-4                 // yaw: ≈ (0.1° / 5.7°)², gyro yaw drifts
        /** Tilt (pitch/roll) from the gyro is gravity-referenced and accurate: hold it to ≈0.5°. */
        private const val PRIOR_TILT_W = 0.05
        /** Rotation between consecutively captured frames from the gyro: ≈1° (little drift in a few seconds). */
        private const val REL_W = 0.013
        /** Floor shots see the floor ~1.3 m away (big parallax): their matches count this much. */
        private const val FLOOR_WEIGHT = 0.2
        /** Room model priors/bounds: ρ = offset/wall distance, κ = offset/floor-or-ceiling height. */
        private const val RHO0 = 0.08
        private const val KAP0 = 0.15
        private const val ROOM_W = 1.6e-3
        private const val RHO_MAX = 0.3
        private const val KAP_MAX = 0.4
        private const val WARP_SMOOTH = 1.5
        private const val WARP_DAMP = 0.1
        private const val WARP_MAX = 0.12          // normalized units ≈ 3°
        private const val WARP_MIN_SAMPLES = 6
        /** Dense seam matching: grid step (px at the base level), texture threshold, NCC search. */
        private const val DENSE_STEP = 16
        private const val DENSE_MIN_STD = 0.02
        private val DENSE_SEARCH = intArrayOf(2, 2, 4)
        private val DENSE_NCC = doubleArrayOf(0.85, 0.75, 0.65)
        private const val DENSE_AMBIG = 0.05
        private const val SCALE_W_FREE = 4e-4
        /**
         * Per-frame camera position u (fraction of the nominal camera offset, see [ray]): prior weight
         * and bound. |u| = 0.3 ≈ 7 cm of body sway when the camera is 25 cm from the rotation axis.
         */
        private const val OFF_W = 0.01
        private const val OFF_MAX = 0.8
        private const val SCALE_W_FIXED = 1e6
        /**
         * Hard bound on the FOV scale (log): ±10 %. With ray-difference residuals a shrinking FOV
         * shrinks every residual too, so an unbounded solver can collapse to a tiny FOV with frames
         * rotated onto each other (seen on a real capture: ×0.11, frames turned 150°).
         */
        private const val SIGMA_MAX = 0.1
        private const val DEG = Math.PI / 180
    }

    private val n = frames.size
    private var scaleW = SCALE_W_FIXED
    private val frameW = DoubleArray(n) { if (-frames[it].r[8] < -0.8) FLOOR_WEIGHT else 1.0 }
    private val rw0 = if (parallaxModel) RHO0 else 0.0
    private val kp0 = if (parallaxModel) KAP0 else 0.0
    private val rot0 = Array(n) { Rot.ofFloat(frames[it].r) }
    private val th = DoubleArray(n) { frames[it].tanH }
    private val tv = DoubleArray(n) { frames[it].tanV }
    private lateinit var pyr: Array<Array<GrayImage>>
    private val gyroGeo = Geo(rot0, 1.0, rw0, kp0)
    /** AI depth (see [refineWithDepth]): when set, ρ comes from it instead of the room model. */
    private var depthMap: RhoMap? = null
    /** All feature + dense matches of the last [run], for [refineWithDepth]. */
    private var lastMatches: List<Match> = emptyList()

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
            for (f in feats[j]!!) matchFeature(j, i, f[0], f[1])?.let { found += it }
            progress(0.2f + 0.65f * done.incrementAndGet() / pairs.size)
        }
        val all = found.toList()
        if (all.size < 3 * n) return unchanged(all.size)

        // ---- bundle adjustment, outlier removal, again (fixed and/or free FOV)
        class Sol(val rot: Array<DoubleArray>, val g: DoubleArray, val inl: List<Match>, val rms: Double, val maxDev: Double) {
            val sigma get() = g[0]
        }
        fun solve(w: Double): Sol {
            scaleW = w
            val rot = Array(n) { rot0[it].copyOf() }
            val g = doubleArrayOf(0.0, rw0, kp0)
            bundleAdjust(all, rot, g, 20)
            val res = DoubleArray(all.size) { residual(all[it], rot, g) }
            val med = res.sorted()[res.size / 2]
            val thr = max(0.01, 4 * med)
            val inl = all.filterIndexed { k, _ -> res[k] < thr }
            bundleAdjust(inl, rot, g, 20)
            val rms = sqrt(inl.sumOf { val e = residual(it, rot, g); e * e } / max(1, inl.size))
            val dev = (0 until n).maxOf { Rot.angle(Rot.mul(rot[it], Rot.t(rot0[it]))) }
            return Sol(rot, g, inl, rms, dev)
        }
        val fixed = if (fovMode != FovMode.FREE) solve(SCALE_W_FIXED) else null
        val free = if (fovMode != FovMode.FIXED) solve(SCALE_W_FREE) else null
        val sol = when {
            fixed == null -> free!!
            free == null -> fixed
            // Only trust a free FOV that fits far better, isn't pinned at the bound, and didn't need
            // to spin frames around (signs of the degenerate collapse).
            free.rms < 0.6 * fixed.rms && abs(free.sigma) < 0.95 * SIGMA_MAX && free.maxDev < 30 * DEG -> free
            else -> fixed
        }
        val rot = sol.rot; val g = sol.g; val sigma = sol.sigma; val inl = sol.inl; val rms = sol.rms
        progress(0.85f)

        val maxDev = (0 until n).maxOf { Rot.angle(Rot.mul(rot[it], Rot.t(rot0[it]))) }
        // Gyro yaw drifts over a full turn, so large corrections are legitimate as long as the images
        // agree (low rms). Only reject when the solution itself looks unreliable.
        val reasons = ArrayList<String>()
        if (inl.size < max(2 * n, 40)) reasons += "จุดที่ผ่านน้อย (${inl.size})"
        if (rms >= 1.0 * DEG) reasons += "rms สูง %.2f°".format(rms / DEG)
        if (abs(sigma) >= 0.15) reasons += "FOV เพี้ยน ×%.2f".format(exp(sigma))
        if (maxDev >= 30 * DEG) reasons += "บางภาพต้องหมุน %.0f°".format(maxDev / DEG)
        val ok = reasons.isEmpty()
        // Use every match, not just the rotation inliers: the points on near objects are exactly the
        // ones the rotation-only fit rejects. Gross mismatches are dropped inside fitWarps (|e| > 2·max).
        //
        // Dense seam matches (after Google's Street View seam fix): with the rotations known, match a
        // regular grid of points in every overlap, not only corners. Flat-ish walls and the parallax
        // around near objects get many more samples, so a finer warp grid can follow them.
        val dense = if (ok && meshWarp) denseMatches(pairs, Geo(rot, exp(sigma), g[1], g[2])) { p -> progress(0.85f + 0.13f * p) } else emptyList()
        val fine = if (ok && meshWarp) fitWarps(all + dense, rot, g, FrameImage.WARP_GX, FrameImage.WARP_GY) else null
        val coarse = if (ok && meshWarp) fitWarps(all, rot, g, FrameImage.SPARSE_GX, FrameImage.SPARSE_GY) else null
        val wf = fine ?: coarse
        // ค: let every frame have its own camera position (body sway between shots), Hugin TrX/TrY/TrZ-like.
        class Offsets(val rot: Array<DoubleArray>, val off: Array<DoubleArray>, val rms: Double)
        val offsets = if (ok && parallaxModel) try {
            val r2 = Array(n) { rot[it].copyOf() }
            val off = Array(n) { DoubleArray(3) }
            bundleAdjust(inl, r2, g, 20, off)
            Offsets(r2, off, sqrt(inl.sumOf { val e = residual(it, r2, g, off); e * e } / max(1, inl.size)))
        } catch (e: Throwable) { null } else null
        lastMatches = all + dense
        val candidates = ArrayList<Candidate>()
        if (ok) {
            fun cand(name: String, s: Sol, w: WarpFit?) =
                Candidate(name, s.rot.map { Rot.toFloat(it) }, exp(s.sigma), s.g[1], s.g[2], w?.warps ?: List(n) { null })
            val fovName = if (sol === free) "FOV ปรับ" else "FOV กล้อง"
            candidates += cand("$fovName + บิดละเอียด", sol, wf)
            if (coarse != null && fine != null) candidates += cand("$fovName + บิดหยาบ", sol, coarse)
            // ค: per-frame camera positions on top of the chosen solution (room model only).
            if (parallaxModel && offsets != null) {
                val ow = if (meshWarp) fitWarps(all + dense, offsets.rot, sol.g, FrameImage.WARP_GX, FrameImage.WARP_GY, offsets.off) else null
                candidates += Candidate("$fovName + ตำแหน่งกล้อง + บิดละเอียด", offsets.rot.map { Rot.toFloat(it) }, exp(sigma), g[1], g[2],
                    ow?.warps ?: List(n) { null }, offsets.off.map { u -> FloatArray(3) { u[it].toFloat() } })
            }
            // The other FOV hypothesis, if it is sane (reuse the dense matches: they are image facts).
            val other = if (sol === free) fixed else free
            if (other != null && other.rms < 1.0 * DEG && abs(other.sigma) < 0.95 * SIGMA_MAX && other.maxDev < 30 * DEG &&
                other.inl.size >= max(2 * n, 40)) {
                val ow = if (meshWarp) fitWarps(all + dense, other.rot, other.g, FrameImage.WARP_GX, FrameImage.WARP_GY) else null
                candidates += cand(if (other === free) "FOV ปรับ + บิดละเอียด" else "FOV กล้อง + บิดละเอียด", other, ow)
            }
            candidates += Candidate("gyroscope อย่างเดียว", frames.map { it.r }, 1.0, 0.0, 0.0, List(n) { null })
        }
        val maxTilt = (0 until n).maxOf { val p = Rot.log(Rot.mul(rot[it], Rot.t(rot0[it]))); sqrt(p[0] * p[0] + p[1] * p[1]) }
        return Result(
            rotations = if (ok) rot.map { Rot.toFloat(it) } else frames.map { it.r },
            fovScale = if (ok) exp(sigma) else 1.0,
            matches = all.size,
            inliers = inl.size,
            rmsDeg = rms / DEG,
            applied = ok,
            perFrame = IntArray(n).also { c -> for (m in inl) { c[m.i]++; c[m.j]++ } },
            reason = reasons.joinToString(", "),
            maxDevDeg = maxDev / DEG,
            fovEstimated = sol === free,
            rhoWall = if (ok) g[1] else 0.0,
            kappa = if (ok) g[2] else 0.0,
            maxTiltDeg = maxTilt / DEG,
            rmsFixedDeg = (fixed?.rms ?: Double.NaN) / DEG,
            rmsFreeDeg = (free?.rms ?: Double.NaN) / DEG,
            warps = wf?.warps ?: emptyList(),
            warpRmsBeforeDeg = wf?.rmsBefore ?: Double.NaN,
            warpRmsAfterDeg = wf?.rmsAfter ?: Double.NaN,
            denseMatches = dense.size,
            offsetRmsDeg = (offsets?.rms ?: Double.NaN) / DEG,
            maxOffset = offsets?.off?.maxOf { sqrt(it[0] * it[0] + it[1] * it[1] + it[2] * it[2]) } ?: 0.0,
            candidates = candidates,
        ).also { progress(1f) }
    }

    /**
     * Regular grid of points in frame i (every [DENSE_STEP] px) that the solved geometry puts inside
     * frame j, each matched by NCC with a small search around the prediction. One direction per pair.
     */
    private fun denseMatches(pairs: List<IntArray>, geo: Geo, progress: (Float) -> Unit): List<Match> {
        val found = ConcurrentLinkedQueue<Match>()
        val done = AtomicInteger()
        IntStream.range(0, pairs.size).parallel().forEach { p ->
            val (i, j) = pairs[p]
            val g0 = pyr[i][0]
            val margin = (TPL[LEVELS - 1] + 1) * (1 shl (LEVELS - 1))
            val d = DoubleArray(3); val q = DoubleArray(2)
            var y = margin
            while (y < g0.h - margin) {
                var x = margin
                while (x < g0.w - margin) {
                    ray(geo.rot[i], th[i] * geo.s, tv[i] * geo.s, g0.nxOf(x.toDouble()), g0.nyOf(y.toDouble()), d, geo.rw, geo.kp, geo.off?.get(i))
                    if (project(geo.rot[j], th[j] * geo.s, tv[j] * geo.s, d, q, geo.rw, geo.kp, geo.off?.get(j)) && abs(q[0]) < 0.9 && abs(q[1]) < 0.9) {
                        matchFeature(i, j, x.toDouble(), y.toDouble(), geo, DENSE_SEARCH, DENSE_NCC, DENSE_AMBIG, DENSE_MIN_STD)?.let { found += it }
                    }
                    x += DENSE_STEP
                }
                y += DENSE_STEP
            }
            progress(done.incrementAndGet().toFloat() / pairs.size)
        }
        return found.toList()
    }

    /**
     * Per-frame depth scale from the matches: ρ = αₖ·disp + βₖ, so a match a (frame i) ↔ b (frame j)
     * gives two points on the scene sphere (camera at ρ·c, as in [ray]) that must coincide.
     * Gauss-Newton over all (αₖ, βₖ) with rotations fixed and a weak pull toward [init] (the
     * room-model fit). Frames without depth keep their initial fit.
     */
    fun calibrateDepth(c: Candidate, depth: List<DepthImage?>, init: List<DepthFusion.Fit?>): List<DepthFusion.Fit?> {
        val rot = Array(n) { Rot.ofFloat(c.rotations[it]) }
        val s = c.fovScale
        val off = c.offsets?.let { o -> Array(n) { k -> DoubleArray(3) { o[k][it].toDouble() } } }
        val ok = BooleanArray(n) { init.getOrNull(it)?.ok == true && depth.getOrNull(it) != null }
        class M(val i: Int, val ax: Double, val ay: Double, val da: Double, val j: Int, val bx: Double, val by: Double, val db: Double)
        val ms = lastMatches.mapNotNull { m ->
            if (!ok[m.i] || !ok[m.j]) return@mapNotNull null
            val da = DepthFusion.sample(depth[m.i]!!, m.ax, m.ay); val db = DepthFusion.sample(depth[m.j]!!, m.bx, m.by)
            if (da.isNaN() || db.isNaN()) null else M(m.i, m.ax, m.ay, da, m.j, m.bx, m.by, db)
        }
        val p = DoubleArray(2 * n); val p0 = DoubleArray(2 * n)
        for (k in 0 until n) init.getOrNull(k)?.let { p[2 * k] = it.alpha; p[2 * k + 1] = it.beta }
        p.copyInto(p0)
        if (ms.size < 3 * n) return init
        // Prior scale: relative change of α, and β in ρ units (ρ ≈ 0.1).
        val wA = 1e-4; val wB = 1e-4 / (0.1 * 0.1)
        fun rhoOfP(pp: DoubleArray, k: Int, d: Double) = (pp[2 * k] * d + pp[2 * k + 1]).coerceIn(0.005, 0.6)
        val ui = DoubleArray(3); val uj = DoubleArray(3)
        fun res(pp: DoubleArray, m: M, out: DoubleArray) {
            ray(rot[m.i], th[m.i] * s, tv[m.i] * s, m.ax, m.ay, ui, rhoOfP(pp, m.i, m.da), 0.0, off?.get(m.i))
            ray(rot[m.j], th[m.j] * s, tv[m.j] * s, m.bx, m.by, uj, rhoOfP(pp, m.j, m.db), 0.0, off?.get(m.j))
            out[0] = ui[0] - uj[0]; out[1] = ui[1] - uj[1]; out[2] = ui[2] - uj[2]
        }
        fun cost(pp: DoubleArray): Double {
            val r = DoubleArray(3); var cst = 0.0
            for (m in ms) {
                res(pp, m, r); val e = sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2])
                cst += if (e < HUBER) e * e else 2 * HUBER * e - HUBER * HUBER
            }
            for (k in 0 until n) if (ok[k]) {
                val da = (pp[2 * k] - p0[2 * k]) / p0[2 * k]; val db = pp[2 * k + 1] - p0[2 * k + 1]
                cst += wA * da * da + wB * db * db
            }
            return cst
        }
        val np = 2 * n
        var cur = cost(p); var lambda = 1e-3
        val r0 = DoubleArray(3); val r1 = DoubleArray(3)
        val eps = 1e-5
        for (it in 0 until 12) {
            val a = DoubleArray(np * np); val b = DoubleArray(np)
            val idx = IntArray(4); val jac = Array(4) { DoubleArray(3) }
            for (m in ms) {
                res(p, m, r0)
                val e = sqrt(r0[0] * r0[0] + r0[1] * r0[1] + r0[2] * r0[2])
                val w = if (e < HUBER) 1.0 else HUBER / e
                idx[0] = 2 * m.i; idx[1] = 2 * m.i + 1; idx[2] = 2 * m.j; idx[3] = 2 * m.j + 1
                for (q in 0..3) {
                    val keep = p[idx[q]]; p[idx[q]] = keep + eps * (if (q % 2 == 0) max(1.0, abs(keep)) else 1.0)
                    val h = p[idx[q]] - keep
                    res(p, m, r1); p[idx[q]] = keep
                    for (c3 in 0..2) jac[q][c3] = (r1[c3] - r0[c3]) / h
                }
                for (q in 0..3) {
                    for (c3 in 0..2) b[idx[q]] += w * jac[q][c3] * r0[c3]
                    for (q2 in 0..3) { var v = 0.0; for (c3 in 0..2) v += jac[q][c3] * jac[q2][c3]; a[idx[q] * np + idx[q2]] += w * v }
                }
            }
            for (k in 0 until n) {
                if (!ok[k]) { a[2 * k * np + 2 * k] += 1e9; a[(2 * k + 1) * np + 2 * k + 1] += 1e9; continue }
                val sa = wA / (p0[2 * k] * p0[2 * k])
                a[2 * k * np + 2 * k] += sa; b[2 * k] += sa * (p[2 * k] - p0[2 * k])
                a[(2 * k + 1) * np + 2 * k + 1] += wB; b[2 * k + 1] += wB * (p[2 * k + 1] - p0[2 * k + 1])
            }
            var improved = false
            for (attempt in 0 until 6) {
                val dmp = a.copyOf()
                for (d in 0 until np) dmp[d * np + d] += lambda * a[d * np + d] + 1e-15
                val delta = solve(dmp, b, np)
                if (delta == null) { lambda *= 10; continue }
                val cand = DoubleArray(np) { p[it] - delta[it] }
                for (k in 0 until n) if (cand[2 * k] <= 0) cand[2 * k] = p[2 * k] * 0.5
                val cc = cost(cand)
                if (cc < cur) {
                    val gain = cur - cc
                    cand.copyInto(p); cur = cc; lambda = max(lambda / 3, 1e-9); improved = true
                    if (gain < 1e-10 * (1 + cur)) return fitsOf(p, ok, init)
                    break
                }
                lambda *= 5
            }
            if (!improved) break
        }
        return fitsOf(p, ok, init)
    }

    private fun fitsOf(p: DoubleArray, ok: BooleanArray, init: List<DepthFusion.Fit?>) =
        List(n) { k -> if (ok[k]) DepthFusion.Fit(p[2 * k], p[2 * k + 1], p[2 * k] > 0) else init.getOrNull(k) }

    /** Result of [refineWithDepth]: the candidate re-warped for AI depth, and match errors (deg). */
    class DepthRefit(val candidate: Candidate, val rmsRoomDeg: Double, val rmsDepthDeg: Double, val warpAfterDeg: Double)

    /**
     * Re-fits the mesh warps of [c] with ρ from [map] (AI depth) instead of the room model, using the
     * matches of the last [run]. Rotations and camera positions stay. Also reports how well the
     * matches agree under each depth model (the warps were fitted to the room model's leftovers, so
     * keeping them with AI depth would correct twice).
     */
    fun refineWithDepth(c: Candidate, map: RhoMap): DepthRefit {
        val rot = Array(n) { Rot.ofFloat(c.rotations[it]) }
        val g = doubleArrayOf(ln(c.fovScale), c.rhoWall, c.kappa)
        val off = c.offsets?.let { o -> Array(n) { k -> DoubleArray(3) { o[k][it].toDouble() } } }
        val ms = lastMatches
        fun rms(): Double {
            if (ms.isEmpty()) return Double.NaN
            val e = ms.map { residual(it, rot, g, off) }.sorted()
            val keep = e.take((e.size * 0.9).toInt().coerceAtLeast(1)) // drop gross mismatches
            return sqrt(keep.sumOf { it * it } / keep.size) / DEG
        }
        depthMap = null
        val before = rms()
        depthMap = map
        try {
            // Re-solve the rotations (and camera positions) under the AI depth: they were bent to
            // fit the room model's errors. Gross mismatches out first, globals held.
            val res0 = ms.map { residual(it, rot, g, off) }
            val med = res0.sorted().getOrElse(res0.size / 2) { 0.0 }
            val inl = ms.filterIndexed { k, _ -> res0[k] < max(0.01, 4 * med) }
            scaleW = SCALE_W_FIXED
            if (inl.size >= 3 * n) bundleAdjust(inl, rot, g, 15, off)
            val after = rms()
            val w = if (meshWarp && ms.isNotEmpty()) fitWarps(ms, rot, g, FrameImage.WARP_GX, FrameImage.WARP_GY, off) else null
            return DepthRefit(
                Candidate(c.name + " + AI ความลึก", rot.map { Rot.toFloat(it) }, c.fovScale, c.rhoWall, c.kappa, w?.warps ?: List(n) { null },
                    off?.map { u -> FloatArray(3) { u[it].toFloat() } }),
                before, after, w?.rmsAfter ?: Double.NaN,
            )
        } finally { depthMap = null }
    }

    // ---------------- mesh warp ----------------

    private class WarpFit(val warps: List<FloatArray?>, val rmsBefore: Double, val rmsAfter: Double)

    /**
     * Parallax can't be removed by rotating whole frames: near objects stay offset. Give each frame
     * a smooth displacement field D on a WARP_GX × WARP_GY grid (FrameImage.warp).
     *
     * For a match a (in i) ↔ b (in j) with final rotations, eⱼ = b − projⱼ(rayᵢ(a)) and
     * eᵢ = a − projᵢ(rayⱼ(b)) are what is left over. Moving each frame half way —
     *     Dᵢ(a) ≈ eᵢ/2,   Dⱼ(b) ≈ eⱼ/2
     * puts both images of the point on the same in-between direction. Each frame's grid is then a
     * small regularised least-squares fit:
     *     Σ_samples |bilinear(D)(pos) − target|² + λs Σ_neighbours |D_k − D_l|² + λ0 Σ |D_k|²
     */
    private fun fitWarps(inl: List<Match>, rot: Array<DoubleArray>, g: DoubleArray, gx: Int, gy: Int, off: Array<DoubleArray>? = null): WarpFit {
        val s = exp(g[0]); val rw = g[1]; val kp = g[2]
        val nodes = gx * gy
        // Finer grids have more nodes per sample: scale the pull toward zero so the total stays alike.
        val damp = WARP_DAMP * (FrameImage.SPARSE_GX * FrameImage.SPARSE_GY).toDouble() / nodes
        val minSamples = WARP_MIN_SAMPLES * max(1, nodes / (FrameImage.SPARSE_GX * FrameImage.SPARSE_GY))
        val d = DoubleArray(3); val q = DoubleArray(2)
        var sumBefore = 0.0; var cnt = 0
        val errs = ArrayList<DoubleArray>() // per match: i, ax, ay, j, bx, by, ejx, ejy, eix, eiy
        for (m in inl) {
            val ui = off?.get(m.i); val uj = off?.get(m.j)
            ray(rot[m.i], th[m.i] * s, tv[m.i] * s, m.ax, m.ay, d, rw, kp, ui)
            if (!project(rot[m.j], th[m.j] * s, tv[m.j] * s, d, q, rw, kp, uj)) continue
            val ejx = m.bx - q[0]; val ejy = m.by - q[1]
            ray(rot[m.j], th[m.j] * s, tv[m.j] * s, m.bx, m.by, d, rw, kp, uj)
            if (!project(rot[m.i], th[m.i] * s, tv[m.i] * s, d, q, rw, kp, ui)) continue
            val eix = m.ax - q[0]; val eiy = m.ay - q[1]
            if (abs(ejx) > WARP_MAX * 2 || abs(ejy) > WARP_MAX * 2) continue
            val a = angle(ejx, ejy, m.j, s)
            sumBefore += a * a; cnt++
            errs += doubleArrayOf(m.i.toDouble(), m.ax, m.ay, m.j.toDouble(), m.bx, m.by, ejx, ejy, eix, eiy)
        }
        if (cnt == 0) return WarpFit(List(n) { null }, Double.NaN, Double.NaN)

        val tmp = DoubleArray(2)
        fun after(e: DoubleArray, warps: Array<FloatArray?>): Double {
            val i = e[0].toInt(); val j = e[3].toInt()
            var rx = e[6]; var ry = e[7]
            warpAt(warps[j], e[4], e[5], tmp, gx, gy); rx -= tmp[0]; ry -= tmp[1]
            warpAt(warps[i], e[1], e[2], tmp, gx, gy); rx += tmp[0]; ry += tmp[1]
            return angle(rx, ry, j, s)
        }
        val warps = solveWarps(errs, gx, gy, damp, minSamples)

        // Error after (all samples, so the number is comparable with "before").
        var sumAfter = 0.0
        for (e in errs) { val a = after(e, warps); sumAfter += a * a }
        return WarpFit(warps.toList(), sqrt(sumBefore / cnt) / DEG, sqrt(sumAfter / cnt) / DEG)
    }

    /** Regularised least-squares displacement grid per frame from (i,a,j,b,eⱼ,eᵢ) samples. */
    private fun solveWarps(errs: List<DoubleArray>, gx: Int, gy: Int, damp: Double, minSamples: Int): Array<FloatArray?> {
        val nodes = gx * gy
        val samples = Array(n) { ArrayList<DoubleArray>() } // (x, y, tx, ty)
        for (e in errs) {
            samples[e[0].toInt()] += doubleArrayOf(e[1], e[2], e[8] / 2, e[9] / 2)
            samples[e[3].toInt()] += doubleArrayOf(e[4], e[5], e[6] / 2, e[7] / 2)
        }
        val warps = Array<FloatArray?>(n) { null }
        val wts = DoubleArray(4); val idx = IntArray(4)
        for (f in 0 until n) {
            val smp = samples[f]
            if (smp.size < minSamples) continue
            val a = DoubleArray(nodes * nodes); val bx = DoubleArray(nodes); val by = DoubleArray(nodes)
            for (sm in smp) {
                bilinear(sm[0], sm[1], idx, wts, gx, gy)
                for (u in 0..3) {
                    bx[idx[u]] += wts[u] * sm[2]; by[idx[u]] += wts[u] * sm[3]
                    for (v in 0..3) a[idx[u] * nodes + idx[v]] += wts[u] * wts[v]
                }
            }
            fun link(k: Int, l: Int) {
                a[k * nodes + k] += WARP_SMOOTH; a[l * nodes + l] += WARP_SMOOTH
                a[k * nodes + l] -= WARP_SMOOTH; a[l * nodes + k] -= WARP_SMOOTH
            }
            for (y in 0 until gy) for (x in 0 until gx) {
                val k = y * gx + x
                if (x + 1 < gx) link(k, k + 1)
                if (y + 1 < gy) link(k, k + gx)
                a[k * nodes + k] += damp
            }
            val sx = solve(a, bx, nodes) ?: continue
            val sy = solve(a, by, nodes) ?: continue
            warps[f] = FloatArray(nodes * 2) { i ->
                val v = if (i % 2 == 0) sx[i / 2] else sy[i / 2]
                v.coerceIn(-WARP_MAX, WARP_MAX).toFloat()
            }
        }
        return warps
    }

    /** Normalized-coordinate offset in frame f → approximate angle (rad). */
    private fun angle(x: Double, y: Double, f: Int, s: Double): Double {
        val ax = x * th[f] * s; val ay = y * tv[f] * s
        return sqrt(ax * ax + ay * ay)
    }

    private fun bilinear(nx: Double, ny: Double, idx: IntArray, w: DoubleArray, gx: Int, gy: Int) {
        val fxg = ((nx + 1) / 2 * (gx - 1)).coerceIn(0.0, gx - 1.0)
        val fyg = ((ny + 1) / 2 * (gy - 1)).coerceIn(0.0, gy - 1.0)
        val x0 = min(fxg.toInt(), gx - 2); val y0 = min(fyg.toInt(), gy - 2)
        val fx = fxg - x0; val fy = fyg - y0
        idx[0] = y0 * gx + x0; idx[1] = idx[0] + 1; idx[2] = idx[0] + gx; idx[3] = idx[2] + 1
        w[0] = (1 - fx) * (1 - fy); w[1] = fx * (1 - fy); w[2] = (1 - fx) * fy; w[3] = fx * fy
    }

    private fun warpAt(w: FloatArray?, nx: Double, ny: Double, out: DoubleArray, gx: Int, gy: Int) {
        if (w == null) { out[0] = 0.0; out[1] = 0.0; return }
        val idx = IntArray(4); val wt = DoubleArray(4)
        bilinear(nx, ny, idx, wt, gx, gy)
        out[0] = 0.0; out[1] = 0.0
        for (u in 0..3) { out[0] += wt[u] * w[idx[u] * 2]; out[1] += wt[u] * w[idx[u] * 2 + 1] }
    }

    private fun unchanged(matches: Int) =
        Result(frames.map { it.r }, 1.0, matches, 0, 0.0, false, reason = "จับคู่ได้น้อย ($matches จุด)")

    // ---------------- geometry ----------------

    /**
     * Room model. The camera sits ρ·D in front of the rotation centre (D = distance of the scene point),
     * i.e. at (0,0,−ρ) in device coordinates on the unit "scene sphere". Depth depends on direction:
     * walls at D_wall, floor/ceiling at height h, so on the unit sphere
     *     ρ(d) = max(ρ_wall, κ·|d.z|),   ρ_wall = offset/D_wall,  κ = offset/h.
     * ρ = 0 is the plain rotating-camera model.
     */
    private fun rhoOf(dz: Double, rw: Double, kp: Double) = min(0.6, max(rw, kp * abs(dz)))

    /** Pixel (nx, ny) of a frame → world direction of the scene point from the rotation centre. */
    private fun ray(
        r: DoubleArray, tanH: Double, tanV: Double, nx: Double, ny: Double, out: DoubleArray,
        rw: Double = 0.0, kp: Double = 0.0, u: DoubleArray? = null,
    ) {
        var vx = nx * tanH; var vy = ny * tanV; var vz = -1.0
        val vn = sqrt(vx * vx + vy * vy + vz * vz); vx /= vn; vy /= vn; vz /= vn
        // Camera centre (device coords, in units of ρ): nominal (0,0,−1), moved by this frame's u.
        val ccx = u?.get(0) ?: 0.0; val ccy = u?.get(1) ?: 0.0; val ccz = -1.0 - (u?.get(2) ?: 0.0)
        val cdotv = ccx * vx + ccy * vy + ccz * vz
        val cc = ccx * ccx + ccy * ccy + ccz * ccz
        var rho = rw
        for (iter in 0 until (if (depthMap != null) 6 else 3)) {
            // Point P = ρ·c + s·v with |P| = 1.
            val cv = rho * cdotv
            val sp = -cv + sqrt(max(0.0, cv * cv - rho * rho * cc + 1))
            val px = rho * ccx + sp * vx; val py = rho * ccy + sp * vy; val pz = rho * ccz + sp * vz
            out[0] = r[0] * px + r[1] * py + r[2] * pz
            out[1] = r[3] * px + r[4] * py + r[5] * pz
            out[2] = r[6] * px + r[7] * py + r[8] * pz
            val dm = depthMap
            if (dm == null && kp == 0.0) break
            val rho2 = dm?.at(out[0], out[1], out[2]) ?: rhoOf(out[2], rw, kp)
            if (abs(rho2 - rho) < 1e-7) break
            rho = rho2
        }
    }

    /** World direction (from the rotation centre) → normalized image coords; false if behind the camera. */
    private fun project(
        r: DoubleArray, tanH: Double, tanV: Double, d: DoubleArray, out: DoubleArray,
        rw: Double = 0.0, kp: Double = 0.0, u: DoubleArray? = null,
    ): Boolean {
        val dm = depthMap
        val rho = if (dm != null) dm.at(d[0], d[1], d[2]) else if (rw == 0.0 && kp == 0.0) 0.0 else rhoOf(d[2], rw, kp)
        val px = r[0] * d[0] + r[3] * d[1] + r[6] * d[2] - rho * (u?.get(0) ?: 0.0)
        val py = r[1] * d[0] + r[4] * d[1] + r[7] * d[2] - rho * (u?.get(1) ?: 0.0)
        val pz = r[2] * d[0] + r[5] * d[1] + r[8] * d[2] + rho * (1 + (u?.get(2) ?: 0.0))
        val depth = -pz
        if (depth < 1e-6) return false
        out[0] = px / depth / tanH; out[1] = py / depth / tanV
        return true
    }

    /** Pixel of j (at gj's level) → pixel of i (at gi's level) using the gyro rotations. */
    private fun jToI(j: Int, i: Int, gj: GrayImage, gi: GrayImage, x: Double, y: Double, d: DoubleArray, q: DoubleArray, geo: Geo): Boolean {
        ray(geo.rot[j], th[j] * geo.s, tv[j] * geo.s, gj.nxOf(x), gj.nyOf(y), d, geo.rw, geo.kp, geo.off?.get(j))
        if (!project(geo.rot[i], th[i] * geo.s, tv[i] * geo.s, d, q, geo.rw, geo.kp, geo.off?.get(i))) return false
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
        for (c in best.indices) if (best[c] > 0.004f * gmax && best[c] > 1e-8f) out += doubleArrayOf(bx[c].toDouble(), by[c].toDouble())
        return out
    }

    // ---------------- matching ----------------

    private fun matchFeature(
        i: Int, j: Int, fx: Double, fy: Double, geo: Geo = gyroGeo,
        search: IntArray = SEARCH, minNcc: DoubleArray = MIN_NCC, ambig: Double = 0.1, minStd: Double = MIN_STD,
    ): Match? {
        val g0 = pyr[i][0]
        val anx = g0.nxOf(fx); val any = g0.nyOf(fy)
        val d = DoubleArray(3); val q = DoubleArray(2)
        ray(geo.rot[i], th[i] * geo.s, tv[i] * geo.s, anx, any, d, geo.rw, geo.kp, geo.off?.get(i))
        if (!project(geo.rot[j], th[j] * geo.s, tv[j] * geo.s, d, q, geo.rw, geo.kp, geo.off?.get(j))) return null
        if (abs(q[0]) > 0.95 || abs(q[1]) > 0.95) return null
        var cnx = q[0]; var cny = q[1]

        for (lvl in LEVELS - 1 downTo 0) {
            val gi = pyr[i][lvl]; val gj = pyr[j][lvl]
            val s = search[lvl]; val r = TPL[lvl]
            val cx = gj.pxOf(cnx).roundToInt(); val cy = gj.pyOf(cny).roundToInt()
            val oxMin = max(-s, r - cx); val oxMax = min(s, gj.w - 1 - r - cx)
            val oyMin = max(-s, r - cy); val oyMax = min(s, gj.h - 1 - r - cy)
            if (oxMin > oxMax || oyMin > oyMax) return null

            // Template = i's neighbourhood of the feature, warped into j's pixel grid.
            val pix = gi.pxOf(anx); val piy = gi.pyOf(any)
            if (!jToI(j, i, gj, gi, cx.toDouble(), cy.toDouble(), d, q, geo)) return null
            val hcx = q[0]; val hcy = q[1]
            val side = 2 * r + 1; val nT = side * side
            val tpl = FloatArray(nT)
            var k = 0; var sum = 0.0
            for (dy in -r..r) for (dx in -r..r) {
                if (!jToI(j, i, gj, gi, (cx + dx).toDouble(), (cy + dy).toDouble(), d, q, geo)) return null
                val v = gi.sample(pix + q[0] - hcx, piy + q[1] - hcy)
                if (v.isNaN()) return null
                tpl[k++] = v; sum += v
            }
            val mean = (sum / nT).toFloat()
            var ss = 0.0
            for (t in 0 until nT) { tpl[t] -= mean; ss += (tpl[t] * tpl[t]).toDouble() }
            if (sqrt(ss / nT) < minStd) return null
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
            if (best < minNcc[lvl]) return null
            if (lvl == LEVELS - 1) {
                // Peak on the edge of the search window → true match is outside. Ambiguous → repetitive texture.
                if (bx == oxMin || bx == oxMax || by == oyMin || by == oyMax) return null
                var second = -2.0
                for (oy in oyMin..oyMax) for (ox in oxMin..oxMax) {
                    if (max(abs(ox - bx), abs(oy - by)) > 2) second = max(second, score[(oy + s) * sw + (ox + s)])
                }
                if (best - second < ambig) return null
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
    //
    // Unknowns: a small world-frame rotation ωₖ per frame (Rₖ ← exp([ωₖ]×)·Rₖ) and three globals
    // g = (σ = log FOV scale, ρ_wall, κ). Cost:
    //   Σ_matches wᵢⱼ·Huber(|uᵢ − uⱼ|)                       uᵢ = rayᵢ(a), uⱼ = rayⱼ(b) on the scene sphere
    // + Σₖ [ W_tilt·(pₓ² + p_y²) + W_yaw·p_z² ]               p = log(Rₖ·R0ₖᵀ): stay level, yaw may drift
    // + Σ_consecutive W_rel·|log(Rₖ₊₁Rₖᵀ·(R0ₖ₊₁R0ₖᵀ)ᵀ)|²     keep the gyro's frame-to-frame rotation
    // + scaleW·σ² + W_room·((ρ_wall−ρ₀)² + (κ−κ₀)²)
    // wᵢⱼ = min(frame weights): floor shots count 20 %.

    private fun dirs(m: Match, rot: Array<DoubleArray>, g: DoubleArray, ui: DoubleArray, uj: DoubleArray, off: Array<DoubleArray>? = null) {
        val s = exp(g[0])
        ray(rot[m.i], th[m.i] * s, tv[m.i] * s, m.ax, m.ay, ui, g[1], g[2], off?.get(m.i))
        ray(rot[m.j], th[m.j] * s, tv[m.j] * s, m.bx, m.by, uj, g[1], g[2], off?.get(m.j))
    }

    private fun residual(m: Match, rot: Array<DoubleArray>, g: DoubleArray, off: Array<DoubleArray>? = null): Double {
        val ui = DoubleArray(3); val uj = DoubleArray(3)
        dirs(m, rot, g, ui, uj, off)
        val a = ui[0] - uj[0]; val b = ui[1] - uj[1]; val c = ui[2] - uj[2]
        return sqrt(a * a + b * b + c * c)
    }

    private fun matchW(m: Match) = min(frameW[m.i], frameW[m.j])

    private fun relError(rot: Array<DoubleArray>, k: Int): DoubleArray =
        Rot.log(Rot.mul(Rot.mul(rot[k + 1], Rot.t(rot[k])), Rot.t(Rot.mul(rot0[k + 1], Rot.t(rot0[k])))))

    private fun roomW() = if (parallaxModel) ROOM_W else SCALE_W_FIXED

    private fun cost(ms: List<Match>, rot: Array<DoubleArray>, g: DoubleArray, off: Array<DoubleArray>? = null): Double {
        var c = 0.0
        if (off != null) for (u in off) c += OFF_W * (u[0] * u[0] + u[1] * u[1] + u[2] * u[2])
        for (m in ms) {
            val e = residual(m, rot, g, off)
            c += matchW(m) * (if (e < HUBER) e * e else 2 * HUBER * e - HUBER * HUBER)
        }
        for (k in 0 until n) {
            val p = Rot.log(Rot.mul(rot[k], Rot.t(rot0[k])))
            c += PRIOR_TILT_W * (p[0] * p[0] + p[1] * p[1]) + PRIOR_W * p[2] * p[2]
        }
        for (k in 0 until n - 1) {
            val e = relError(rot, k)
            c += REL_W * (e[0] * e[0] + e[1] * e[1] + e[2] * e[2])
        }
        val dr = g[1] - rw0; val dk = g[2] - kp0
        return c + scaleW * g[0] * g[0] + roomW() * (dr * dr + dk * dk)
    }

    /** Levenberg-Marquardt. Updates [rot] and [g] in place. */
    /**
     * Levenberg-Marquardt. Updates [rot] and [g] in place. With [off] (option ค) it also solves a
     * camera position per frame; the globals are then held fixed (a common shift of all cameras
     * along the axis is the same as changing ρ).
     */
    private fun bundleAdjust(ms: List<Match>, rot: Array<DoubleArray>, g: DoubleArray, iters: Int, off: Array<DoubleArray>? = null) {
        val ng = 3
        val no = if (off != null) 3 * n else 0
        val np = 3 * n + ng + no
        val g0 = 3 * n
        val o0 = 3 * n + ng
        var lambda = 1e-3
        var cur = cost(ms, rot, g, off)
        val ui = DoubleArray(3); val uj = DoubleArray(3)
        val pi = Array(ng + 6) { DoubleArray(3) }; val pj = Array(ng + 6) { DoubleArray(3) }
        val nc = 6 + ng + (if (off != null) 6 else 0)
        val cols = IntArray(nc); val vals = DoubleArray(nc)
        val eps = 1e-6

        for (it in 0 until iters) {
            val a = DoubleArray(np * np); val b = DoubleArray(np)
            for (m in ms) {
                dirs(m, rot, g, ui, uj, off)
                for (q in 0 until ng) {
                    val gp = g.copyOf(); gp[q] += eps
                    dirs(m, rot, gp, pi[q], pj[q], off)
                }
                if (off != null) for (q in 0 until 6) {
                    val f = if (q < 3) m.i else m.j
                    val keep = off[f][q % 3]
                    off[f][q % 3] = keep + eps
                    dirs(m, rot, g, pi[ng + q], pj[ng + q], off)
                    off[f][q % 3] = keep
                }
                val r0 = ui[0] - uj[0]; val r1 = ui[1] - uj[1]; val r2 = ui[2] - uj[2]
                val e = sqrt(r0 * r0 + r1 * r1 + r2 * r2)
                val w = matchW(m) * (if (e < HUBER) 1.0 else HUBER / e)
                val res = doubleArrayOf(r0, r1, r2)
                for (row in 0..2) {
                    // d(ω×u)/dω = −[u]×  →  Jᵢ = −[uᵢ]×,  Jⱼ = +[uⱼ]×
                    skewRow(ui, row, -1.0, vals, 0)
                    skewRow(uj, row, 1.0, vals, 3)
                    for (q in 0 until ng) vals[6 + q] = ((pi[q][row] - pj[q][row]) - res[row]) / eps
                    for (c in 0..2) { cols[c] = 3 * m.i + c; cols[3 + c] = 3 * m.j + c }
                    for (q in 0 until ng) cols[6 + q] = g0 + q
                    if (off != null) for (q in 0 until 6) {
                        vals[6 + ng + q] = ((pi[ng + q][row] - pj[ng + q][row]) - res[row]) / eps
                        cols[6 + ng + q] = o0 + 3 * (if (q < 3) m.i else m.j) + q % 3
                    }
                    for (p in cols.indices) {
                        val vp = vals[p]; if (vp == 0.0) continue
                        b[cols[p]] += w * vp * res[row]
                        for (q in cols.indices) a[cols[p] * np + cols[q]] += w * vp * vals[q]
                    }
                }
            }
            // Absolute priors: tilt strong, yaw weak.
            for (k in 0 until n) {
                val p = Rot.log(Rot.mul(rot[k], Rot.t(rot0[k])))
                for (c in 0..2) {
                    val w = if (c < 2) PRIOR_TILT_W else PRIOR_W
                    a[(3 * k + c) * np + 3 * k + c] += w; b[3 * k + c] += w * p[c]
                }
            }
            // Relative priors between consecutive frames: e ≈ e₀ + ωₖ₊₁ − A₀·ωₖ, A₀ = R0ₖ₊₁R0ₖᵀ.
            for (k in 0 until n - 1) {
                val e = relError(rot, k)
                val a0 = Rot.mul(rot0[k + 1], Rot.t(rot0[k]))
                val i1 = 3 * (k + 1); val i0 = 3 * k
                for (r in 0..2) {
                    // row r of J: +1 at (i1+r), −A0[r][c] at (i0+c)
                    a[(i1 + r) * np + i1 + r] += REL_W
                    b[i1 + r] += REL_W * e[r]
                    for (c in 0..2) {
                        a[(i1 + r) * np + i0 + c] -= REL_W * a0[r * 3 + c]
                        a[(i0 + c) * np + i1 + r] -= REL_W * a0[r * 3 + c]
                        b[i0 + c] -= REL_W * a0[r * 3 + c] * e[r]
                        for (c2 in 0..2) a[(i0 + c) * np + i0 + c2] += REL_W * a0[r * 3 + c] * a0[r * 3 + c2]
                    }
                }
            }
            a[g0 * np + g0] += scaleW; b[g0] += scaleW * g[0]
            a[(g0 + 1) * np + g0 + 1] += roomW(); b[g0 + 1] += roomW() * (g[1] - rw0)
            a[(g0 + 2) * np + g0 + 2] += roomW(); b[g0 + 2] += roomW() * (g[2] - kp0)
            if (off != null) {
                for (q in 0 until ng) a[(g0 + q) * np + g0 + q] += 1e9  // hold the globals
                for (k in 0 until n) for (c in 0..2) {
                    a[(o0 + 3 * k + c) * np + o0 + 3 * k + c] += OFF_W; b[o0 + 3 * k + c] += OFF_W * off[k][c]
                }
            }

            var improved = false
            for (attempt in 0 until 6) {
                val damped = a.copyOf()
                for (d in 0 until np) damped[d * np + d] += lambda * a[d * np + d] + 1e-12
                val delta = solve(damped, b, np)
                if (delta == null) { lambda *= 10; continue }
                val cand = Array(n) { k ->
                    Rot.mul(Rot.exp(-delta[3 * k], -delta[3 * k + 1], -delta[3 * k + 2]), rot[k])
                }
                val cg = doubleArrayOf(
                    (g[0] - delta[g0]).coerceIn(-SIGMA_MAX, SIGMA_MAX),
                    if (parallaxModel) (g[1] - delta[g0 + 1]).coerceIn(0.0, RHO_MAX) else 0.0,
                    if (parallaxModel) (g[2] - delta[g0 + 2]).coerceIn(0.0, KAP_MAX) else 0.0,
                )
                val co = off?.let { o -> Array(n) { k -> DoubleArray(3) { c -> (o[k][c] - delta[o0 + 3 * k + c]).coerceIn(-OFF_MAX, OFF_MAX) } } }
                if (off != null) g.copyInto(cg)
                val c = cost(ms, cand, cg, co)
                if (c < cur) {
                    val gain = cur - c
                    for (k in 0 until n) rot[k] = cand[k]
                    if (off != null) for (k in 0 until n) co!![k].copyInto(off[k])
                    cg.copyInto(g); cur = c; lambda = max(lambda / 3, 1e-9)
                    improved = true
                    if (gain < 1e-12 * (1 + cur)) return
                    break
                }
                lambda *= 5
            }
            if (!improved) break
        }
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
