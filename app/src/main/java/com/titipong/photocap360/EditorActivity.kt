package com.titipong.photocap360

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.titipong.photocap360.SphereMath.Vec3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.stream.IntStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Manual stitch editor.
 *  - Tap: select a frame covering that point (tap again to cycle through overlapping frames).
 *  - Move mode: drag moves the selected frame (yaw/pitch), ↺ ↻ roll it.
 *  - Paint mode: drag paints where the selected frame must be used; seams are recomputed around it.
 *  - Save writes manual.json and re-stitches at full size.
 */
class EditorActivity : ComponentActivity(), EditorView.Listener {

    private enum class Mode { MOVE, PAINT }

    private lateinit var dir: File
    private lateinit var view: EditorView
    private lateinit var hint: TextView
    private lateinit var status: TextView
    private lateinit var btnMove: Button
    private lateinit var btnPaint: Button
    private lateinit var btnMinus: Button
    private lateinit var btnPlus: Button

    private var yaw0 = 0.0
    private var names: List<String> = emptyList()
    private var base: List<FrameImage> = emptyList()
    private var state = EditState()
    private val undo = ArrayDeque<EditState>()
    private var selected = -1
    private var mode = Mode.MOVE
    private var brushDeg = 4.0
    private var activeStroke: Stroke? = null

