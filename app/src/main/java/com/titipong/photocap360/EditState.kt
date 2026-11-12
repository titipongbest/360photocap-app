package com.titipong.photocap360

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A brush stroke: inside it the panorama must come from frame [frame] (file name). */
class Stroke(val frame: String, val radius: Double, val pts: MutableList<Double> = ArrayList()) {
    fun copy() = Stroke(frame, radius, ArrayList(pts))
}

/**
 * Orientation of every frame (by file name), FOV scale, mesh warps and painted strokes.
 *  - align.json  : what the automatic alignment produced (written by the stitcher)
 *  - manual.json : the user's edits (when present the stitcher uses it instead of auto alignment)
 */
class EditState(
    var fovScale: Double = 1.0,
    val rot: MutableMap<String, FloatArray> = LinkedHashMap(),
    val warp: MutableMap<String, FloatArray> = LinkedHashMap(),
    val strokes: MutableList<Stroke> = ArrayList(),
    /** Frames the user moved by hand (their auto warp no longer fits and is dropped). */
    val moved: MutableSet<String> = LinkedHashSet(),
    /** Room model (see StitchCore.rhoWall / kappa). */
    var rhoWall: Double = 0.0,
    var kappa: Double = 0.0,
    /** Per-frame camera positions (see FrameImage.offset). */
    val offset: MutableMap<String, FloatArray> = LinkedHashMap(),
) {
    fun copy() = EditState(
        fovScale,
        LinkedHashMap(rot.mapValues { it.value.copyOf() }),
        LinkedHashMap(warp.mapValues { it.value.copyOf() }),
        strokes.map { it.copy() }.toMutableList(),
        LinkedHashSet(moved),
        rhoWall, kappa,
        LinkedHashMap(offset.mapValues { it.value.copyOf() }),
    )

    fun save(f: File) {
        val frames = JSONObject()
        for ((name, r) in rot) {
            frames.put(name, JSONObject().apply {
                put("r", JSONArray().apply { r.forEach { put(it.toDouble()) } })
                warp[name]?.let { w -> put("warp", JSONArray().apply { w.forEach { put(it.toDouble()) } }) }
                if (name in moved) put("moved", true)
                offset[name]?.let { u -> put("off", JSONArray().apply { u.forEach { put(it.toDouble()) } }) }
            })
        }
        val st = JSONArray()
        for (s in strokes) st.put(JSONObject().apply {
            put("frame", s.frame); put("radius", s.radius)
            put("pts", JSONArray().apply { s.pts.forEach { put(it) } })
        })
        f.writeText(JSONObject().apply {
            put("fovScale", fovScale); put("frames", frames); put("strokes", st)
            put("rhoWall", rhoWall); put("kappa", kappa)
        }.toString())
    }

    companion object {
        const val AUTO = "align.json"
        const val MANUAL = "manual.json"

        fun load(f: File): EditState? = try {
            if (!f.exists()) null else {
                val j = JSONObject(f.readText())
                val e = EditState(j.optDouble("fovScale", 1.0))
                e.rhoWall = j.optDouble("rhoWall", 0.0); e.kappa = j.optDouble("kappa", 0.0)
                val frames = j.optJSONObject("frames") ?: JSONObject()
                for (name in frames.keys()) {
                    val o = frames.getJSONObject(name)
                    val r = o.getJSONArray("r")
                    e.rot[name] = FloatArray(9) { r.getDouble(it).toFloat() }
                    o.optJSONArray("warp")?.let { w -> e.warp[name] = FloatArray(w.length()) { w.getDouble(it).toFloat() } }
                    if (o.optBoolean("moved")) e.moved += name
                    o.optJSONArray("off")?.takeIf { it.length() == 3 }?.let { u -> e.offset[name] = FloatArray(3) { u.getDouble(it).toFloat() } }
                }
                val st = j.optJSONArray("strokes") ?: JSONArray()
                for (i in 0 until st.length()) {
                    val o = st.getJSONObject(i)
                    val pts = o.getJSONArray("pts")
                    e.strokes += Stroke(o.getString("frame"), o.getDouble("radius"),
                        MutableList(pts.length()) { pts.getDouble(it) })
                }
                e
            }
        } catch (ex: Exception) { null }
    }
}
