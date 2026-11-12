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

    /**
     * Final tone: scale so the 98th-percentile brightness reaches ~240 (white walls look white, not
     * grey). Capped at ×1.3 and never darkens. Returns the factor applied (1.0 = unchanged).
     */
    private fun autoLevel(px: IntArray): Double {
        val hist = IntArray(256)
        var i = 0
        while (i < px.size) {
            val c = px[i]
            hist[(77 * ((c shr 16) and 0xFF) + 150 * ((c shr 8) and 0xFF) + 29 * (c and 0xFF)) shr 8]++
            i += 7
        }
        val total = hist.sum()
        var acc = 0; var p98 = 255
        for (v in 0..255) { acc += hist[v]; if (acc >= total * 0.98) { p98 = v; break } }
        val k = (240.0 / max(1, p98)).coerceIn(1.0, 1.3)
        if (k < 1.02) return 1.0
        scalePixels(px, k)
        return k
    }

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

    /**
     * [ai]: also estimate depth with the AI model (DepthModel) and stitch with it instead of the box
     * room model; the diagnostics then go to diag_ai.txt.
     */
    suspend fun stitch(session: CaptureSession, ai: DepthModel? = null, onProgress: (Float) -> Unit): Result = withContext(Dispatchers.Default) {
        val maxMem = Runtime.getRuntime().maxMemory()
        // The straight-down shot always contains the photographer's legs; skip it and fill the floor
        // under the feet with a smooth patch instead (see StitchCore.fillPoles).
        val used = session.frames.filter { SphereMath.cameraForward(it.rotation).z > -0.985 }
        val n = used.size
        // Memory plan: frames get ~35 % of the heap, output buffer + bitmap ~30 %.
        val frameBudget = maxMem * 0.35
        val longEdge = sqrt(frameBudget / (n * 0.75 * 4)).toInt().coerceIn(640, 1600)

        val diag = StringBuilder()
        var tStage = System.currentTimeMillis()
        fun lap(): String { val now = System.currentTimeMillis(); val d = now - tStage; tStage = now; return "%.1fs".format(d / 1000.0) }
        val times = ArrayList<String>()

        val shot = session.frames.size
        diag.append("360 PhotoCap v${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA}) · By NorthXbar")
        diag.append(if (ai != null) " · ต่อด้วย AI ความลึก\n" else "\n")
        diag.append("ภาพ: $shot").append(if (session.targetCount > 0) "/${session.targetCount}" else "")
        if (session.targetCount in 1..Int.MAX_VALUE && shot < session.targetCount) diag.append(" (ขาด ${session.targetCount - shot} → มีส่วนที่ไม่มีภาพ)")
        if (shot > n) diag.append(" · ไม่ใช้ภาพพื้นใต้เท้า ${shot - n} ภาพ (เติมพื้นให้แทน)")
        diag.append("\nล็อกแสง: ").append(session.exposureMode.ifEmpty { "ไม่ทราบ (ถ่ายด้วยเวอร์ชันเก่า)" })

        val images = ArrayList<FrameImage>(n)
        val exposures = ArrayList<Exposure>(n)
        for ((i, f) in used.withIndex()) {
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

        // Manual edits (from the editor) take precedence over automatic alignment.
        val manual = EditState.load(File(session.dir, EditState.MANUAL))

        // Refine gyro rotations + FOV from image content so overlaps line up.
        val aligner = Aligner(images)
        val align = if (manual != null) null else try {
            aligner.run { p -> onProgress(0.15f + (if (ai != null) 0.2f else 0.27f) * p) }
        } catch (e: Throwable) {
            Log.e("Stitch", "alignment failed, using gyro only", e); null
        }
        if (align != null) {
            Log.i("Stitch", "align: matches=${align.matches} inliers=${align.inliers} " +
                "rms=%.3f° fovScale=%.4f applied=${align.applied}".format(align.rmsDeg, align.fovScale))
            diag.append("\nจัดภาพ: ").append(if (align.applied) "ใช้ ✓" else "ไม่ใช้ (${align.reason}) → ใช้ gyroscope อย่างเดียว")
            diag.append(" · จุดจับคู่ ${align.matches} (ผ่าน ${align.inliers})")
            if (align.inliers > 0) {
                diag.append(" · rms %.2f° · แก้ gyro สูงสุด %.1f°".format(align.rmsDeg, align.maxDevDeg))
                diag.append("\n  FOV: ").append(
                    if (align.fovEstimated) "ปรับ ×%.3f (ค่าจากกล้องคลาด: rms %.2f° → %.2f°)".format(align.fovScale, align.rmsFixedDeg, align.rmsFreeDeg)
                    else "ใช้ค่าจากกล้อง (ถ้าปรับ rms %.2f° → %.2f° ไม่คุ้ม ส่วนใหญ่เป็น parallax)".format(align.rmsFixedDeg, align.rmsFreeDeg))
            }
            if (align.applied) {
                diag.append("\n  ระดับน้ำ: เอียงจาก gyro สูงสุด %.2f°".format(align.maxTiltDeg))
                diag.append("\n  โมเดลห้อง: ρ ผนัง %.3f · κ พื้น/เพดาน %.3f".format(align.rhoWall, align.kappa))
                if (align.rhoWall > 0.005 && align.kappa > 0.005)
                    diag.append(" (ถ้ากล้องห่างแกนหมุน 25 ซม.: ผนัง ≈ %.1f ม., พื้น/เพดาน ≈ %.1f ม.)".format(0.25 / align.rhoWall, 0.25 / align.kappa))
            }
            if (!align.offsetRmsDeg.isNaN()) {
                diag.append("\n  ตำแหน่งกล้องแยกต่อภาพ: rms %.2f° → %.2f° · ขยับมากสุด %.2f (≈ %.0f ซม. ถ้ากล้องห่างแกนหมุน 25 ซม.)"
                    .format(align.rmsDeg, align.offsetRmsDeg, align.maxOffset, align.maxOffset * 25))
            }
            if (!align.warpRmsBeforeDeg.isNaN()) {
                diag.append("\n  บิดภาพเฉพาะจุด: ความคลาดที่จุดคู่ %.2f° → %.2f° (${align.warps.count { it != null }} ภาพ · จุดหนาแน่นแถบรอยต่อ ${align.denseMatches})"
                    .format(align.warpRmsBeforeDeg, align.warpRmsAfterDeg))
            }
            val weak = align.perFrame.withIndex().filter { it.value < 8 }.map { used[it.index].targetIndex }
            if (align.perFrame.isNotEmpty() && weak.isNotEmpty()) {
                diag.append("\n  ภาพที่จับคู่ได้น้อย (<8 จุด): ${weak.size} ภาพ (เป้า #${weak.take(8).joinToString(", #")})")
            }
        } else if (manual == null) diag.append("\nจัดภาพ: ผิดพลาด → ใช้ gyroscope อย่างเดียว")
        times += "จัดภาพ ${lap()}"

        // Several alignment hypotheses → keep the one whose seams come out cleanest.
        var chosen: Aligner.Candidate? = align?.candidates?.firstOrNull()
        if (manual == null && align != null && align.applied && align.candidates.size > 1) {
            try {
                val pick = SeamSelect.pick(images, align.candidates, session.yaw0) { p -> onProgress((if (ai != null) 0.35f else 0.42f) + 0.08f * p) }
                chosen = align.candidates[pick.index]
                diag.append("\nเลือกจากคุณภาพรอยต่อ: ${chosen.name}")
                diag.append(align.candidates.indices.joinToString(" · ", " (", ")") { k ->
                    val sc = pick.scores[k]
                    (if (k == pick.index) "▶" else "") + align.candidates[k].name + " " + (if (sc == Double.MAX_VALUE) "ผิดพลาด" else "%.0f".format(sc))
                })
            } catch (e: Throwable) {
                Log.e("Stitch", "seam selection failed", e)
            }
            times += "เลือก ${lap()}"
        }
        // AI depth: per-frame relative depth → scale per frame (room model, then matches) → one ρ map.
        var rhoMap: RhoMap? = null
        if (ai != null && manual == null && align != null && align.applied && chosen != null) {
            try {
                val base = SeamSelect.frames(images, chosen)
                val depth = images.mapIndexed { k, f ->
                    runCatching { ai.infer(f.pixels, f.width, f.height) }.getOrNull()
                        .also { onProgress(0.43f + 0.05f * (k + 1) / n) }
                }
                val init = DepthFusion.fits(base, depth, chosen.rhoWall, chosen.kappa)
                val cal = aligner.calibrateDepth(chosen, depth, init)
                val fused = DepthFusion.build(base, depth, chosen.rhoWall, chosen.kappa, cal)
                val refit = aligner.refineWithDepth(chosen, fused.map)
                diag.append("\nAI ความลึก: ${depth.count { it != null }}/$n ภาพ · ปรับสเกลได้ ${fused.fits.count { it?.ok == true }} ภาพ · ครอบคลุม %.0f%%"
                    .format(fused.coverage * 100))
                diag.append("\n  จุดคู่คลาด (90%% ที่ดีสุด): โมเดลห้อง %.2f° → AI %.2f°".format(refit.rmsRoomDeg, refit.rmsDepthDeg))
                if (refit.rmsDepthDeg > refit.rmsRoomDeg * 1.05) diag.append(" ⚠ AI ไม่ช่วยในภาพนี้")
                chosen = refit.candidate
                rhoMap = fused.map
            } catch (e: Throwable) {
                Log.e("Stitch", "AI depth failed", e)
                diag.append("\nAI ความลึก: ผิดพลาด (${e.javaClass.simpleName}: ${e.message}) → ใช้โมเดลห้อง")
            }
            times += "AI ${lap()}"
        }
        val frames = when {
            manual != null -> {
                diag.append("\nแก้ไขแมนนวล: เลื่อน ${manual.moved.size} ภาพ · ระบาย ${manual.strokes.size} เส้น (ข้ามการจัดภาพอัตโนมัติ)")
                images.mapIndexed { k, f ->
                    val name = used[k].file.name
                    FrameImage(f.pixels, f.width, f.height, manual.rot[name] ?: f.r,
                        f.tanH * manual.fovScale, f.tanV * manual.fovScale,
                        warp = if (name in manual.moved) null else manual.warp[name], offset = manual.offset[name])
                }
            }
            align != null && align.applied && chosen != null -> SeamSelect.frames(images, chosen)
            else -> images
        }
        // Remember the automatic result so the editor starts from it.
        if (manual == null && ai == null) try { // the editor works with the room model, not AI depth
            val c = chosen.takeIf { align?.applied == true }
            EditState(fovScale = c?.fovScale ?: 1.0).apply {
                if (c != null) { rhoWall = c.rhoWall; kappa = c.kappa }
                for ((k, f) in frames.withIndex()) {
                    val name = used[k].file.name
                    rot[name] = f.r
                    f.warp?.let { warp[name] = it }
                    f.offset?.let { offset[name] = it }
                }
            }.save(File(session.dir, EditState.AUTO))
        } catch (e: Exception) { Log.w("Stitch", "save align.json", e) }

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
        core.rhoMap = rhoMap
        when {
            manual != null -> { core.rhoWall = manual.rhoWall; core.kappa = manual.kappa }
            align != null && align.applied && chosen != null -> { core.rhoWall = chosen.rhoWall; core.kappa = chosen.kappa }
        }
        if (manual != null) {
            val index = used.withIndex().associate { it.value.file.name to it.index }
            core.paint = manual.strokes.mapNotNull { st ->
                index[st.frame]?.let { StitchCore.Paint(it, st.radius, st.pts.toDoubleArray()) }
            }
        }
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
        val level = autoLevel(out)
        if (level > 1.0) diag.append("\nปรับโทน: สว่างขึ้น ×%.2f".format(level))
        val bmp = Bitmap.createBitmap(out, outW, outH, Bitmap.Config.ARGB_8888)
        diag.append("\nขนาด ${outW}×$outH · เวลา: ${times.joinToString(" · ")}")
        val report = diag.toString()
        Log.i("Stitch", report)
        try { File(session.dir, if (ai != null) PanoStore.DIAG_AI else PanoStore.DIAG).writeText(report) } catch (_: Exception) {}
        onProgress(1f)
        Result(bmp, outW, outH, report)
    }

    fun loadFrame(f: Frame, longEdge: Int, tanLong: Double, tanShort: Double): FrameImage {
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
