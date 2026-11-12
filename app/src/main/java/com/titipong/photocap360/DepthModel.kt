package com.titipong.photocap360

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlin.math.roundToInt

/**
 * Depth Anything V2 Small (Apache-2.0), int8 ONNX export from onnx-community, run with ONNX Runtime
 * on the CPU. Downloaded once (≈27 MB) into the app's private storage and checked by SHA-256.
 *
 * Input  pixel_values  [1, 3, H, W], RGB, ImageNet mean/std, H and W multiples of 14.
 * Output predicted_depth [1, H, W]: relative inverse depth (larger = nearer), affine-invariant.
 */
class DepthModel private constructor(private val session: OrtSession) : AutoCloseable {

    companion object {
        const val URL_MODEL = "https://huggingface.co/onnx-community/depth-anything-v2-small/resolve/main/onnx/model_quantized.onnx"
        private const val SHA256 = "fcf51f1b230362b28690bb9d1809bf0431f29cad20534e3f589bd7285547f20d"
        const val SIZE_BYTES = 27_258_801L
        /** Long side of the network input (24 patches of 14 px). */
        private const val INPUT_LONG = 336
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

        private fun file(ctx: Context) = File(File(ctx.filesDir, "models"), "depth_anything_v2_small_int8.onnx")

        fun isDownloaded(ctx: Context) = file(ctx).let { it.exists() && it.length() == SIZE_BYTES }

        /** Downloads the model if needed. [progress] gets 0..1. Throws on failure. */
        fun download(ctx: Context, progress: (Float) -> Unit) {
            val dst = file(ctx)
            if (isDownloaded(ctx)) return
            dst.parentFile?.mkdirs()
            val tmp = File(dst.parentFile, dst.name + ".part")
            var url = URL(URL_MODEL)
            var conn: HttpURLConnection
            var hops = 0
            while (true) {
                conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000; readTimeout = 30_000; instanceFollowRedirects = false
                }
                val code = conn.responseCode
                if (code in 300..399 && hops++ < 5) {
                    url = URL(url, conn.getHeaderField("Location")); conn.disconnect(); continue
                }
                if (code != 200) { conn.disconnect(); error("HTTP $code") }
                break
            }
            val md = MessageDigest.getInstance("SHA-256")
            conn.inputStream.use { inp ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val r = inp.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r); md.update(buf, 0, r)
                        total += r
                        progress((total.toDouble() / SIZE_BYTES).toFloat().coerceAtMost(1f))
                    }
                }
            }
            conn.disconnect()
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            if (hex != SHA256) { tmp.delete(); error("ไฟล์โมเดลไม่ถูกต้อง") }
            if (!tmp.renameTo(dst)) error("บันทึกโมเดลไม่ได้")
        }

        fun load(ctx: Context): DepthModel {
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
            }
            return DepthModel(env.createSession(file(ctx).absolutePath, opts))
        }
    }

    /** Relative inverse depth for an upright ARGB frame. */
    fun infer(pixels: IntArray, width: Int, height: Int): DepthImage {
        val long = maxOf(width, height)
        fun dim(v: Int) = maxOf(14, (v.toDouble() * INPUT_LONG / long / 14).roundToInt() * 14)
        val w = dim(width); val h = dim(height)
        val src = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        if (small !== src) src.recycle()
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        small.recycle()
        val plane = w * h
        val data = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val c = px[i]
            data[i] = (((c shr 16) and 0xFF) / 255f - MEAN[0]) / STD[0]
            data[plane + i] = (((c shr 8) and 0xFF) / 255f - MEAN[1]) / STD[1]
            data[2 * plane + i] = ((c and 0xFF) / 255f - MEAN[2]) / STD[2]
        }
        val env = OrtEnvironment.getEnvironment()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 3, h.toLong(), w.toLong())).use { input ->
            session.run(mapOf(session.inputNames.first() to input)).use { out ->
                val t = out.get(0) as OnnxTensor
                val shape = t.info.shape
                val oh = shape[shape.size - 2].toInt(); val ow = shape[shape.size - 1].toInt()
                val fb = t.floatBuffer
                val v = FloatArray(ow * oh); fb.get(v)
                return DepthImage(ow, oh, v)
            }
        }
    }

    override fun close() = session.close()
}
