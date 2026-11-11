package com.titipong.photocap360

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.atan
import kotlin.math.cos
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
 * Seam blending: the frame with the largest margin owns the pixel; other frames only blend in
 * when their margin is within [seamBand] of the best:
 *   t = (marginᵢ − best)/band + 1,   weight = smoothstep(clamp(t, 0, 1))
 * This keeps every pixel from (mostly) one frame — no ghosting — with a narrow crossfade at seams.
 * Output = Σ weight·colour / Σ weight.  Pixels no frame sees get alpha 0.
 */
class StitchCore(private val frames: List<FrameImage>, private val yaw0: Double) {

    /** Width of the crossfade at seams, in tan units (0.035 ≈ 2°). */
    var seamBand = 0.035

    fun renderRows(outW: Int, outH: Int, rowStart: Int, rowEnd: Int, out: IntArray) {
        val sinH = DoubleArray(outW); val cosH = DoubleArray(outW)
        for (u in 0 until outW) {
            val lon = (u + 0.5) / outW * 2 * PI - PI
            sinH[u] = sin(lon + yaw0); cosH[u] = cos(lon + yaw0)
        }
        val n = frames.size
        val candF = IntArray(MAX_CAND); val candM = DoubleArray(MAX_CAND)
        val candX = DoubleArray(MAX_CAND); val candY = DoubleArray(MAX_CAND)
        for (v in rowStart until rowEnd) {
            val lat = PI / 2 - (v + 0.5) / outH * PI
            val cl = cos(lat); val sl = sin(lat)
            for (u in 0 until outW) {
                val dx = cl * sinH[u]; val dy = cl * cosH[u]; val dz = sl
                var cnt = 0; var best = -1.0
                for (i in 0 until n) {
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
                    val m = min((1 - abs(nx)) * f.tanH, (1 - abs(ny)) * f.tanV)
                    if (cnt < MAX_CAND) {
                        candF[cnt] = i; candM[cnt] = m; candX[cnt] = nx; candY[cnt] = ny; cnt++
                    }
                    if (m > best) best = m
                }
                var sr = 0.0; var sg = 0.0; var sb = 0.0; var sw = 0.0
                for (c in 0 until cnt) {
                    val t = (candM[c] - best) / seamBand + 1
                    if (t <= 0) continue
                    val w = t * t * (3 - 2 * t)
                    val f = frames[candF[c]]
                    val col = sample(f, (candX[c] + 1) / 2 * f.width - 0.5, (1 - candY[c]) / 2 * f.height - 0.5)
                    sr += w * ((col shr 16) and 0xFF)
                    sg += w * ((col shr 8) and 0xFF)
                    sb += w * (col and 0xFF)
                    sw += w
                }
                out[v * outW + u] = if (sw > 1e-12) {
                    val rr = (sr / sw + 0.5).toInt().coerceIn(0, 255)
                    val gg = (sg / sw + 0.5).toInt().coerceIn(0, 255)
                    val bb = (sb / sw + 0.5).toInt().coerceIn(0, 255)
                    (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
                } else 0
            }
        }
    }

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

    companion object {
        private const val MAX_CAND = 16

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
