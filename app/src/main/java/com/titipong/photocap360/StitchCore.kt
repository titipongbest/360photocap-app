package com.titipong.photocap360

import java.util.stream.IntStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A decoded, upright (portrait) frame plus its orientation. Pixels are ARGB ints. */
class FrameImage(
    val pixels: IntArray,
    val width: Int,
    val height: Int,
    val r: FloatArray,      // device → world, row-major
    val tanH: Double,       // tan(hFOV/2) of this image
    val tanV: Double,       // tan(vFOV/2)
    /**
     * Optional mesh warp (see Aligner.fitWarps): displacements on a gx × gy grid over the frame
     * (WARP_GX × WARP_GY, or the older/coarser SPARSE_GX × SPARSE_GY, told apart by the array size),
     * in normalized image coords, interleaved (dx, dy). The point the rotation model puts at
     * (nx, ny) is actually found in the image at (nx, ny) + D(nx, ny). Unknown sizes are ignored.
     */
    warp: FloatArray? = null,
    /**
     * Optional camera position of this frame (Aligner option ค), in units of the room-model offset:
     * the camera sits at ρ(d)·(ux, uy, −(1+uz)) in device coords instead of ρ(d)·(0, 0, −1).
     */
    val offset: FloatArray? = null,
) {
    val warp: FloatArray? = warp?.takeIf { gridOf(it.size) != null }
    private val wgx = this.warp?.let { gridOf(it.size)!![0] } ?: 0
    private val wgy = this.warp?.let { gridOf(it.size)!![1] } ?: 0

    internal val fwdX = -r[2].toDouble()
    internal val fwdY = -r[5].toDouble()
    internal val fwdZ = -r[8].toDouble()
    /** Horizontal length of the viewing direction (≈ 0 for a straight-down/up shot). */
    internal val fwdH = sqrt(fwdX * fwdX + fwdY * fwdY)
    /** cos of the half-diagonal FOV: directions farther than this from the axis can't be inside. */
    internal val cosCull = cos(atan(sqrt(tanH * tanH + tanV * tanV)))

    /** Bilinear mesh-warp displacement at (nx, ny) → out[0], out[1] (zero without a warp). */
    fun warpAt(nx: Double, ny: Double, out: DoubleArray) {
        val w = warp
        if (w == null) { out[0] = 0.0; out[1] = 0.0; return }
        val gw = wgx; val gh = wgy
        val gx = ((nx + 1) / 2 * (gw - 1)).coerceIn(0.0, gw - 1.0)
        val gy = ((ny + 1) / 2 * (gh - 1)).coerceIn(0.0, gh - 1.0)
        val x0 = min(gx.toInt(), gw - 2); val y0 = min(gy.toInt(), gh - 2)
        val fx = gx - x0; val fy = gy - y0
        val i00 = (y0 * gw + x0) * 2; val i10 = i00 + 2
        val i01 = i00 + gw * 2; val i11 = i01 + 2
        for (c in 0..1) {
            val top = w[i00 + c] + (w[i10 + c] - w[i00 + c]) * fx
            val bot = w[i01 + c] + (w[i11 + c] - w[i01 + c]) * fx
            out[c] = top + (bot - top) * fy
        }
    }

    companion object {
        /** Fine grid fitted from dense seam matches. */
        const val WARP_GX = 12
        const val WARP_GY = 16
        /** Coarse grid (feature matches only; also what older align.json/manual.json files hold). */
        const val SPARSE_GX = 6
        const val SPARSE_GY = 8

        fun gridOf(size: Int): IntArray? = when (size) {
            2 * WARP_GX * WARP_GY -> intArrayOf(WARP_GX, WARP_GY)
            2 * SPARSE_GX * SPARSE_GY -> intArrayOf(SPARSE_GX, SPARSE_GY)
            else -> null
        }
    }
}

/**
 * Equirectangular stitcher (inverse mapping, pure Kotlin so it can be unit-tested).
 *
 * For each output pixel (u, v) of a W×H panorama:
 *   lon = (u + ½)/W · 2π − π           lat = π/2 − (v + ½)/H · π
 *   d   = (cos lat · sin(lon+yaw0), cos lat · cos(lon+yaw0), sin lat)     (world)
 * For every frame i whose axis is within its half-diagonal FOV of d:
 *   p = Rᵢᵀ·d,  depth = −p.z
 *   nx = p.x/depth / tanH,  ny = p.y/depth / tanV        (inside if |nx|,|ny| < 1)
 *   px = (nx+1)/2 · w − ½,  py = (1−ny)/2 · h − ½         → bilinear sample (pixel-centre convention)
 *   margin = min((1−|nx|)·tanH, (1−|ny|)·tanV)            (≈ angular distance to the frame edge)
 *
 * Without [prepare] (plain mode) the frame with the largest margin owns the pixel and other frames
 * only crossfade in within [seamBand] of it.
 *
 * [prepare] adds two things that need a global view of the overlaps:
 *  1. Exposure gains: one gain per frame and colour channel, solved by least squares so overlapping
 *     frames have the same mean colour (regularised toward 1).
 *  2. Seam finding: every pixel chooses ONE owner frame, minimising
 *         Σ_pixels dataWeight·(bestMargin − margin(owner))
 *       + Σ_neighbour pairs with different owners (a,b)  [ |Iₐ−I_b|(p) + |Iₐ−I_b|(q) ]
 *     i.e. seams prefer places where the two frames agree (flat walls) and avoid places where they
 *     disagree (nearby objects displaced by parallax). The data term is almost flat (a seam may sit
 *     anywhere in an overlap) except within [EDGE] of a frame border. Solved with alpha-expansion
 *     graph cuts (Boykov-Veksler-Zabih; own Dinic max-flow) on a reduced-resolution grid; at render
 *     time the owner map is bilinearly interpolated, which gives a short crossfade at the seams.
 *  3. Multi-band blending (3 bands, after Burt & Adelson): each frame is split into
 *         detail = I − L₁,   mid = L₁ − L₂,   low = L₂
 *     (L₁, L₂ = low-pass ≈ 1° and ≈ 5°, computed in frame space). Each band is blended with its own
 *     weights: detail with the sharp owner map, mid with the owner map blurred by ≈1°, low with it
 *     blurred by ≈4°. Brightness/colour differences are spread over a wide band (no patches) while
 *     detail still comes from one frame (no ghosting).
 */
class StitchCore(private val frames: List<FrameImage>, private val yaw0: Double) {

