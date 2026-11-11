package com.titipong.photocap360

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StitchActivity : ComponentActivity() {

    companion object { const val EXTRA_DIR = "dir" }

    private lateinit var root: FrameLayout
    private lateinit var status: TextView
    private lateinit var bar: ProgressBar
    private var galleryUri: Uri? = null
    private var started = false
    private var viewer: PanoViewerView? = null

    override fun onPause() { super.onPause(); viewer?.onPause() }
    override fun onResume() { super.onResume(); viewer?.onResume() }

    private val requestWrite = registerForActivityResult(ActivityResultContracts.RequestPermission()) { run() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(64, 0, 64, 0)
        }
        status = TextView(this).apply {
            setText(R.string.stitching); setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER
        }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        box.addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        box.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 32 })
        root.addView(box, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) requestWrite.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) else run()
    }

    private fun run() {
        if (started) return
        started = true
        val dir = File(intent.getStringExtra(EXTRA_DIR) ?: run { finish(); return })
        lifecycleScope.launch {
            try {
                val session = withContext(Dispatchers.IO) { CaptureSession.load(dir) }
                val t0 = System.currentTimeMillis()
                val result = Stitcher.stitch(session) { p ->
                    runOnUiThread { bar.progress = (p * 1000).toInt() }
                }
                Log.i("Stitch", "${result.width}x${result.height} in ${System.currentTimeMillis() - t0} ms")
                val bytes = withContext(Dispatchers.Default) { PanoSaver.encode(result.bitmap) }
                withContext(Dispatchers.IO) {
                    File(dir, "pano.jpg").writeBytes(bytes)
                    val name = "PANO360_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
                    galleryUri = try { PanoSaver.saveToGallery(this@StitchActivity, bytes, name) } catch (e: Exception) {
                        Log.e("Stitch", "gallery save failed", e); null
                    }
                }
                if (galleryUri != null) Toast.makeText(this@StitchActivity, R.string.saved, Toast.LENGTH_LONG).show()
                showViewer(result)
            } catch (e: Throwable) {
                Log.e("Stitch", "failed", e)
                status.text = "ต่อภาพไม่สำเร็จ: ${e.message}"
            }
        }
    }

    private fun showViewer(result: Stitcher.Result) {
        root.removeAllViews()
        val v = PanoViewerView(this, result.bitmap).also { viewer = it }
        root.addView(v, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val hint = TextView(this).apply {
            setText(R.string.viewer_hint); setTextColor(Color.WHITE); textSize = 14f
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
        }
        root.addView(hint, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = 48 })

        val info = TextView(this).apply {
            text = result.diag; setTextColor(Color.WHITE); textSize = 12f
            setBackgroundColor(0xCC000000.toInt()); setPadding(32, 24, 32, 24)
            setLineSpacing(4f, 1f)
            visibility = android.view.View.GONE
            setOnClickListener { visibility = android.view.View.GONE }
        }
        root.addView(info, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP).apply { topMargin = 120 })

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(32, 32, 32, 48) }
        val infoBtn = Button(this).apply {
            text = "ข้อมูล"; setTextColor(Color.WHITE); setBackgroundResource(R.drawable.btn_outline)
            setOnClickListener {
                info.visibility = if (info.visibility == android.view.View.VISIBLE) android.view.View.GONE else android.view.View.VISIBLE
            }
        }
        val again = Button(this).apply {
            setText(R.string.new_capture); setTextColor(Color.WHITE); setBackgroundResource(R.drawable.btn_outline)
            setOnClickListener { startActivity(Intent(this@StitchActivity, CaptureActivity::class.java)); finish() }
        }
        val share = Button(this).apply {
            setText(R.string.share); setTextColor(Color.BLACK); setBackgroundResource(R.drawable.btn_primary)
            isEnabled = galleryUri != null
            setOnClickListener {
                val uri = galleryUri ?: return@setOnClickListener
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"; putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, null))
            }
        }
        bar.addView(infoBtn, LinearLayout.LayoutParams(0, 140, 0.8f).apply { marginEnd = 24 })
        bar.addView(again, LinearLayout.LayoutParams(0, 140, 1f).apply { marginEnd = 24 })
        bar.addView(share, LinearLayout.LayoutParams(0, 140, 1f))
        root.addView(bar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
    }
}