    private var rendering = false
    private var pending: Boolean? = null   // null = nothing pending, true = fast, false = full

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dir = File(intent.getStringExtra(StitchActivity.EXTRA_DIR) ?: run { finish(); return })

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        hint = TextView(this).apply { setTextColor(Color.WHITE); textSize = 13f; setPadding(24, 16, 24, 4) }
        status = TextView(this).apply { setTextColor(0xFF4CFF7A.toInt()); textSize = 13f; setPadding(24, 0, 24, 12) }
        view = EditorView(this).also { it.listener = this }
        root.addView(hint, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(view, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        fun button(text: String, primary: Boolean = false, onClick: () -> Unit) = Button(this).apply {
            this.text = text; textSize = 13f; isAllCaps = false
            setTextColor(if (primary) Color.BLACK else Color.WHITE)
            setBackgroundResource(if (primary) R.drawable.btn_primary else R.drawable.btn_outline)
            compactButton()
            setOnClickListener { onClick() }
        }
        fun row(vararg bs: Button) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(16, 8, 16, 8)
            for ((i, b) in bs.withIndex()) addView(b, LinearLayout.LayoutParams(0, dp(44f), 1f).apply { if (i < bs.lastIndex) marginEnd = dp(6f) })
        }
        btnMove = button("เลื่อนภาพ") { setMode(Mode.MOVE) }
        btnPaint = button("ระบาย") { setMode(Mode.PAINT) }
        btnMinus = button("↺") { minus() }
        btnPlus = button("↻") { plus() }
        root.addView(row(btnMove, btnPaint, btnMinus, btnPlus), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(row(
            button("เลิกทำ") { undoLast() },
            button("อัตโนมัติ") { confirmAuto() },
            button("บันทึก", primary = true) { save() },
        ), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 16 })
        setContentView(root)
        padForSystemBars()
        status.text = "กำลังโหลด…"

        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { load() }.isSuccess }
            if (!ok) { Toast.makeText(this@EditorActivity, "โหลดภาพไม่ได้", Toast.LENGTH_LONG).show(); finish(); return@launch }
            setMode(Mode.MOVE)
        }
    }

    private fun load() {
        val session = CaptureSession.load(dir)
        yaw0 = session.yaw0
        val used = session.frames.filter { SphereMath.cameraForward(it.rotation).z > -0.985 }
        names = used.map { it.file.name }
        base = used.map { Stitcher.loadFrame(it, 480, session.tanLong, session.tanShort) }
        val st = EditState.load(File(dir, EditState.MANUAL)) ?: EditState.load(File(dir, EditState.AUTO)) ?: EditState()
        for ((k, n) in names.withIndex()) if (n !in st.rot) st.rot[n] = used[k].rotation.copyOf()
        state = st
    }

    // ---------------- modes & buttons ----------------

    private fun setMode(m: Mode) {
        mode = m
        btnMove.alpha = if (m == Mode.MOVE) 1f else 0.5f
        btnPaint.alpha = if (m == Mode.PAINT) 1f else 0.5f
        btnMinus.text = if (m == Mode.MOVE) "↺" else "แปรง −"
        btnPlus.text = if (m == Mode.MOVE) "↻" else "แปรง +"
        hint.text = if (m == Mode.MOVE)
            "แตะเพื่อเลือกภาพ (แตะซ้ำเพื่อสลับภาพที่ซ้อนกัน) · ลากเพื่อเลื่อนภาพ · สองนิ้วเพื่อซูม/เลื่อนมุมมอง"
        else
            "แตะเลือกภาพ แล้วลากระบายตรงที่ต้องการให้ใช้ภาพนั้น · สองนิ้วเพื่อซูม/เลื่อนมุมมอง"
        view.brushRadius = if (m == Mode.PAINT) brushDeg * PI / 180 else 0.0
        refreshOverlays()
        requestRender(fast = false)
    }

    private fun minus() {
        if (mode == Mode.MOVE) roll(-0.5) else { brushDeg = (brushDeg / 1.4).coerceAtLeast(1.0); view.brushRadius = brushDeg * PI / 180; updateStatus() }
    }

    private fun plus() {
        if (mode == Mode.MOVE) roll(0.5) else { brushDeg = (brushDeg * 1.4).coerceAtMost(20.0); view.brushRadius = brushDeg * PI / 180; updateStatus() }
    }

    private fun pushUndo() {
        undo.addLast(state.copy())
        while (undo.size > 30) undo.removeFirst()
    }

    private fun undoLast() {
        val prev = undo.removeLastOrNull() ?: return
        state = prev
        refreshOverlays()
        requestRender(fast = false)
    }

    private fun confirmAuto() {
        AlertDialog.Builder(this)
            .setMessage("ยกเลิกการแก้ไขทั้งหมด แล้วต่อภาพใหม่แบบอัตโนมัติ?")
            .setPositiveButton("ตกลง") { _, _ ->
                File(dir, EditState.MANUAL).delete()
                startActivity(Intent(this, StitchActivity::class.java).putExtra(StitchActivity.EXTRA_DIR, dir.absolutePath))
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun save() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { state.save(File(dir, EditState.MANUAL)) }
            startActivity(Intent(this@EditorActivity, StitchActivity::class.java).putExtra(StitchActivity.EXTRA_DIR, dir.absolutePath))
            finish()
        }
    }

    // ---------------- geometry helpers ----------------

    private fun dirOf(u: Double, v: Double): Vec3 {
        val lon = u * 2 * PI - PI + yaw0
        val lat = PI / 2 - v * PI
        return SphereMath.dirFromYawPitch(lon, lat)
    }

    private fun uvOf(d: Vec3, out: DoubleArray, i: Int) {
        var u = (SphereMath.yawOf(d) - yaw0 + PI) / (2 * PI)
        u -= kotlin.math.floor(u)
        out[i] = u
        out[i + 1] = (PI / 2 - SphereMath.pitchOf(d)) / PI
    }

    private fun frame(k: Int): FrameImage {
        val f = base[k]; val n = names[k]
        return FrameImage(f.pixels, f.width, f.height, state.rot[n]!!.copyOf(),
            f.tanH * state.fovScale, f.tanV * state.fovScale,
            warp = if (n in state.moved) null else state.warp[n])
    }

    /** Frames covering direction d, best (most central) first. */
    private fun covering(d: Vec3): List<Int> {
        val out = ArrayList<Pair<Int, Double>>()
        for (k in base.indices) {
            val f = frame(k)
            val q = SphereMath.project(f.r, d, f.tanH, f.tanV) ?: continue
            if (abs(q[0]) >= 1 || abs(q[1]) >= 1) continue
            out += k to min(1 - abs(q[0]), 1 - abs(q[1]))
        }
        return out.sortedByDescending { it.second }.map { it.first }
    }

    // ---------------- touch ----------------

    override fun onTap(u: Double, v: Double) {
        val c = covering(dirOf(u, v))
        if (c.isEmpty()) return
        val at = c.indexOf(selected)
        selected = if (at < 0) c[0] else c[(at + 1) % c.size]
        refreshOverlays()
    }

    override fun onDragStart(u: Double, v: Double) {
        if (selected < 0) {
            Toast.makeText(this, "แตะเพื่อเลือกภาพก่อน", Toast.LENGTH_SHORT).show(); return
        }
        pushUndo()
        if (mode == Mode.PAINT) {
            activeStroke = Stroke(names[selected], brushDeg * PI / 180).also { state.strokes += it }
            addPaintPoint(u, v)
        }
    }

    override fun onDrag(u: Double, v: Double, du: Double, dv: Double) {
        if (selected < 0) return
        if (mode == Mode.MOVE) {
            move(du * 2 * PI, -dv * PI)
            refreshOverlays()
            requestRender(fast = true)
        } else {
            addPaintPoint(u, v)
        }
    }

    override fun onDragEnd() {
        activeStroke = null
        if (selected >= 0) requestRender(fast = false)
    }

    private fun addPaintPoint(u: Double, v: Double) {
        val s = activeStroke ?: return
        val d = dirOf(u, v)
        val lon = SphereMath.yawOf(d); val lat = SphereMath.pitchOf(d)
        val n = s.pts.size
        if (n >= 2) {
            val prev = SphereMath.dirFromYawPitch(s.pts[n - 2], s.pts[n - 1])
            if (SphereMath.angleBetween(prev, d) < s.radius / 3) return
        }
        s.pts += lon; s.pts += lat
        refreshOverlays()
    }

    /** Yaw about the world vertical, pitch about the horizontal axis across the view direction. */
    private fun move(dYaw: Double, dPitch: Double) {
        val n = names[selected]
        val r = Rot.ofFloat(state.rot[n]!!)
        val fx = -r[2]; val fy = -r[5]
        var m = Rot.exp(0.0, 0.0, -dYaw)
        var ax = fy; var ay = -fx; var az = 0.0
        var len = hypot(ax, ay)
        if (len < 1e-3) { ax = r[0]; ay = r[3]; az = r[6]; len = hypot(hypot(ax, ay), az) }
        m = Rot.mul(Rot.exp(ax / len * dPitch, ay / len * dPitch, az / len * dPitch), m)
        state.rot[n] = Rot.toFloat(Rot.mul(m, r))
        state.moved += n
    }

    private fun roll(deg: Double) {
        if (selected < 0) { Toast.makeText(this, "แตะเพื่อเลือกภาพก่อน", Toast.LENGTH_SHORT).show(); return }
        pushUndo()
        val n = names[selected]
        val r = Rot.ofFloat(state.rot[n]!!)
        val a = deg * PI / 180
        val m = Rot.exp(-r[2] * a, -r[5] * a, -r[8] * a)
        state.rot[n] = Rot.toFloat(Rot.mul(m, r))
        state.moved += n
        refreshOverlays()
        requestRender(fast = false)
    }

    // ---------------- overlays & rendering ----------------

    private fun colorFor(k: Int) = Color.HSVToColor(80, floatArrayOf((k * 137.5f) % 360f, 0.85f, 1f))

    private fun refreshOverlays() {
        val idx = names.withIndex().associate { it.value to it.index }
        view.strokes = state.strokes.map { s ->
            val uv = DoubleArray(s.pts.size)
            var i = 0
            while (i + 1 < s.pts.size) { uvOf(SphereMath.dirFromYawPitch(s.pts[i], s.pts[i + 1]), uv, i); i += 2 }
            EditorView.ViewStroke(colorFor(idx[s.frame] ?: 0), s.radius, uv)
        }
        view.outline = if (selected >= 0) {
            val f = frame(selected)
            val per = 24
            val out = DoubleArray(4 * per * 2 + 2)
            var o = 0
            for (side in 0..3) for (t in 0 until per) {
                val s = t.toDouble() / per * 2 - 1
                val (nx, ny) = when (side) { 0 -> s to 1.0; 1 -> 1.0 to -s; 2 -> -s to -1.0; else -> -1.0 to s }
                uvOf(SphereMath.rotate(f.r, Vec3(nx * f.tanH, ny * f.tanV, -1.0)).normalized(), out, o); o += 2
            }
            out[o] = out[0]; out[o + 1] = out[1]
            out
        } else DoubleArray(0)
        updateStatus()
    }

    private fun updateStatus(extra: String = "") {
        val sel = if (selected >= 0) "ภาพที่เลือก #${selected + 1}/${names.size}" else "ยังไม่ได้เลือกภาพ"
        val brush = if (mode == Mode.PAINT) " · แปรง %.0f°".format(brushDeg) else ""
        status.text = sel + brush + " · เลื่อน ${state.moved.size} · ระบาย ${state.strokes.size}" + extra
    }

    /** Fast = low-res plain blend while dragging; full = higher res, with seams in paint mode. */
    private fun requestRender(fast: Boolean) {
        if (base.isEmpty()) return
        if (rendering) { pending = if (pending == false || !fast) false else true; return }
        rendering = true
        val frames = base.indices.map { frame(it) }
        val idx = names.withIndex().associate { it.value to it.index }
        val paint = state.strokes.mapNotNull { s -> idx[s.frame]?.let { StitchCore.Paint(it, s.radius, s.pts.toDoubleArray()) } }
        val seams = mode == Mode.PAINT && !fast
        if (seams) updateStatus(" · คำนวณรอยต่อ…")
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.Default) { render(frames, if (fast) 512 else 1024, seams, paint) }
            view.preview = bmp
            rendering = false
            updateStatus()
            pending?.let { p -> pending = null; requestRender(p) }
        }
    }

    private fun render(frames: List<FrameImage>, w: Int, seams: Boolean, paint: List<StitchCore.Paint>): Bitmap {
        val h = w / 2
        val core = StitchCore(frames, yaw0).apply {
            multiBand = false; this.paint = paint; rhoWall = state.rhoWall; kappa = state.kappa
        }
        if (seams) core.prepare(w, h, maxLowWidth = w / 2)
        val out = IntArray(w * h)
        IntStream.range(0, (h + 15) / 16).parallel().forEach { c ->
            core.renderRows(w, h, c * 16, min(h, (c + 1) * 16), out)
        }
        StitchCore.fillPoles(out, w, h)
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }
}
