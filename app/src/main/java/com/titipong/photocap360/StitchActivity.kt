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
        padForSystemBars()

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
                result.bitmap.recycle()
                withContext(Dispatchers.IO) {
                    File(dir, PanoStore.PANO).writeBytes(bytes)
                    File(dir, PanoStore.THUMB).delete()
                    PanoStore.deleteGalleryCopy(this@StitchActivity, dir) // re-stitch replaces the old copy
                    val name = "PANO360_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
                    galleryUri = try { PanoSaver.saveToGallery(this@StitchActivity, bytes, name) } catch (e: Exception) {
                        Log.e("Stitch", "gallery save failed", e); null
                    }
                    galleryUri?.let { File(dir, PanoStore.GALLERY_URI).writeText(it.toString()) }
                }
                if (galleryUri != null) Toast.makeText(this@StitchActivity,
                    getString(R.string.saved_to, PhotoFolder.displayName(this@StitchActivity)), Toast.LENGTH_LONG).show()
                startActivity(Intent(this@StitchActivity, ViewerActivity::class.java).putExtra(EXTRA_DIR, dir.absolutePath))
                finish()
            } catch (e: Throwable) {
                Log.e("Stitch", "failed", e)
                status.text = "ต่อภาพไม่สำเร็จ: ${e.message}"
            }
        }
    }
}
