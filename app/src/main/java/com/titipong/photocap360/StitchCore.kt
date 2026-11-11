package com.titipong.photocap360

import kotlin.math.PI
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
 *   px = (nx+1)/2 · (w−1),  py = (1−ny)/2 · (h−1)         → bilinear sample
 *   weight = ((1−nx²)(1−ny²))^k                           (feather: centre counts most)
 * Output = Σ weight·colour / Σ weight.  Pixels no frame sees get alpha 0.
 */
class StitchCore(private val frames: List<FrameImage>, private val yaw0: Double) {

    var featherPower = 3

    fun renderRows(outW: Int, outH: Int, rowStart: Int, rowEnd: Int, out: IntArray) {
        val sinH = DoubleArray(outW); val cosH = DoubleArray(outW)
        for (u in 0 until outW) {
            val lon = (u + 0.5) / outW * 2 * PI - PI
            sinH[u] = sin(lon + yaw0); cosH[u] = cos(lon + yaw0)
        }
        val n = frames.size
        for (v in rowStart until rowEnd) {
            val lat = PI / 2 - (v + 0.5) / outH * PI
            val cl = cos(lat); val sl = sin(lat)
            for (u in 0 until outW) {
                val dx = cl * sinH[u]; val dy = cl * cosH[u]; val dz = sl
                var sr = 0.0; var sg = 0.0; var sb = 0.0; var sw = 0.0
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
                    var w = (1 - nx * nx) * (1 - ny * ny)
                    var k = featherPower
                    val base = w
                    while (k-- > 1) w *= base
                    val c = sample(f, (nx + 1) / 2 * (f.width - 1), (1 - ny) / 2 * (f.height - 1))
                    sr += w * ((c shr 16) and 0xFF)
                    sg += w * ((c shr 8) and 0xFF)
                    sb += w * (c and 0xFF)
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

    private fun sample(f: FrameImage, x: Double, y: Double): Int {
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
