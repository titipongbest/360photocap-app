package com.titipong.photocap360

import java.util.concurrent.atomic.AtomicInteger
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
) {
    internal val fwdX = -r[2].toDouble()
    internal val fwdY = -r[5].toDouble()
    internal val fwdZ = -r[8].toDouble()
    /** cos of the half-diagonal FOV: directions farther than this from the axis can't be inside. */
    internal val cosCull = cos(atan(sqrt(tanH * tanH + tanV * tanV)))
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
 *     disagree (nearby objects displaced by parallax). Solved by iterated conditional modes on a
 *     reduced-resolution grid; at render time the owner map is bilinearly interpolated, which gives
 *     a short crossfade at the seams.
 */
class StitchCore(private val frames: List<FrameImage>, private val yaw0: Double) {

    /** Width of the crossfade in plain mode, in tan units (0.035 ≈ 2°). */
    var seamBand = 0.035
    /** Cost of choosing a frame whose margin is smaller than the best one, per tan unit. */
    var dataWeight = 1200.0
    var icmPasses = 40

    class SeamStats(val seamEnergyBefore: Double, val seamEnergyAfter: Double, val passes: Int)

    var seamStats: SeamStats? = null
        private set

    /** Per-frame RGB gains found by [prepare] (1,1,1 until then). */
    val gains: Array<DoubleArray> = Array(frames.size) { doubleArrayOf(1.0, 1.0, 1.0) }

    private var lw = 0
    private var lh = 0
    private var labels: IntArray? = null

    // ---------------------------------------------------------------- candidates

    /** Frames that see direction (dx,dy,dz): fills the arrays and returns the count. */
    private fun gather(
        dx: Double, dy: Double, dz: Double,
        cf: IntArray, cm: DoubleArray, cx: DoubleArray, cy: DoubleArray,
    ): Int {
        var cnt = 0
        for (i in frames.indices) {
            val f = frames[i]
            if (dx * f.fwdX + dy * f.fwdY + dz * f.fwdZ < f.cosCull) continue
            val r = f.r
            val px = r[0] * dx + r[3] * dy + r[6] * dz
            val py = r[1] * dx + r[4] * dy + r[7] * dz
            val pz = r[2] * dx + r[5] * dy + r[8] * dz
            val depth = -pz
            if (depth <= 1e-6) continue
            val nx = px / depth / f.tanH
            val ny = py / depth / f.tanV
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

        computeGains(cF, cC, cN, k, total)
        for (p in 0 until total) for (c in 0 until cN[p].toInt()) {
            val g = gains[cF[p * k + c]]
            val col = cC[p * k + c]
            cC[p * k + c] = (0xFF shl 24) or
                (clamp255(((col shr 16) and 0xFF) * g[0]) shl 16) or
                (clamp255(((col shr 8) and 0xFF) * g[1]) shl 8) or
                clamp255((col and 0xFF) * g[2])
        }

        val field = SeamField(lw, lh, k, cF, cM, cC, cN, dataWeight)
        val before = field.seamEnergy()
        val passes = field.icm(icmPasses)
        seamStats = SeamStats(before, field.seamEnergy(), passes)
        labels = field.lab
    }

    private fun clamp255(v: Double) = (v + 0.5).toInt().coerceIn(0, 255)

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
            for (i in 0 until n) gains[i][ch] = (g?.get(i) ?: 1.0).coerceIn(0.7, 1.4)
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
    ) {
        val total = lw * lh
        val lab = IntArray(total) { p -> if (cN[p] > 0) cF[p * k] else -1 }

        private fun colourAt(p: Int, f: Int): Int {
            val base = p * k
            for (i in 0 until cN[p].toInt()) if (cF[base + i] == f) return cC[base + i]
            return 0 // alpha is always 0xFF for real colours
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

        private fun nbr(p: Int, d: Int): Int {
            val x = p % lw; val y = p / lw
            return when (d) {
                0 -> y * lw + (x - 1 + lw) % lw
                1 -> y * lw + (x + 1) % lw
                2 -> if (y > 0) p - lw else -1
                else -> if (y + 1 < lh) p + lw else -1
            }
        }

        private fun pixelCost(p: Int, c: Int): Double {
            val a = cF[p * k + c]
            var cost = dataW * (cM[p * k] - cM[p * k + c])
            for (d in 0..3) {
                val q = nbr(p, d)
                if (q < 0) continue
                val lq = lab[q]
                if (lq < 0) continue
                cost += pairCost(p, a, q, lq)
            }
            return cost
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

        /** Iterated conditional modes with red/black updates so rows can run in parallel. */
        fun icm(maxPasses: Int): Int {
            var passes = 0
            for (iter in 0 until maxPasses) {
                val changed = AtomicInteger()
                for (parity in 0..1) {
                    IntStream.range(0, lh).parallel().forEach { y ->
                        var x = (y + parity) and 1
                        var local = 0
                        while (x < lw) {
                            val p = y * lw + x
                            val c = cN[p].toInt()
                            if (c > 1) {
                                val cur = lab[p]
                                var bestC = -1; var bestCost = Double.MAX_VALUE; var curCost = Double.MAX_VALUE
                                for (i in 0 until c) {
                                    val cost = pixelCost(p, i)
                                    if (cF[p * k + i] == cur) curCost = cost
                                    if (cost < bestCost) { bestCost = cost; bestC = i }
                                }
                                if (bestC >= 0 && bestCost < curCost - 1e-9) {
                                    lab[p] = cF[p * k + bestC]; local++
                                }
                            }
                            x += 2
                        }
                        if (local > 0) changed.addAndGet(local)
                    }
                }
                passes++
                if (changed.get() <= total / 50000) break
            }
            return passes
        }
    }

    companion object {
        private const val MAX_CAND = 16
        private const val KEEP = 6
        private const val SIGMA_N = 10.0
        private const val SIGMA_G = 0.1
        private const val NO_OVERLAP_COST = 120.0

        /**
         * Fills uncovered pixels at the very top/bottom (zenith/nadir gaps) by stretching
         * the nearest covered pixel in each column, then makes everything opaque.
         */
        fun fillPoles(out: IntArray, w: Int, h: Int) {
            for (u in 0 until w) {
                var top = -1
                for (v in 0 until h) if (out[v * w + u] ushr 24 != 0) { top = v; break }
                if (top < 0) continue
                for (v in 0 until top) out[v * w + u] = out[top * w + u]
                var bot = -1
                for (v in h - 1 downTo 0) if (out[v * w + u] ushr 24 != 0) { bot = v; break }
                for (v in bot + 1 until h) out[v * w + u] = out[bot * w + u]
            }
            for (i in out.indices) out[i] = out[i] or (0xFF shl 24)
        }
    }
}
