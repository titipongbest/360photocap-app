package com.titipong.photocap360

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max

/**
 * Flat (equirectangular) panorama for editing. Pano coordinates: u ∈ [0,1) left→right (wraps
 * around 360°), v ∈ [0,1] top→bottom. One finger = tap/drag (reported to [listener]), two fingers =
 * zoom and pan.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class EditorView(ctx: Context) : View(ctx) {

    interface Listener {
        fun onTap(u: Double, v: Double)
        fun onDragStart(u: Double, v: Double)
        fun onDrag(u: Double, v: Double, du: Double, dv: Double)
        fun onDragEnd()
    }

    /** A painted stroke to draw: colour, brush radius (rad) and (u, v) pairs. */
    class ViewStroke(val color: Int, val radius: Double, val uv: DoubleArray)

    var listener: Listener? = null
    var preview: Bitmap? = null
        set(value) { field = value; invalidate() }
    /** Outline of the selected frame as (u, v) pairs. */
    var outline: DoubleArray = DoubleArray(0)
        set(value) { field = value; invalidate() }
    var strokes: List<ViewStroke> = emptyList()
        set(value) { field = value; invalidate() }
    /** Brush radius (rad) for the cursor; 0 hides it. */
    var brushRadius = 0.0

    private var zoom = 1f
    private var ox = 0f
    private var oy = 0f
    private val panoW get() = width * zoom
    private val panoH get() = panoW / 2

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(76, 255, 122); style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val rect = RectF()
    private val path = Path()

    private val scaleDet = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val old = zoom
            zoom = (zoom * d.scaleFactor).coerceIn(1f, 10f)
            val f = zoom / old
            ox = d.focusX - (d.focusX - ox) * f
            oy = d.focusY - (d.focusY - oy) * f
            clamp(); invalidate()
            return true
        }
    })

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var multi = false
    private var dragging = false
    private var downX = 0f; private var downY = 0f
    private var lastX = 0f; private var lastY = 0f
    private var lastFx = 0f; private var lastFy = 0f
    private var focusInit = false
    private var cursorU = -1.0; private var cursorV = -1.0

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { clamp() }

    private fun clamp() {
        if (width == 0) return
        ox %= panoW
        if (ox > 0) ox -= panoW
        oy = if (panoH <= height) (height - panoH) / 2 else oy.coerceIn(height - panoH, 0f)
    }

    private fun toU(x: Float): Double { var u = ((x - ox) / panoW).toDouble() % 1.0; if (u < 0) u += 1.0; return u }
    private fun toV(y: Float) = ((y - oy) / panoH).toDouble()

    private fun focus(e: MotionEvent, skip: Int = -1): Pair<Float, Float> {
        var sx = 0f; var sy = 0f; var n = 0
        for (i in 0 until e.pointerCount) if (i != skip) { sx += e.getX(i); sy += e.getY(i); n++ }
        return if (n == 0) 0f to 0f else sx / n to sy / n
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDet.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                multi = false; dragging = false
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (dragging) listener?.onDragEnd()
                dragging = false; multi = true; focusInit = false
            }
            MotionEvent.ACTION_POINTER_UP -> { focusInit = false }
            MotionEvent.ACTION_MOVE -> {
                if (multi) {
                    if (e.pointerCount >= 2) {
                        val (fx, fy) = focus(e)
                        if (focusInit) { ox += fx - lastFx; oy += fy - lastFy; clamp(); invalidate() }
                        lastFx = fx; lastFy = fy; focusInit = true
                    }
                } else {
                    if (!dragging && hypot(e.x - downX, e.y - downY) > slop) {
                        dragging = true
                        listener?.onDragStart(toU(downX), toV(downY))
                        lastX = downX; lastY = downY
                    }
                    if (dragging) {
                        val u = toU(e.x); val v = toV(e.y)
                        listener?.onDrag(u, v, ((e.x - lastX) / panoW).toDouble(), ((e.y - lastY) / panoH).toDouble())
                        lastX = e.x; lastY = e.y
                        cursorU = u; cursorV = v
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) listener?.onDragEnd()
                else if (!multi && e.actionMasked == MotionEvent.ACTION_UP) listener?.onTap(toU(e.x), toV(e.y))
                dragging = false; multi = false; cursorU = -1.0
                invalidate()
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val bmp = preview ?: return
        for (k in 0..2) {
            val x0 = ox + k * panoW
            if (x0 > width) break
            rect.set(x0, oy, x0 + panoW, oy + panoH)
            canvas.drawBitmap(bmp, null, rect, bmpPaint)
            drawOverlays(canvas, x0)
        }
    }

    private fun drawOverlays(canvas: Canvas, x0: Float) {
        // Painted strokes (ellipses: a fixed angular radius is wider on screen toward the poles).
        for (s in strokes) {
            strokePaint.color = s.color
            var i = 0
            while (i + 1 < s.uv.size) {
                val u = s.uv[i]; val v = s.uv[i + 1]; i += 2
                val lat = PI / 2 - v * PI
                val rx = (s.radius / (2 * PI) / max(cos(lat), 0.08) * panoW).toFloat()
                val ry = (s.radius / PI * panoH).toFloat()
                val cx = x0 + (u * panoW).toFloat(); val cy = oy + (v * panoH).toFloat()
                rect.set(cx - rx, cy - ry, cx + rx, cy + ry)
                canvas.drawOval(rect, strokePaint)
            }
        }
        // Outline of the selected frame; break the line where it wraps around 360°.
        if (outline.size >= 4) {
            path.reset()
            var pu = -10.0
            var i = 0
            while (i + 1 < outline.size) {
                val u = outline[i]; val v = outline[i + 1]; i += 2
                val x = x0 + (u * panoW).toFloat(); val y = oy + (v * panoH).toFloat()
                if (abs(u - pu) > 0.5) path.moveTo(x, y) else path.lineTo(x, y)
                pu = u
            }
            canvas.drawPath(path, outlinePaint)
        }
        if (brushRadius > 0 && cursorU >= 0) {
            val lat = PI / 2 - cursorV * PI
            val rx = (brushRadius / (2 * PI) / max(cos(lat), 0.08) * panoW).toFloat()
            val ry = (brushRadius / PI * panoH).toFloat()
            val cx = x0 + (cursorU * panoW).toFloat(); val cy = oy + (cursorV * panoH).toFloat()
            rect.set(cx - rx, cy - ry, cx + rx, cy + ry)
            canvas.drawOval(rect, cursorPaint)
        }
    }
}