    /** Width of the crossfade in plain mode, in tan units (0.035 ≈ 2°). */
    var seamBand = 0.035
    /** Weak preference for the frame whose centre is closest (cost per tan unit of margin lost). */
    var dataWeight = 60.0
    /** Number of alpha-expansion sweeps over all frames. */
    var sweeps = 3

    /**
     * A painted brush stroke from the manual editor: inside it, the seam finder must use [frame].
     * [lonLat] holds world (yaw, pitch) pairs in radians (yaw as SphereMath.yawOf), [radius] in radians.
     */
    class Paint(val frame: Int, val radius: Double, val lonLat: DoubleArray)

    /**
     * Room model from the aligner (0 = plain rotating camera). The camera sits in front of the
     * rotation centre; on the unit scene sphere the offset is ρ(d) = max(ρ_wall, κ·|d.z|), and a
     * direction d is seen by a frame at p = Rᵀd + (0, 0, ρ(d)).
     */
    var rhoWall = 0.0
    var kappa = 0.0

    /** Equalise exposure between frames in [prepare]. */
    var gainCompensation = true

    /** Manual overrides for [prepare] (empty = fully automatic seams). */
    var paint: List<Paint> = emptyList()

    class SeamStats(
        val seamEnergyBefore: Double, val seamEnergyAfter: Double, val passes: Int,
        /** Grid cells no frame covers, and the grid size. */
        val uncovered: Int = 0, val cells: Int = 0,
    )

    var seamStats: SeamStats? = null
        private set

    /** Per-frame RGB gains found by [prepare] (1,1,1 until then). */
    val gains: Array<DoubleArray> = Array(frames.size) { doubleArrayOf(1.0, 1.0, 1.0) }

    private var lw = 0
    private var lh = 0
    private var labels: IntArray? = null
    private val warpScratch = ThreadLocal.withInitial { DoubleArray(2) }

    /** Use multi-band blending after [prepare] (false = sharp seams, for comparison). */
    var multiBand = true
    private var slotF: IntArray? = null
    private var slotN: ByteArray? = null
    private var bandW1: FloatArray? = null
    private var bandW2: FloatArray? = null
    /** 1 where a candidate frame shows the same thing as the owner, → 0 where it doesn't (parallax). */
    private var slotAgree: FloatArray? = null
    private var low1: Array<LowPass>? = null
    private var low2: Array<LowPass>? = null

    // ---------------------------------------------------------------- candidates

    /** Frames that see direction (dx,dy,dz): fills the arrays and returns the count. */
    private fun gather(
        dx: Double, dy: Double, dz: Double,
        cf: IntArray, cm: DoubleArray, cx: DoubleArray, cy: DoubleArray,
    ): Int {
        var cnt = 0
        val rho = if (rhoWall == 0.0 && kappa == 0.0) 0.0 else min(0.6, max(rhoWall, kappa * abs(dz)))
        for (i in frames.indices) {
            val f = frames[i]
            if (dx * f.fwdX + dy * f.fwdY + dz * f.fwdZ < f.cosCull) continue
            val r = f.r
            val u = f.offset
            val px = r[0] * dx + r[3] * dy + r[6] * dz - (if (u != null) rho * u[0] else 0.0)
            val py = r[1] * dx + r[4] * dy + r[7] * dz - (if (u != null) rho * u[1] else 0.0)
            val pz = r[2] * dx + r[5] * dy + r[8] * dz + rho * (1 + (u?.get(2) ?: 0f))
            val depth = -pz
            if (depth <= 1e-6) continue
            // Looking down past the nadir a frame sees the photographer's own legs: only use the
            // floor on the side the camera faces; frames facing the other way fill the rest.
            if (dz < -0.85 && f.fwdH > 0.2 && dx * f.fwdX + dy * f.fwdY < 0) continue
            var nx = px / depth / f.tanH
            var ny = py / depth / f.tanV
            if (f.warp != null) {
                val ws = warpScratch.get()!!
                f.warpAt(nx, ny, ws)
                nx += ws[0]; ny += ws[1]
            }
            if (nx <= -1 || nx >= 1 || ny <= -1 || ny >= 1) continue
            if (cnt < MAX_CAND) {
                cf[cnt] = i
                cm[cnt] = min((1 - abs(nx)) * f.tanH, (1 - abs(ny)) * f.tanV)
                cx[cnt] = nx; cy[cnt] = ny
                cnt++
            }
        }
        return cnt
    }

    private fun sortByMarginDesc(cnt: Int, cf: IntArray, cm: DoubleArray, cx: DoubleArray, cy: DoubleArray) {
        for (i in 1 until cnt) {
            var j = i
            while (j > 0 && cm[j] > cm[j - 1]) {
                val ti = cf[j]; cf[j] = cf[j - 1]; cf[j - 1] = ti
                val tm = cm[j]; cm[j] = cm[j - 1]; cm[j - 1] = tm
                val tx = cx[j]; cx[j] = cx[j - 1]; cx[j - 1] = tx
                val ty = cy[j]; cy[j] = cy[j - 1]; cy[j - 1] = ty
                j--
            }
        }
    }

    // ---------------------------------------------------------------- prepare (gains + seams)

