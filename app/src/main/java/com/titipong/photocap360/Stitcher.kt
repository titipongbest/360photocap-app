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

    class Result(val bitmap: Bitmap, val width: Int, val height: Int)

    suspend fun stitch(session: CaptureSession, onProgress: (Float) -> Unit): Result = withContext(Dispatchers.Default) {
        val maxMem = Runtime.getRuntime().maxMemory()
        val n = session.frames.size
        // Memory plan: frames get ~35 % of the heap, output buffer + bitmap ~30 %.
        val frameBudget = maxMem * 0.35
        val longEdge = sqrt(frameBudget / (n * 0.75 * 4)).toInt().coerceIn(640, 1600)

        val images = ArrayList<FrameImage>(n)
        for ((i, f) in session.frames.withIndex()) {
            images += loadFrame(f, longEdge, session.tanLong, session.tanShort)
            onProgress(0.15f * (i + 1) / n)
        }

        // Refine gyro rotations + FOV from image content so overlaps line up.
        val align = try {
            Aligner(images).run { p -> onProgress(0.15f + 0.35f * p) }
        } catch (e: Throwable) {
            Log.e("Stitch", "alignment failed, using gyro only", e); null
        }
        if (align != null) {
            Log.i("Stitch", "align: matches=${align.matches} inliers=${align.inliers} " +
                "rms=%.3f° fovScale=%.4f applied=${align.applied}".format(align.rmsDeg, align.fovScale))
        }
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
        val chunk = 32
        val doneRows = AtomicInteger(0)
        coroutineScope {
            (0 until outH step chunk).map { start ->
                async {
                    val end = min(outH, start + chunk)
                    core.renderRows(outW, outH, start, end, out)
                    val d = doneRows.addAndGet(end - start)
                    onProgress(0.5f + 0.45f * d / outH)
                }
            }.awaitAll()
        }
        StitchCore.fillPoles(out, outW, outH)
        val bmp = Bitmap.createBitmap(out, outW, outH, Bitmap.Config.ARGB_8888)
        onProgress(1f)
        Result(bmp, outW, outH)
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
