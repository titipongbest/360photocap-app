package com.titipong.photocap360

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Shows one stitched panorama: drag to look around, info, re-stitch, share, delete. */
class ViewerActivity : ComponentActivity() {

    private lateinit var root: FrameLayout
    private lateinit var dir: File
    private var viewer: PanoViewerView? = null

    override fun onPause() { super.onPause(); viewer?.onPause() }
    override fun onResume() { super.onResume(); viewer?.onResume() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dir = File(intent.getStringExtra(StitchActivity.EXTRA_DIR) ?: run { finish(); return })
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(ProgressBar(this), FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        setContentView(root)
        padForSystemBars()

        lifecycleScope.launch {
            val (bmp, diag) = withContext(Dispatchers.IO) {
                val f = File(dir, PanoStore.PANO)
                val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.absolutePath, b)
                var sample = 1
                while (b.outWidth / sample > 4096) sample *= 2  // GPU texture limit
                val bitmap = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
                bitmap to File(dir, PanoStore.DIAG).takeIf { it.exists() }?.readText()
            }
            if (bmp == null) { finish(); return@launch }
            show(bmp, diag)
        }
    }

    private fun show(bmp: Bitmap, diag: String?) {
        root.removeAllViews()
        val v = PanoViewerView(this, bmp).also { viewer = it }
        root.addView(v, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val hint = TextView(this).apply {
            setText(R.string.viewer_hint); setTextColor(Color.WHITE); textSize = 14f
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
        }
        root.addView(hint, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = 24 })

        val info = TextView(this).apply {
            text = diag ?: getString(R.string.no_info); setTextColor(Color.WHITE); textSize = 12f
            setBackgroundColor(0xCC000000.toInt()); setPadding(32, 24, 32, 24)
            setLineSpacing(4f, 1f)
            visibility = View.GONE
            setOnClickListener { visibility = View.GONE }
        }
        root.addView(info, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP).apply { topMargin = 96 })

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(24, 24, 24, 32) }
        fun button(text: Int, primary: Boolean = false, onClick: () -> Unit) = Button(this).apply {
            setText(text); textSize = 14f; isAllCaps = false
            setTextColor(if (primary) Color.BLACK else Color.WHITE)
            setBackgroundResource(if (primary) R.drawable.btn_primary else R.drawable.btn_outline)
            setOnClickListener { onClick() }
        }
        val buttons = listOf(
            button(R.string.info) { info.visibility = if (info.visibility == View.VISIBLE) View.GONE else View.VISIBLE },
            button(R.string.restitch) {
                startActivity(Intent(this, StitchActivity::class.java).putExtra(StitchActivity.EXTRA_DIR, dir.absolutePath))
                finish()
            },
            button(R.string.delete) { confirmDelete() },
            button(R.string.share, primary = true) { share() },
        )
        for ((i, b) in buttons.withIndex()) {
            bar.addView(b, LinearLayout.LayoutParams(0, 130, 1f).apply { if (i < buttons.lastIndex) marginEnd = 16 })
        }
        root.addView(bar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
    }

    private fun share() {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", File(dir, PanoStore.PANO))
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"; putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, null))
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setMessage(R.string.delete_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { PanoStore.delete(this@ViewerActivity, dir) }
                    finish()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