    fun prepare(outW: Int, outH: Int, maxLowWidth: Int = 1024) {
        val n = frames.size
        val factor = max(1, ceil(outW.toDouble() / maxLowWidth).toInt())
        lw = max(16, outW / factor)
        lh = max(8, outH / factor)
        val total = lw * lh
        val k = KEEP
        val cF = IntArray(total * k) { -1 }
        val cM = FloatArray(total * k)
        val cC = IntArray(total * k)
        val cN = ByteArray(total)

        IntStream.range(0, lh).parallel().forEach { y ->
            val fi = IntArray(MAX_CAND); val fm = DoubleArray(MAX_CAND)
            val fx = DoubleArray(MAX_CAND); val fy = DoubleArray(MAX_CAND)
            val lat = PI / 2 - (y + 0.5) / lh * PI
            val cl = cos(lat); val sl = sin(lat)
            for (x in 0 until lw) {
                val lon = (x + 0.5) / lw * 2 * PI - PI
                val cnt = gather(cl * sin(lon + yaw0), cl * cos(lon + yaw0), sl, fi, fm, fx, fy)
                sortByMarginDesc(cnt, fi, fm, fx, fy)
                val keep = min(cnt, k)
                val p = y * lw + x
                cN[p] = keep.toByte()
                for (c in 0 until keep) {
                    val f = frames[fi[c]]
                    cF[p * k + c] = fi[c]
                    cM[p * k + c] = fm[c].toFloat()
                    cC[p * k + c] = sample(f, (fx[c] + 1) / 2 * f.width - 0.5, (1 - fy[c]) / 2 * f.height - 0.5)
                }
            }
        }

        if (gainCompensation) computeGains(cF, cC, cN, k, total)
        for (p in 0 until total) for (c in 0 until cN[p].toInt()) {
            val g = gains[cF[p * k + c]]
            val col = cC[p * k + c]
            cC[p * k + c] = (0xFF shl 24) or
                (clamp255(((col shr 16) and 0xFF) * g[0]) shl 16) or
                (clamp255(((col shr 8) and 0xFF) * g[1]) shl 8) or
                clamp255((col and 0xFF) * g[2])
        }

        val field = SeamField(lw, lh, k, cF, cM, cC, cN, dataWeight, buildForced(cF, cN, k))
        val before = field.seamEnergy()
        val passes = field.optimize(sweeps)
        var holes = 0
        for (p in 0 until total) if (cN[p].toInt() == 0) holes++
        seamStats = SeamStats(before, field.seamEnergy(), passes, holes, total)
        labels = field.lab
        if (multiBand) {
            buildBands(field, cF, cN)
            // Multi-band should only spread out *exposure* differences. Where two frames show
            // different content (misalignment/parallax) mixing their low bands leaves a smudge,
            // so fade those frames out of the wide bands.
            val agree = FloatArray(total * k)
            for (p in 0 until total) {
                val owner = field.lab[p]
                var oc = 0
                for (c in 0 until cN[p].toInt()) if (cF[p * k + c] == owner) oc = cC[p * k + c]
                for (c in 0 until cN[p].toInt()) {
                    val d = if (oc == 0) 0 else colourDiff(oc, cC[p * k + c])
                    agree[p * k + c] = (1f - (d - AGREE_OK) / AGREE_RANGE).coerceIn(0f, 1f)
                }
            }
            slotAgree = agree
        }
    }

    /** Grid of frames forced by [paint] (−1 = free). Later strokes win. Only where that frame is a candidate. */
    private fun buildForced(cF: IntArray, cN: ByteArray, k: Int): IntArray? {
        if (paint.isEmpty()) return null
        val forced = IntArray(lw * lh) { -1 }
        for (st in paint) {
            val cr = cos(st.radius)
            var i = 0
            while (i + 1 < st.lonLat.size) {
                val lon = st.lonLat[i]; val lat = st.lonLat[i + 1]; i += 2
                val cx = cos(lat) * sin(lon); val cy = cos(lat) * cos(lon); val cz = sin(lat)
                val y0 = ((PI / 2 - (lat + st.radius)) / PI * lh - 0.5).toInt().coerceIn(0, lh - 1)
                val y1 = ((PI / 2 - (lat - st.radius)) / PI * lh + 0.5).toInt().coerceIn(0, lh - 1)
                for (y in y0..y1) {
                    val la = PI / 2 - (y + 0.5) / lh * PI
                    val cl = cos(la); val sl = sin(la)
                    val half = if (cl < 1e-3) PI else min(PI, st.radius / cl * 1.2)
                    val xs = if (half >= PI) 0 until lw else {
                        val xc = ((lon - yaw0 + PI) / (2 * PI) * lw - 0.5)
                        ((xc - half / (2 * PI) * lw).toInt() - 1)..((xc + half / (2 * PI) * lw).toInt() + 1)
                    }
                    for (xr in xs) {
                        val x = ((xr % lw) + lw) % lw
                        val gl = (x + 0.5) / lw * 2 * PI - PI + yaw0
                        val dot = cl * sin(gl) * cx + cl * cos(gl) * cy + sl * cz
                        if (dot < cr) continue
                        val p = y * lw + x
                        for (c in 0 until cN[p].toInt()) if (cF[p * k + c] == st.frame) { forced[p] = st.frame; break }
                    }
                }
            }
        }
        return forced
    }

    private fun clamp255(v: Double) = (v + 0.5).toInt().coerceIn(0, 255)

