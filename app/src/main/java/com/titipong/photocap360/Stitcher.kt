package com.titipong.photocap360

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Loads the captured frames and renders an equirectangular (2:1) panorama. */
object Stitcher {

    /** [diag] is a human-readable report of what each stage did (shown on the result screen). */
    class Result(val bitmap: Bitmap, val width: Int, val height: Int, val diag: String)

    private class Exposure(val seconds: Double?, val iso: Int?) {
        val ev: Double? get() = if (seconds != null && iso != null) seconds * iso else null
    }

    private fun readExposure(f: File): Exposure = try {
        val ex = ExifInterface(f.absolutePath)
        Exposure(
            ex.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, -1.0).takeIf { it > 0 },
            ex.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, -1).takeIf { it > 0 },
        )
    } catch (e: Exception) { Exposure(null, null) }

    private fun shutter(s: Double) = if (s >= 0.5) "%.1f".format(s) else "1/${(1 / s).roundToInt()}"

    /** Multiplies every channel by [g] (JPEG values, clamped). */
    private fun scalePixels(px: IntArray, g: Double) {
        for (i in px.indices) {
            val c = px[i]
            val r = (((c shr 16) and 0xFF) * g + 0.5).toInt().coerceAtMost(255)
            val gg = (((c shr 8) and 0xFF) * g + 0.5).toInt().coerceAtMost(255)
            val b = ((c and 0xFF) * g + 0.5).toInt().coerceAtMost(255)
            px[i] = (c and 0xFF000000.toInt()) or (r shl 16) or (gg shl 8) or b
        }
    }

    suspend fun stitch(session: CaptureSession, onProgress: (Float) -> Unit): Result = withContext(Dispatchers.Default) {
        val maxMem = Runtime.getRuntime().maxMemory()
        val n = session.frames.size
        // Memory plan: frames get ~35 % of the heap, output buffer + bitmap ~30 %.
        val frameBudget = maxMem * 0.35
        val longEdge = sqrt(frameBudget / (n * 0.75 * 4)).toInt().coerceIn(640, 1600)

        val diag = StringBuilder()
        var tStage = System.currentTimeMillis()
        fun lap(): String { val now = System.currentTimeMillis(); val d = now - tStage; tStage = now; return "%.1fs".format(d / 1000.0) }
        val times = ArrayList<String>()

        diag.append("ภาพ: $n").append(if (session.targetCount > 0) "/${session.targetCount}" else "")
        if (session.targetCount in 1..Int.MAX_VALUE && n < session.targetCount) diag.append(" (ขาด ${session.targetCount - n} → มีส่วนที่ไม่มีภาพ)")
        diag.append("\nล็อกแสง: ").append(session.exposureMode.ifEmpty { "ไม่ทราบ (ถ่ายด้วยเวอร์ชันเก่า)" })

        val images = ArrayList<FrameImage>(n)
        val exposures = ArrayList<Exposure>(n)
        for ((i, f) in session.frames.withIndex()) {
            images += loadFrame(f, longEdge, session.tanLong, session.tanShort)
            exposures += readExposure(f.file)
            onProgress(0.15f * (i + 1) / n)
        }

        // Exposure check from EXIF. If the lock didn't hold, scale frames back to the median exposure.
        // JPEG ≈ linear^(1/2.2), so a linear factor k becomes k^(1/2.2) on pixel values.
        val evs = exposures.mapNotNull { it.ev }
        if (evs.size == n && n > 1) {
            val isos = exposures.mapNotNull { it.iso }; val secs = exposures.mapNotNull { it.seconds }
            val spread = evs.max() / evs.min()
            diag.append("\nค่าแสง EXIF: ISO ${isos.min()}–${isos.max()} · ชัตเตอร์ ${shutter(secs.min())}–${shutter(secs.max())} s")
            if (spread > 1.05) {
                val med = evs.sorted()[evs.size / 2]
                for ((i, img) in images.withIndex()) scalePixels(img.pixels, Math.pow(med / evs[i], 1 / 2.2))
                diag.append(" · ต่างกัน ×%.2f → ชดเชยแล้ว".format(spread))
            } else diag.append(" · คงที่ ✓")
        } else diag.append("\nค่าแสง EXIF: ไม่มีข้อมูล")
        times += "โหลด ${lap()}"

        // Refine gyro rotations + FOV from image content so overlaps line up.
        val align = try {
            Aligner(images).run { p -> onProgress(0.15f + 0.35f * p) }
        } catch (e: Throwable) {
            Log.e("Stitch", "alignment failed, using gyro only", e); null
        }
        if (align != null) {
            Log.i("Stitch", "align: matches=${align.matches} inliers=${align.inliers} " +
                "rms=%.3f° fovScale=%.4f applied=${align.applied}".format(align.rmsDeg, align.fovScale))
            diag.append("\nจัดภาพ: ").append(if (align.applied) "ใช้ ✓" else "ไม่ใช้ → ใช้ gyroscope อย่างเดียว")
            diag.append(" · จุดจับคู่ ${align.matches} (ผ่าน ${align.inliers})")
            if (align.inliers > 0) diag.append(" · rms %.2f° · FOV ×%.3f".format(align.rmsDeg, align.fovScale))
            val weak = align.perFrame.withIndex().filter { it.value < 8 }.map { session.frames[it.index].targetIndex }
            if (align.perFrame.isNotEmpty() && weak.isNotEmpty()) {
                diag.append("\n  ภาพที่จับคู่ได้น้อย (<8 จุด): ${weak.size} ภาพ (เป้า #${weak.take(8).joinToString(", #")})")
            }
        } else diag.append("\nจัดภาพ: ผิดพลาด → ใช้ gyroscope อย่างเดียว")
        times += "จัดภาพ ${lap()}"
        val frames = if (align != null && align.applied) images.mapIndexed { k, f ->
            FrameImage(f.pixels, f.width, f.height, align.rotations[k], f.tanH * align.fovScale, f.tanV * align.fovScale)
        } else images

        // Output width = horizon pixel density × 360°, capped by memory and a sane maximum.
        val ref = frames.first()
        val pxPerRad = (ref.width / 2.0) / ref.tanH
        val memCap = sqrt(maxMem * 0.30 / 4.0).toInt()  // 2 copies × W·(W/2)·4 bytes ≤ 30 % heap
        var outW = (2 * PI * pxPerRad).roundToInt()
        outW = min(outW, min(memCap, 8192))
        outW = max(1024, outW / 16 * 16)
        val outH = outW / 2

        val out = IntArray(outW * outH)
        val core = StitchCore(frames, session.yaw0)
        onProgress(0.5f)
        try {
            core.prepare(outW, outH)
            core.seamStats?.let {
                Log.i("Stitch", "seams: energy %.0f → %.0f in %d passes, gains r=%s".format(
                    it.seamEnergyBefore, it.seamEnergyAfter, it.passes,
                    core.gains.joinToString(",") { g -> "%.3f".format(g[0]) }))
                val all = core.gains.flatMap { g -> g.toList() }
                diag.append("\nความสว่าง (gain): %.2f–%.2f".format(all.min(), all.max()))
                if (all.min() <= 0.801 || all.max() >= 1.249) diag.append(" ⚠ ชนขีดจำกัด แสงต่างกันมาก")
                diag.append("\nรอยต่อ: ต้นทุน %.0f → %.0f (%.0f%%)".format(
                    it.seamEnergyBefore, it.seamEnergyAfter, 100 * it.seamEnergyAfter / max(1.0, it.seamEnergyBefore)))
            }
        } catch (e: Throwable) {
            Log.e("Stitch", "seam finding failed, using margin blending", e)
            diag.append("\nรอยต่อ: ผิดพลาด (${e.javaClass.simpleName}) → ใช้แบบธรรมดา")
        }
        times += "รอยต่อ ${lap()}"
        onProgress(0.6f)
        val chunk = 32
        val doneRows = AtomicInteger(0)
        coroutineScope {
            (0 until outH step chunk).map { start ->
                async {
                    val end = min(outH, start + chunk)
                    core.renderRows(outW, outH, start, end, out)
                    val d = doneRows.addAndGet(end - start)
                    onProgress(0.6f + 0.35f * d / outH)
                }
            }.awaitAll()
        }
        times += "เรนเดอร์ ${lap()}"
        val gaps = out.count { it ushr 24 == 0 }
        if (gaps > 0) diag.append("\nส่วนที่ไม่มีภาพ: %.1f%% (เติมด้วยสีขอบ)".format(100.0 * gaps / out.size))
        StitchCore.fillPoles(out, outW, outH)
        val bmp = Bitmap.createBitmap(out, outW, outH, Bitmap.Config.ARGB_8888)
        diag.append("\nขนาด ${outW}×$outH · เวลา: ${times.joinToString(" · ")}")
        val report = diag.toString()
        Log.i("Stitch", report)
        try { File(session.dir, "diag.txt").writeText(report) } catch (_: Exception) {}
        onProgress(1f)
        Result(bmp, outW, outH, report)
    }

    private fun loadFrame(f: Frame, longEdge: Int, tanLong: Double, tanShort: Double): FrameImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.file.absolutePath, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= longEdge) sample *= 2
        val raw = BitmapFactory.decodeFile(f.file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("cannot decode ${f.file}")

        val exifDeg = when (ExifInterface(f.file.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
        )) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
        // Upright portrait image; if it still comes out landscape, assume a 90° sensor.
        var deg = exifDeg
        val rotW = if (exifDeg % 180 == 0) raw.width else raw.height
        val rotH = if (exifDeg % 180 == 0) raw.height else raw.width
        if (rotW > rotH) deg = (deg + 90) % 360

        val scale = longEdge.toFloat() / max(raw.width, raw.height)
        val m = Matrix().apply {
            postRotate(deg.toFloat())
            if (scale < 1f) postScale(scale, scale)
        }
        val bmp = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        if (bmp !== raw) raw.recycle()

        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()

        val (tl, ts) = SphereMath.cropTan(tanLong, tanShort, max(w, h).toDouble() / min(w, h))
        return FrameImage(px, w, h, f.rotation, tanH = ts, tanV = tl)
    }
}
