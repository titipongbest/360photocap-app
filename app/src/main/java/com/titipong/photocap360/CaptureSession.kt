package com.titipong.photocap360

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One captured frame: JPEG file + camera orientation at the moment of capture. */
data class Frame(val file: File, val rotation: FloatArray, val targetIndex: Int)

/**
 * Everything the stitcher needs, persisted as session.json next to the frames.
 * tanLong/tanShort describe the sensor field of view: tan(FOV/2) along the long and short axes.
 */
class CaptureSession(
    val dir: File,
    val tanLong: Double,
    val tanShort: Double,
    val yaw0: Double,
    val frames: MutableList<Frame> = mutableListOf(),
) {
    fun save() {
        val arr = JSONArray()
        for (f in frames) {
            arr.put(JSONObject().apply {
                put("file", f.file.name)
                put("target", f.targetIndex)
                put("r", JSONArray().apply { f.rotation.forEach { put(it.toDouble()) } })
            })
        }
        val json = JSONObject().apply {
            put("tanLong", tanLong)
            put("tanShort", tanShort)
            put("yaw0", yaw0)
            put("frames", arr)
        }
        File(dir, FILE).writeText(json.toString(2))
    }

    companion object {
        const val FILE = "session.json"

        fun load(dir: File): CaptureSession {
            val json = JSONObject(File(dir, FILE).readText())
            val frames = mutableListOf<Frame>()
            val arr = json.getJSONArray("frames")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val ra = o.getJSONArray("r")
                frames += Frame(
                    File(dir, o.getString("file")),
                    FloatArray(9) { ra.getDouble(it).toFloat() },
                    o.optInt("target", -1),
                )
            }
            return CaptureSession(
                dir,
                json.getDouble("tanLong"),
                json.getDouble("tanShort"),
                json.getDouble("yaw0"),
                frames,
            )
        }
    }
}