    private fun colourDiff(a: Int, b: Int): Int =
        (abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) +
            abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) +
            abs((a and 0xFF) - (b and 0xFF))) / 3

    private fun ramp(t: Double): Double { val c = t.coerceIn(0.0, 1.0); return c * c * (3 - 2 * c) }

    /**
     * Least-squares gains (per channel). With Īᵢⱼ = mean colour of frame i over its overlap with j:
     *   minimise Σᵢ<ⱼ Nᵢⱼ [ (gᵢĪᵢⱼ − gⱼĪⱼᵢ)²/σn² + (1−gᵢ)²/σg² + (1−gⱼ)²/σg² ]
     * which is linear:  A g = b.
     */
    private fun computeGains(cF: IntArray, cC: IntArray, cN: ByteArray, k: Int, total: Int) {
        val n = frames.size
        val cnt = DoubleArray(n * n)
        val sum = Array(3) { DoubleArray(n * n) }
        for (p in 0 until total) {
            val c = cN[p].toInt()
            if (c < 2) continue
            for (a in 0 until c) for (b in a + 1 until c) {
                val ia = cF[p * k + a]; val ib = cF[p * k + b]
                val ca = cC[p * k + a]; val cb = cC[p * k + b]
                cnt[ia * n + ib] += 1.0; cnt[ib * n + ia] += 1.0
                for (ch in 0..2) {
                    val s = 16 - 8 * ch
                    sum[ch][ia * n + ib] += ((ca shr s) and 0xFF).toDouble()
                    sum[ch][ib * n + ia] += ((cb shr s) and 0xFF).toDouble()
                }
            }
        }
        val sn2 = SIGMA_N * SIGMA_N; val sg2 = SIGMA_G * SIGMA_G
        for (ch in 0..2) {
            val a = DoubleArray(n * n); val b = DoubleArray(n)
            for (i in 0 until n) { a[i * n + i] = 1e-6; b[i] = 1e-6 }
            for (i in 0 until n) for (j in i + 1 until n) {
                val nn = cnt[i * n + j]
                if (nn < 20) continue
                val iij = sum[ch][i * n + j] / nn
                val iji = sum[ch][j * n + i] / nn
                a[i * n + i] += nn * (iij * iij / sn2 + 1 / sg2)
                a[j * n + j] += nn * (iji * iji / sn2 + 1 / sg2)
                a[i * n + j] -= nn * iij * iji / sn2
                a[j * n + i] -= nn * iij * iji / sn2
                b[i] += nn / sg2; b[j] += nn / sg2
            }
            val g = solveDense(a, b, n)
            for (i in 0 until n) gains[i][ch] = (g?.get(i) ?: 1.0).coerceIn(0.8, 1.25)
        }
    }

    private fun solveDense(a: DoubleArray, b: DoubleArray, n: Int): DoubleArray? {
        val m = a.copyOf(); val x = b.copyOf()
        for (c in 0 until n) {
            var piv = c
            for (r in c + 1 until n) if (abs(m[r * n + c]) > abs(m[piv * n + c])) piv = r
            if (abs(m[piv * n + c]) < 1e-12) return null
            if (piv != c) {
                for (j in 0 until n) { val t = m[c * n + j]; m[c * n + j] = m[piv * n + j]; m[piv * n + j] = t }
                val t = x[c]; x[c] = x[piv]; x[piv] = t
            }
            for (r in c + 1 until n) {
                val f = m[r * n + c] / m[c * n + c]
                if (f == 0.0) continue
                for (j in c until n) m[r * n + j] -= f * m[c * n + j]
                x[r] -= f * x[c]
            }
        }
        for (r in n - 1 downTo 0) {
            var t = x[r]
            for (j in r + 1 until n) t -= m[r * n + j] * x[j]
            x[r] = t / m[r * n + r]
        }
        return x
    }

    // ---------------------------------------------------------------- multi-band setup

    /** Block-averaged copy of a frame (a cheap low-pass); sampled bilinearly in frame pixel coords. */
    private class LowPass(f: FrameImage, val factor: Int) {
        val w = max(1, (f.width + factor - 1) / factor)
        val h = max(1, (f.height + factor - 1) / factor)
        val r = FloatArray(w * h); val g = FloatArray(w * h); val b = FloatArray(w * h)

        init {
            val cnt = IntArray(w * h)
            for (y in 0 until f.height) {
                val row = (y / factor) * w
                for (x in 0 until f.width) {
                    val i = row + x / factor
                    val c = f.pixels[y * f.width + x]
                    r[i] += ((c shr 16) and 0xFF).toFloat(); g[i] += ((c shr 8) and 0xFF).toFloat(); b[i] += (c and 0xFF).toFloat()
                    cnt[i]++
                }
            }
            for (i in cnt.indices) if (cnt[i] > 0) { r[i] /= cnt[i]; g[i] /= cnt[i]; b[i] /= cnt[i] }
            // Two [1 2 1]/4 passes per axis turn the blocky average into a near-Gaussian blur,
            // so overlapping frames (with differently placed block grids) produce matching low-passes.
            for (a in arrayOf(r, g, b)) repeat(2) { smooth(a) }
        }

        private fun smooth(a: FloatArray) {
            val t = FloatArray(a.size)
            for (y in 0 until h) for (x in 0 until w) {
                val l = a[y * w + max(0, x - 1)]; val c = a[y * w + x]; val rr = a[y * w + min(w - 1, x + 1)]
                t[y * w + x] = (l + 2 * c + rr) / 4
            }
            for (y in 0 until h) for (x in 0 until w) {
                val u = t[max(0, y - 1) * w + x]; val c = t[y * w + x]; val d = t[min(h - 1, y + 1) * w + x]
                a[y * w + x] = (u + 2 * c + d) / 4
            }
        }

        fun sample(x: Double, y: Double, out: DoubleArray) {
            val gx = ((x + 0.5) / factor - 0.5).coerceIn(0.0, w - 1.0)
            val gy = ((y + 0.5) / factor - 0.5).coerceIn(0.0, h - 1.0)
            val x0 = min(gx.toInt(), w - 1); val y0 = min(gy.toInt(), h - 1)
            val x1 = min(x0 + 1, w - 1); val y1 = min(y0 + 1, h - 1)
            val fx = gx - x0; val fy = gy - y0
            val i00 = y0 * w + x0; val i10 = y0 * w + x1; val i01 = y1 * w + x0; val i11 = y1 * w + x1
            out[0] = bil(r, i00, i10, i01, i11, fx, fy)
            out[1] = bil(g, i00, i10, i01, i11, fx, fy)
            out[2] = bil(b, i00, i10, i01, i11, fx, fy)
        }

        private fun bil(a: FloatArray, i00: Int, i10: Int, i01: Int, i11: Int, fx: Double, fy: Double): Double {
            val top = a[i00] + (a[i10] - a[i00]) * fx
            val bot = a[i01] + (a[i11] - a[i01]) * fx
            return top + (bot - top) * fy
        }
    }

    /** Box radius r such that 3 box passes ≈ a Gaussian of σ px:  3·((2r+1)²−1)/12 = σ². */
    private fun boxRadius(sigmaPx: Double) = max(1, ((sqrt(4 * sigmaPx * sigmaPx + 1) - 1) / 2 + 0.5).toInt())

    private fun buildBands(field: SeamField, cF: IntArray, cN: ByteArray) {
        val k = KEEP
        val total = lw * lh
        val lab = field.lab
        val lists = field.framePixels
        val pxPerDeg = lw / 360.0
        val w1 = FloatArray(total * k); val w2 = FloatArray(total * k)

        // Horizontal radius grows toward the poles so the blur is round on the sphere.
        fun radii(sigmaDeg: Double): Pair<IntArray, Int> {
            val s = sigmaDeg * pxPerDeg
            val rx = IntArray(lh) { y ->
                val lat = PI / 2 - (y + 0.5) / lh * PI
                min((lw - 1) / 2, boxRadius(s / max(cos(lat), 0.1)))
            }
            return rx to boxRadius(s)
        }
        val (rx1, ry1) = radii(MASK1_DEG)
        val (rx2, ry2) = radii(MASK2_DEG)

        IntStream.range(0, lists.size).parallel().forEach { f ->
            val pl = lists[f]
            if (pl.isEmpty() || pl.none { lab[it] == f }) return@forEach
            var yMin = lh; var yMax = -1
            for (p in pl) { val y = p / lw; if (y < yMin) yMin = y; if (y > yMax) yMax = y }
            val a = FloatArray(total); val tmp = FloatArray(total)
            for (band in 0..1) {
                val rx = if (band == 0) rx1 else rx2
                val ry = if (band == 0) ry1 else ry2
                val y0 = max(0, yMin - 3 * ry); val y1 = min(lh - 1, yMax + 3 * ry)
                java.util.Arrays.fill(a, 0f)
                for (p in pl) if (lab[p] == f) a[p] = 1f
                boxH(a, tmp, y0, y1, rx); boxH(tmp, a, y0, y1, rx); boxH(a, tmp, y0, y1, rx)
                boxV(tmp, a, y0, y1, ry); boxV(a, tmp, y0, y1, ry); boxV(tmp, a, y0, y1, ry)
                val target = if (band == 0) w1 else w2
                for (p in pl) {
                    val base = p * k
                    for (sl in 0 until cN[p].toInt()) if (cF[base + sl] == f) { target[base + sl] = a[p]; break }
                }
            }
        }
        bandW1 = w1; bandW2 = w2
        slotF = cF; slotN = cN

        val l1 = arrayOfNulls<LowPass>(frames.size); val l2 = arrayOfNulls<LowPass>(frames.size)
        IntStream.range(0, frames.size).parallel().forEach { i ->
            val f = frames[i]
            val framePxPerDeg = (f.width / 2.0) / f.tanH * (PI / 180)
            l1[i] = LowPass(f, max(2, (framePxPerDeg * LOW1_DEG / 2 + 0.5).toInt()))
            l2[i] = LowPass(f, max(3, (framePxPerDeg * LOW2_DEG / 2 + 0.5).toInt()))
        }
        low1 = Array(frames.size) { l1[it]!! }
        low2 = Array(frames.size) { l2[it]!! }
    }

    /** Horizontal box blur with 360° wrap, rows y0..y1, per-row radius. */
    private fun boxH(src: FloatArray, dst: FloatArray, y0: Int, y1: Int, rx: IntArray) {
        for (y in y0..y1) {
            val r = rx[y]; val row = y * lw; val win = (2 * r + 1).toFloat()
            var s = 0f
            for (i in -r..r) s += src[row + ((i % lw) + lw) % lw]
            for (x in 0 until lw) {
                dst[row + x] = s / win
                s += src[row + (x + r + 1) % lw] - src[row + ((x - r) % lw + lw) % lw]
            }
        }
    }

    /** Vertical box blur over rows y0..y1 (zero outside them), normalised at the poles. */
    private fun boxV(src: FloatArray, dst: FloatArray, y0: Int, y1: Int, r: Int) {
        fun at(y: Int, x: Int) = if (y < y0 || y > y1) 0f else src[y * lw + x]
        for (x in 0 until lw) {
            var s = 0f
            for (y in y0 - r..y0 + r) s += at(y, x)
            for (y in y0..y1) {
                val cnt = min(y + r, lh - 1) - max(y - r, 0) + 1
                dst[y * lw + x] = s / cnt
                s += at(y + r + 1, x) - at(y - r, x)
            }
        }
    }

    // ---------------------------------------------------------------- render

    fun renderRows(outW: Int, outH: Int, rowStart: Int, rowEnd: Int, out: IntArray) {
        val sinH = DoubleArray(outW); val cosH = DoubleArray(outW)
        for (u in 0 until outW) {
            val lon = (u + 0.5) / outW * 2 * PI - PI
            sinH[u] = sin(lon + yaw0); cosH[u] = cos(lon + yaw0)
        }
        val lab = labels
        val ux0 = IntArray(outW); val ux1 = IntArray(outW); val ufx = DoubleArray(outW)
        if (lab != null) for (u in 0 until outW) {
            val lx = (u + 0.5) * lw / outW - 0.5
            val x0 = floor(lx).toInt()
            ufx[u] = lx - x0
            ux0[u] = ((x0 % lw) + lw) % lw
            ux1[u] = (ux0[u] + 1) % lw
        }
        val cf = IntArray(MAX_CAND); val cm = DoubleArray(MAX_CAND)
        val cx = DoubleArray(MAX_CAND); val cy = DoubleArray(MAX_CAND); val wc = DoubleArray(MAX_CAND)
        val wc1 = DoubleArray(MAX_CAND); val wc2 = DoubleArray(MAX_CAND)
        val t1 = DoubleArray(3); val t2 = DoubleArray(3)
        val bw1 = bandW1; val bw2 = bandW2; val sF = slotF; val sN = slotN; val lp1 = low1; val lp2 = low2
        val ag = slotAgree
        val useBands = multiBand && lab != null && bw1 != null && bw2 != null && sF != null && sN != null && lp1 != null && lp2 != null

        for (v in rowStart until rowEnd) {
            val lat = PI / 2 - (v + 0.5) / outH * PI
            val cl = cos(lat); val sl = sin(lat)
            var y0 = 0; var y1 = 0; var fyv = 0.0
            if (lab != null) {
                val ly = (v + 0.5) * lh / outH - 0.5
                val yy = floor(ly).toInt()
                fyv = ly - yy
                y0 = yy.coerceIn(0, lh - 1); y1 = (yy + 1).coerceIn(0, lh - 1)
            }
            for (u in 0 until outW) {
                val cnt = gather(cl * sinH[u], cl * cosH[u], sl, cf, cm, cx, cy)
                if (cnt == 0) { out[v * outW + u] = 0; continue }
                for (c in 0 until cnt) wc[c] = 0.0
                var sw = 0.0
                if (lab != null && cnt > 1) {
                    val fx = ufx[u]
                    for (corner in 0..3) {
                        val xi = if (corner and 1 == 0) ux0[u] else ux1[u]
                        val yi = if (corner and 2 == 0) y0 else y1
                        val w = (if (corner and 1 == 0) 1 - fx else fx) * (if (corner and 2 == 0) 1 - fyv else fyv)
                        val l = lab[yi * lw + xi]
                        for (c in 0 until cnt) if (cf[c] == l) { wc[c] += w; sw += w; break }
                    }
                }
                if (sw <= 1e-9) {
                    var best = -1.0
                    for (c in 0 until cnt) if (cm[c] > best) best = cm[c]
                    for (c in 0 until cnt) {
                        val t = (cm[c] - best) / seamBand + 1
                        val w = if (t <= 0) 0.0 else t * t * (3 - 2 * t)
                        wc[c] = w; sw += w
                    }
                }
                if (useBands && cnt > 1) {
                    // Wide weights for the mid and low bands (owner map blurred by ≈1° and ≈4°).
                    for (c in 0 until cnt) { wc1[c] = 0.0; wc2[c] = 0.0 }
                    val fx = ufx[u]
                    for (corner in 0..3) {
                        val xi = if (corner and 1 == 0) ux0[u] else ux1[u]
                        val yi = if (corner and 2 == 0) y0 else y1
                        val w = (if (corner and 1 == 0) 1 - fx else fx) * (if (corner and 2 == 0) 1 - fyv else fyv)
                        val q = yi * lw + xi
                        val base = q * KEEP
                        for (sl in 0 until sN!![q].toInt()) {
                            val fr = sF!![base + sl]
                            for (c in 0 until cnt) if (cf[c] == fr) {
                                val a = if (ag != null) ag[base + sl] else 1f
                                wc1[c] += w * bw1!![base + sl] * a; wc2[c] += w * bw2!![base + sl] * a; break
                            }
                        }
                    }
                    var s1 = 0.0; var s2 = 0.0
                    for (c in 0 until cnt) {
                        // Keep wide bands away from frame borders, where the low-pass is biased.
                        wc1[c] *= ramp(cm[c] / EDGE_RAMP1); wc2[c] *= ramp(cm[c] / EDGE_RAMP2)
                        s1 += wc1[c]; s2 += wc2[c]
                    }
                    if (s1 <= 1e-9) { for (c in 0 until cnt) wc1[c] = wc[c]; s1 = sw }
                    if (s2 <= 1e-9) { for (c in 0 until cnt) wc2[c] = wc1[c]; s2 = s1 }
                    var ar = 0.0; var ag = 0.0; var ab = 0.0
                    for (c in 0 until cnt) {
                        val a0 = wc[c] / sw; val a1 = wc1[c] / s1; val a2 = wc2[c] / s2
                        if (a0 <= 0.0 && a1 <= 0.0 && a2 <= 0.0) continue
                        val f = frames[cf[c]]; val g = gains[cf[c]]
                        val x = (cx[c] + 1) / 2 * f.width - 0.5; val y = (1 - cy[c]) / 2 * f.height - 0.5
                        val col = sample(f, x, y)
                        lp1!![cf[c]].sample(x, y, t1); lp2!![cf[c]].sample(x, y, t2)
                        val fr = ((col shr 16) and 0xFF).toDouble(); val fg = ((col shr 8) and 0xFF).toDouble(); val fb = (col and 0xFF).toDouble()
                        ar += g[0] * (a0 * (fr - t1[0]) + a1 * (t1[0] - t2[0]) + a2 * t2[0])
                        ag += g[1] * (a0 * (fg - t1[1]) + a1 * (t1[1] - t2[1]) + a2 * t2[1])
                        ab += g[2] * (a0 * (fb - t1[2]) + a1 * (t1[2] - t2[2]) + a2 * t2[2])
                    }
                    out[v * outW + u] = (0xFF shl 24) or (clamp255(ar) shl 16) or (clamp255(ag) shl 8) or clamp255(ab)
                    continue
                }
                var sr = 0.0; var sg = 0.0; var sb = 0.0
                for (c in 0 until cnt) {
                    if (wc[c] <= 0.0) continue
                    val f = frames[cf[c]]
                    val g = gains[cf[c]]
                    val col = sample(f, (cx[c] + 1) / 2 * f.width - 0.5, (1 - cy[c]) / 2 * f.height - 0.5)
                    sr += wc[c] * ((col shr 16) and 0xFF) * g[0]
                    sg += wc[c] * ((col shr 8) and 0xFF) * g[1]
                    sb += wc[c] * (col and 0xFF) * g[2]
                }
                out[v * outW + u] = (0xFF shl 24) or
                    (clamp255(sr / sw) shl 16) or (clamp255(sg / sw) shl 8) or clamp255(sb / sw)
            }
        }
    }

    // ---------------------------------------------------------------- sampling

    private fun sample(f: FrameImage, xIn: Double, yIn: Double): Int {
        val x = xIn.coerceIn(0.0, f.width - 1.0); val y = yIn.coerceIn(0.0, f.height - 1.0)
        val x0 = x.toInt().coerceIn(0, f.width - 1); val y0 = y.toInt().coerceIn(0, f.height - 1)
        val x1 = (x0 + 1).coerceAtMost(f.width - 1); val y1 = (y0 + 1).coerceAtMost(f.height - 1)
        val fx = x - x0; val fy = y - y0
        val p = f.pixels; val w = f.width
        val c00 = p[y0 * w + x0]; val c10 = p[y0 * w + x1]
        val c01 = p[y1 * w + x0]; val c11 = p[y1 * w + x1]
        val r = lerp(c00, c10, c01, c11, 16, fx, fy)
        val g = lerp(c00, c10, c01, c11, 8, fx, fy)
        val b = lerp(c00, c10, c01, c11, 0, fx, fy)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun lerp(c00: Int, c10: Int, c01: Int, c11: Int, s: Int, fx: Double, fy: Double): Int {
        val a = (c00 shr s) and 0xFF; val b = (c10 shr s) and 0xFF
        val c = (c01 shr s) and 0xFF; val d = (c11 shr s) and 0xFF
        val top = a + (b - a) * fx
        val bot = c + (d - c) * fx
        return (top + (bot - top) * fy + 0.5).toInt()
    }

    // ---------------------------------------------------------------- seam field

    /**
     * Owner labels on a lat/lon grid. cF/cM/cC hold up to K candidate frames per cell, sorted by
     * margin (best first), with their margin and gain-corrected colour at that cell.
     */
    private class SeamField(
        val lw: Int, val lh: Int, val k: Int,
        val cF: IntArray, val cM: FloatArray, val cC: IntArray, val cN: ByteArray,
        val dataW: Double,
        val forced: IntArray? = null,
    ) {
        val total = lw * lh
        val lab = IntArray(total) { p ->
            when {
                forced != null && forced[p] >= 0 -> forced[p]
                cN[p] > 0 -> cF[p * k]
                else -> -1
            }
        }

        private fun colourAt(p: Int, f: Int): Int {
            val base = p * k
            for (i in 0 until cN[p].toInt()) if (cF[base + i] == f) return cC[base + i]
            return 0 // alpha is always 0xFF for real colours
        }

        private fun marginAt(p: Int, f: Int): Double {
            val base = p * k
            for (i in 0 until cN[p].toInt()) if (cF[base + i] == f) return cM[base + i].toDouble()
            return -1.0
        }

        private fun data(p: Int, f: Int): Double {
            if (forced != null && forced[p] >= 0 && forced[p] != f) return FORCED_COST
            val m = marginAt(p, f)
            val short = max(0.0, EDGE - m)
            return dataW * (cM[p * k] - m) + EDGE_WEIGHT * short * short / EDGE
        }

        private fun colDiff(a: Int, b: Int): Int =
            (abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) +
                abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) +
                abs((a and 0xFF) - (b and 0xFF))) / 3

        /** Cost of an edge p–q when p shows frame a and q shows frame lq. */
        fun pairCost(p: Int, a: Int, q: Int, lq: Int): Double {
            if (a == lq) return 0.0
            val ap = colourAt(p, a); val lp = colourAt(p, lq)
            val aq = colourAt(q, a); val lqq = colourAt(q, lq)
            val hasP = ap != 0 && lp != 0
            val hasQ = aq != 0 && lqq != 0
            return when {
                hasP && hasQ -> (colDiff(ap, lp) + colDiff(aq, lqq)).toDouble()
                hasP -> 2.0 * colDiff(ap, lp)
                hasQ -> 2.0 * colDiff(aq, lqq)
                else -> NO_OVERLAP_COST
            }
        }

        /** d: 0 left, 1 right (both wrap around 360°), 2 up, 3 down; −1 if outside the grid. */
        private fun nbr(p: Int, d: Int): Int {
            val x = p % lw; val y = p / lw
            return when (d) {
                0 -> y * lw + (x - 1 + lw) % lw
                1 -> y * lw + (x + 1) % lw
                2 -> if (y > 0) p - lw else -1
                else -> if (y + 1 < lh) p + lw else -1
            }
        }

        /** Σ edge costs over right/down neighbours (no data term). */
        fun seamEnergy(): Double {
            var e = 0.0
            for (p in 0 until total) {
                if (cN[p].toInt() == 0) continue
                val a = lab[p]
                val x = p % lw; val y = p / lw
                val q1 = y * lw + (x + 1) % lw
                if (lab[q1] >= 0) e += pairCost(p, a, q1, lab[q1])
                if (y + 1 < lh && lab[p + lw] >= 0) e += pairCost(p, a, p + lw, lab[p + lw])
            }
            return e
        }

        // ---- alpha-expansion

        val framePixels: Array<IntArray> by lazy {
            val nf = (cF.maxOrNull() ?: -1) + 1
            val count = IntArray(nf)
            for (p in 0 until total) for (i in 0 until cN[p].toInt()) count[cF[p * k + i]]++
            val lists = Array(nf) { IntArray(count[it]) }
            val fill = IntArray(nf)
            for (p in 0 until total) for (i in 0 until cN[p].toInt()) {
                val f = cF[p * k + i]; lists[f][fill[f]++] = p
            }
            lists
        }

        private val nodeOf = IntArray(total) { -1 }

        /** Returns the number of full sweeps run. */
        fun optimize(maxSweeps: Int): Int {
            val maxNodes = framePixels.maxOfOrNull { it.size } ?: 0
            if (maxNodes == 0) return 0
            val flow = MaxFlow(maxNodes + 2, 4 * maxNodes + 8)
            var last = seamEnergy()
            var sweepsDone = 0
            for (sweep in 0 until maxSweeps) {
                for (alpha in framePixels.indices) expand(alpha, flow)
                sweepsDone++
                val e = seamEnergy()
                val gain = last - e
                last = e
                if (gain < 0.002 * (e + 1)) break
            }
            return sweepsDone
        }

        private fun expand(alpha: Int, g: MaxFlow) {
            val pix = framePixels[alpha]
            val m = pix.size
            if (m == 0) return
            for (i in 0 until m) nodeOf[pix[i]] = i
            val u0 = DoubleArray(m); val u1 = DoubleArray(m)
            val pe = IntArray(2 * m); val pq = IntArray(2 * m); val pc = IntArray(2 * m)
            var pn = 0

            for (i in 0 until m) {
                val p = pix[i]
                val lp = lab[p]
                u0[i] += data(p, lp)
                u1[i] += data(p, alpha)
                for (d in 0..3) {
                    val q = nbr(p, d)
                    if (q < 0) continue
                    val qi = nodeOf[q]
                    if (qi >= 0) {
                        if (d == 0 || d == 2) continue // handled from q's side
                        val lq = lab[q]
                        val a = pairCost(p, lp, q, lq)
                        val b = pairCost(p, lp, q, alpha)
                        val c = pairCost(p, alpha, q, lq)
                        u1[i] += c - a
                        u1[qi] += -c
                        val cap = b + c - a
                        if (cap > 1e-9) { pe[pn] = i; pq[pn] = qi; pc[pn] = (cap * CAP_SCALE + 0.5).toInt(); pn++ }
                    } else {
                        val lq = lab[q]
                        if (lq < 0) continue
                        u0[i] += pairCost(p, lp, q, lq)
                        u1[i] += pairCost(p, alpha, q, lq)
                    }
                }
            }

            g.reset(m + 2)
            val s = m; val t = m + 1
            for (i in 0 until m) {
                val base = min(u0[i], u1[i])
                val c0 = ((u0[i] - base) * CAP_SCALE + 0.5).toInt()   // paid when the node keeps its label
                val c1 = ((u1[i] - base) * CAP_SCALE + 0.5).toInt()   // paid when it switches to alpha
                if (c1 > 0) g.addEdge(s, i, c1, 0)
                if (c0 > 0) g.addEdge(i, t, c0, 0)
            }
            for (e in 0 until pn) g.addEdge(pe[e], pq[e], pc[e], 0)

            g.maxFlow(s, t)
            for (i in 0 until m) if (!g.onSourceSide(i)) lab[pix[i]] = alpha
            for (i in 0 until m) nodeOf[pix[i]] = -1
        }
    }

    /** Dinic max-flow with an iterative DFS (no recursion → no stack overflow on long paths). */
    private class MaxFlow(maxNodes: Int, maxEdges: Int) {
        private val head = IntArray(maxNodes)
        private val to = IntArray(2 * maxEdges)
        private val cap = IntArray(2 * maxEdges)
        private val nxt = IntArray(2 * maxEdges)
        private val level = IntArray(maxNodes)
        private val cur = IntArray(maxNodes)
        private val queue = IntArray(maxNodes)
        private val path = IntArray(maxNodes)
        private var n = 0
        private var ec = 0

        fun reset(nodes: Int) { n = nodes; ec = 0; java.util.Arrays.fill(head, 0, nodes, -1) }

        fun addEdge(u: Int, v: Int, c: Int, rc: Int) {
            to[ec] = v; cap[ec] = c; nxt[ec] = head[u]; head[u] = ec++
            to[ec] = u; cap[ec] = rc; nxt[ec] = head[v]; head[v] = ec++
        }

        private fun bfs(s: Int, t: Int): Boolean {
            java.util.Arrays.fill(level, 0, n, -1)
            var qh = 0; var qt = 0
            queue[qt++] = s; level[s] = 0
            while (qh < qt) {
                val u = queue[qh++]
                var e = head[u]
                while (e != -1) {
                    val v = to[e]
                    if (cap[e] > 0 && level[v] < 0) { level[v] = level[u] + 1; queue[qt++] = v }
                    e = nxt[e]
                }
            }
            return level[t] >= 0
        }

        fun maxFlow(s: Int, t: Int): Long {
            var total = 0L
            while (bfs(s, t)) {
                System.arraycopy(head, 0, cur, 0, n)
                var depth = 0
                var u = s
                while (true) {
                    if (u == t) {
                        var f = Int.MAX_VALUE
                        for (i in 0 until depth) f = min(f, cap[path[i]])
                        var firstSat = -1
                        for (i in 0 until depth) {
                            val e = path[i]
                            cap[e] -= f; cap[e xor 1] += f
                            if (firstSat < 0 && cap[e] == 0) firstSat = i
                        }
                        total += f
                        u = to[path[firstSat] xor 1]
                        depth = firstSat
                        continue
                    }
                    var advanced = false
                    while (cur[u] != -1) {
                        val e = cur[u]
                        if (cap[e] > 0 && level[to[e]] == level[u] + 1) {
                            path[depth++] = e; u = to[e]; advanced = true; break
                        }
                        cur[u] = nxt[e]
                    }
                    if (!advanced) {
                        if (u == s) break
                        level[u] = -1
                        depth--
                        val e = path[depth]
                        u = to[e xor 1]
                        cur[u] = nxt[cur[u]]
                    }
                }
            }
            bfs(s, t) // leaves level[] = reachability from the source in the residual graph
            return total
        }

        fun onSourceSide(v: Int) = level[v] >= 0
    }

    companion object {
        private const val MAX_CAND = 16
        private const val KEEP = 6
        private const val SIGMA_N = 4.0
        private const val SIGMA_G = 0.2
        /** Seams may not come closer than this (tan units ≈ 3.5°) to a frame edge. */
        private const val EDGE = 0.06
        private const val EDGE_WEIGHT = 2000.0
        private const val CAP_SCALE = 8.0
        /** Low-pass scales of the frame bands, and blur of the owner map for the mid/low bands. */
        private const val LOW1_DEG = 1.0
        private const val LOW2_DEG = 5.0
        private const val MASK1_DEG = 1.0
        private const val MASK2_DEG = 4.0
        /** Margins (tan units) over which a frame's mid/low band fades in from its border. */
        private const val EDGE_RAMP1 = 0.02
        private const val EDGE_RAMP2 = 0.06
        /** Colour difference (0–255) up to which frames count as showing the same thing, and fade range. */
        private const val AGREE_OK = 14f
        private const val AGREE_RANGE = 22f
        private const val NO_OVERLAP_COST = 120.0
        /** Data cost of not using the painted frame (large but finite so the max-flow stays well-scaled). */
        private const val FORCED_COST = 5000.0

        /**
         * Fills the uncovered caps at the zenith/nadir (e.g. the floor under the photographer) with a
         * smooth patch: from the coverage edge it blends quickly into the edge colours blurred around
         * the circle, then into one average colour at the pole. Then makes everything opaque.
         */
        fun fillPoles(out: IntArray, w: Int, h: Int) {
            fillCap(out, w, h, top = true)
            fillCap(out, w, h, top = false)
            for (i in out.indices) out[i] = out[i] or (0xFF shl 24)
        }

        private fun fillCap(out: IntArray, w: Int, h: Int, top: Boolean) {
            val step = if (top) 1 else -1
            val pole = if (top) 0 else h - 1
            // First covered row per column, scanning from the pole.
            val edge = IntArray(w) { -1 }
            var any = false
            for (u in 0 until w) {
                var v = pole; var n = 0
                while (n < h / 2 && out[v * w + u] ushr 24 == 0) { v += step; n++ }
                if (n < h / 2) { edge[u] = v; if (n > 0) any = true }
            }
            if (!any) return
            // Colour just inside the coverage edge (average of 6 rows), per column.
            val er = DoubleArray(w); val eg = DoubleArray(w); val eb = DoubleArray(w)
            var cr = 0.0; var cg = 0.0; var cb = 0.0; var cn = 0
            for (u in 0 until w) {
                if (edge[u] < 0) continue
                var r = 0.0; var g = 0.0; var b = 0.0; var k = 0
                for (i in 0 until 6) {
                    val v = edge[u] + i * step
                    if (v < 0 || v >= h) break
                    val c = out[v * w + u]
                    if (c ushr 24 == 0) continue
                    r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; k++
                }
                if (k == 0) continue
                er[u] = r / k; eg[u] = g / k; eb[u] = b / k
                cr += er[u]; cg += eg[u]; cb += eb[u]; cn++
            }
            if (cn == 0) return
            cr /= cn; cg /= cn; cb /= cn
            for (u in 0 until w) if (edge[u] < 0) { er[u] = cr; eg[u] = cg; eb[u] = cb }
            // Blur edge colours around the circle (two box passes, radius w/32).
            val rad = max(1, w / 32)
            val br = er.copyOf(); val bg = eg.copyOf(); val bb = eb.copyOf()
            repeat(2) { circularBox(br, rad); circularBox(bg, rad); circularBox(bb, rad) }
            for (u in 0 until w) {
                val e = if (edge[u] < 0) pole + step * (h / 2) else edge[u]
                val span = abs(e - pole).coerceAtLeast(1)
                var v = pole
                while (v != e) {
                    val t = abs(e - v).toDouble() / span          // 0 at the edge, 1 at the pole
                    val a = smooth((t * 3).coerceAtMost(1.0))      // edge → blurred ring
                    val c2 = smooth(t)                             // ring → pole colour
                    fun mix(edgeV: Double, ring: Double, centre: Double) =
                        ((edgeV + (ring - edgeV) * a) * (1 - c2) + centre * c2 + 0.5).toInt().coerceIn(0, 255)
                    out[v * w + u] = (0xFF shl 24) or (mix(er[u], br[u], cr) shl 16) or
                        (mix(eg[u], bg[u], cg) shl 8) or mix(eb[u], bb[u], cb)
                    v += step
                }
            }
        }

        private fun smooth(t: Double) = t * t * (3 - 2 * t)

        private fun circularBox(a: DoubleArray, r: Int) {
            val n = a.size
            val src = a.copyOf()
            var s = 0.0
            for (i in -r..r) s += src[((i % n) + n) % n]
            for (i in 0 until n) {
                a[i] = s / (2 * r + 1)
                s += src[(i + r + 1) % n] - src[((i - r) % n + n) % n]
            }
        }
    }
}
