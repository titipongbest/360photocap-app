package com.titipong.photocap360

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.titipong.photocap360.SphereMath.Target
import com.titipong.photocap360.SphereMath.Vec3
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * Draws the capture targets on top of the camera preview.
 *
 * Screen projection of a world direction w (see SphereMath):
 *   p = Rᵀ·w,  depth = −p.z
 *   x = cx + (p.x/depth)/tanH · dispW/2
 *   y = cy − (p.y/depth)/tanV · dispH/2
 * dispW × dispH is the area the preview actually occupies (FIT_CENTER).
 */
class TargetOverlayView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null,
) : View(ctx, attrs) {

    var orient: FloatArray? = null
    var targets: List<Target> = emptyList()
    var targetDirs: List<Vec3> = emptyList()
    var done: Set<Int> = emptySet()
    var next: Int = -1
    var holdProgress = 0f
    var capturedFrames: List<FloatArray> = emptyList()
    var tanH = SphereMath.tanHalf(50.0)
    var tanV = SphereMath.tanHalf(65.0)
    /** Angular radius (deg) inside which a target counts as aimed. */
    var alignDeg = 3.0

    private val green = Color.rgb(76, 255, 122)
    private val pTarget = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = green; style = Paint.Style.FILL }
    private val pTargetRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = green; style = Paint.Style.STROKE; strokeWidth = 4f; alpha = 160
    }
    private val pReticle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 6f
    }
    private val pProgress = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 14f; strokeCap = Paint.Cap.ROUND
    }
    private val pFrame = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = green; alpha = 55; style = Paint.Style.FILL }
    private val pFrameEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = green; alpha = 140; style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val pArrow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val pDot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val arcRect = RectF()

    private var dispW = 0f; private var dispH = 0f; private var cx = 0f; private var cy = 0f

    private fun layoutDisplay() {
        val w = width.toFloat(); val h = height.toFloat()
        val aspect = (tanH / tanV).toFloat() // width / height of preview image
        if (w / h < aspect) { dispW = w; dispH = w / aspect } else { dispH = h; dispW = h * aspect }
        cx = w / 2; cy = h / 2
    }

    /** Returns screen (x, y, depth) for a world direction. */
    private fun toScreen(r: FloatArray, w: Vec3, out: FloatArray): Boolean {
        val p = SphereMath.rotateT(r, w)
        val depth = -p.z
        out[2] = depth.toFloat()
        if (depth <= 1e-4) return false
        out[0] = (cx + (p.x / depth) / tanH * dispW / 2).toFloat()
        out[1] = (cy - (p.y / depth) / tanV * dispH / 2).toFloat()
        return true
    }

    private fun pxPerRad(): Float = (dispW / 2 / tanH).toFloat()

    override fun onDraw(canvas: Canvas) {
        val r = orient ?: return
        layoutDisplay()
        val tmp = FloatArray(3)

        // 1) Captured frames as translucent quads (great-circle edges project to straight lines).
        val cornersDev = arrayOf(
            Vec3(-tanH, tanV, -1.0), Vec3(tanH, tanV, -1.0),
            Vec3(tanH, -tanV, -1.0), Vec3(-tanH, -tanV, -1.0),
        )
        for (fr in capturedFrames) {
            path.reset()
            var ok = true
            for ((i, c) in cornersDev.withIndex()) {
                if (!toScreen(r, SphereMath.rotate(fr, c), tmp)) { ok = false; break }
                if (i == 0) path.moveTo(tmp[0], tmp[1]) else path.lineTo(tmp[0], tmp[1])
            }
            if (!ok) continue
            path.close()
            canvas.drawPath(path, pFrame)
            canvas.drawPath(path, pFrameEdge)
        }

        // 2) Targets.
        val targetRadius = pxPerRad() * tan(alignDeg * 0.8 * SphereMath.DEG).toFloat()
        for ((i, d) in targetDirs.withIndex()) {
            if (i in done) continue
            if (!toScreen(r, d, tmp)) continue
            if (i == next) canvas.drawCircle(tmp[0], tmp[1], targetRadius, pTarget)
            else canvas.drawCircle(tmp[0], tmp[1], targetRadius * 0.7f, pTargetRing)
        }

        // 3) Reticle + hold progress.
        val reticleR = pxPerRad() * tan(alignDeg * 1.3 * SphereMath.DEG).toFloat()
        canvas.drawCircle(cx, cy, reticleR, pReticle)
        if (holdProgress > 0f) {
            arcRect.set(cx - reticleR - 14, cy - reticleR - 14, cx + reticleR + 14, cy + reticleR + 14)
            canvas.drawArc(arcRect, -90f, 360f * holdProgress, false, pProgress)
        }

        // 4) Arrow toward the next target when it's not near the center.
        if (next in targetDirs.indices) {
            val p = SphereMath.rotateT(r, targetDirs[next])
            val onScreen = toScreen(r, targetDirs[next], tmp) &&
                tmp[0] in 0f..width.toFloat() && tmp[1] in 0f..height.toFloat()
            val far = !onScreen || (tmp[0] - cx) * (tmp[0] - cx) + (tmp[1] - cy) * (tmp[1] - cy) > (reticleR * 3) * (reticleR * 3)
            if (far) {
                val ang = atan2(-p.y, p.x).toFloat() // screen direction (y down)
                drawArrow(canvas, cx + cos(ang) * reticleR * 2f, cy + sin(ang) * reticleR * 2f, ang, reticleR * 0.6f)
            }
        }

        drawDotMap(canvas)
    }

    private fun drawArrow(c: Canvas, x: Float, y: Float, ang: Float, size: Float) {
        path.reset()
        path.moveTo(x + cos(ang) * size, y + sin(ang) * size)
        path.lineTo(x + cos(ang + 2.5f) * size, y + sin(ang + 2.5f) * size)
        path.lineTo(x + cos(ang - 2.5f) * size, y + sin(ang - 2.5f) * size)
        path.close()
        c.drawPath(path, pArrow)
    }

    /** Small map of all targets (rows ordered by pitch) at the bottom, like a progress bar. */
    private fun drawDotMap(c: Canvas) {
        if (targets.isEmpty()) return
        val rows = targets.groupBy { it.row }.values.sortedByDescending { it.first().pitch }
        // Sit in the letterbox under the preview, but stay clear of the button bar.
        val buttonBar = 88f * resources.displayMetrics.density
        val previewBottom = cy + dispH / 2
        val free = (height - buttonBar - previewBottom).coerceAtLeast(0f)
        val areaH: Float
        val top: Float
        if (free > 120f) { areaH = free * 0.8f; top = previewBottom + free * 0.1f }
        else { areaH = height * 0.12f; top = height - buttonBar - areaH }
        val maxCount = rows.maxOf { it.size }
        val gap = min(width * 0.8f / maxCount, areaH / rows.size)
        val rad = gap * 0.3f
        for ((ri, row) in rows.withIndex()) {
            val y = top + gap * (ri + 0.5f)
            val sorted = row.sortedBy { SphereMath.wrapPi(it.yaw) }
            val startX = cx - gap * (sorted.size - 1) / 2f
            for ((k, t) in sorted.withIndex()) {
                val x = startX + gap * k
                when {
                    t.index == next -> { pDot.color = Color.WHITE; c.drawCircle(x, y, rad * 1.35f, pDot) }
                    t.index in done -> { pDot.color = Color.WHITE; pDot.alpha = 90; c.drawCircle(x, y, rad, pDot) }
                    else -> { pDot.color = green; c.drawCircle(x, y, rad, pDot) }
                }
                pDot.alpha = 255
            }
        }
    }
}
