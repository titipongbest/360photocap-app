package com.titipong.photocap360

import android.app.AlertDialog
import android.app.RecoverableSecurityException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.IntentSender
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * Shows one panorama: drag to look around, info (+ copy), re-stitch, share, delete.
 * Opened either for a capture session (EXTRA_DIR) or for any image in the app's folder (EXTRA_URI).
 */
class ViewerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URI = "uri"
        /** Show the AI-depth result (pano_ai.jpg) of the session instead of the normal one. */
        const val EXTRA_AI = "ai"
    }

    private lateinit var root: FrameLayout
    private var dir: File? = null
    private var uri: Uri? = null
    private var viewer: PanoViewerView? = null
    private var showAi = false

    private val deleteRequest = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode == RESULT_OK) finish()
    }

    override fun onPause() { super.onPause(); viewer?.onPause() }
    override fun onResume() { super.onResume(); viewer?.onResume() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dir = intent.getStringExtra(StitchActivity.EXTRA_DIR)?.let { File(it) }
        uri = intent.getStringExtra(EXTRA_URI)?.let { Uri.parse(it) }
        showAi = intent.getBooleanExtra(EXTRA_AI, false) && dir?.let { File(it, PanoStore.PANO_AI).exists() } == true
        if (dir == null && uri == null) { finish(); return }
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(ProgressBar(this), FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        setContentView(root)
        padForSystemBars()

        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { runCatching { load() }.getOrNull() }
            if (loaded == null) {
                Toast.makeText(this@ViewerActivity, "เปิดภาพไม่ได้", Toast.LENGTH_LONG).show()
                finish(); return@launch
            }
            show(loaded.first, loaded.second)
        }
    }

    private fun open(): InputStream? = dir?.let { File(it, if (showAi) PanoStore.PANO_AI else PanoStore.PANO).inputStream() }
        ?: uri?.let { contentResolver.openInputStream(it) }

    /** Decodes the panorama (≤ 4096 px wide for the GPU) and builds the info text. */
    private fun load(): Pair<Bitmap, String>? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, b) }
        var sample = 1
        while (b.outWidth / sample > 4096) sample *= 2
        val bmp = open()?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: return null
        val d = dir
        val info = if (d != null) {
            File(d, if (showAi) PanoStore.DIAG_AI else PanoStore.DIAG).takeIf { it.exists() }?.readText() ?: getString(R.string.no_info)
        } else {
            val name = uri?.let { u ->
                contentResolver.query(u, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            } ?: uri.toString()
            val ratio = if (b.outHeight > 0) b.outWidth.toDouble() / b.outHeight else 0.0
            "ไฟล์: $name\nขนาด ${b.outWidth}×${b.outHeight}" +
                (if (kotlin.math.abs(ratio - 2.0) > 0.05) "\n⚠ ไม่ใช่สัดส่วน 2:1 อาจแสดงเพี้ยน" else "") +
                "\nไม่ได้ถ่ายด้วยแอพนี้ หรือไม่มีภาพต้นฉบับ จึงต่อภาพใหม่ไม่ได้"
        }
        return bmp to info
    }

    private fun show(bmp: Bitmap, infoText: String) {
        root.removeAllViews()
        val v = PanoViewerView(this, bmp).also { viewer = it }
        root.addView(v, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val hint = TextView(this).apply {
            setText(if (showAi) R.string.ai_viewing else R.string.viewer_hint); setTextColor(Color.WHITE); textSize = 14f
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
        }
        root.addView(hint, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = 24 })

        // Info panel: text + copy button.
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xDD000000.toInt()); setPadding(32, 24, 32, 24)
            visibility = View.GONE
        }
        val text = TextView(this).apply {
            text = infoText; setTextColor(Color.WHITE); textSize = 12f
            setLineSpacing(4f, 1f); setTextIsSelectable(true)
        }
        val copy = Button(this).apply {
            setText(R.string.copy); textSize = 13f; isAllCaps = false
            setTextColor(Color.BLACK); setBackgroundResource(R.drawable.btn_primary)
            compactButton()
            setOnClickListener {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("360 PhotoCap", infoText))
                if (Build.VERSION.SDK_INT < 33) Toast.makeText(this@ViewerActivity, R.string.copied, Toast.LENGTH_SHORT).show()
            }
        }
        panel.addView(text, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        panel.addView(copy, LinearLayout.LayoutParams(WRAP_CONTENT, dp(40f)).apply { topMargin = dp(8f); gravity = Gravity.END })
        root.addView(panel, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP).apply { topMargin = 96 })

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(24, 24, 24, 32) }
        fun button(label: Int, primary: Boolean = false, onClick: () -> Unit) = Button(this).apply {
            setText(label); textSize = 14f; isAllCaps = false
            setTextColor(if (primary) Color.BLACK else Color.WHITE)
            setBackgroundResource(if (primary) R.drawable.btn_primary else R.drawable.btn_outline)
            compactButton()
            setOnClickListener { onClick() }
        }
        val buttons = ArrayList<Button>()
        buttons += button(R.string.info) { panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        val d = dir
        if (showAi && d != null) {
            buttons += button(R.string.ai_original) { openVariant(d, ai = false) }
            buttons += button(R.string.ai_use) { confirmUseAi(d) }
        } else {
            if (d != null && PanoStore.frames(d) > 0) buttons += button(R.string.edit) {
                startActivity(Intent(this, EditorActivity::class.java).putExtra(StitchActivity.EXTRA_DIR, d.absolutePath))
                finish()
            }
            buttons += button(R.string.delete) { confirmDelete() }
        }
        buttons += button(R.string.share, primary = true) { share() }
        for ((i, b) in buttons.withIndex()) {
            bar.addView(b, LinearLayout.LayoutParams(0, dp(46f), 1f).apply { if (i < buttons.lastIndex) marginEnd = dp(8f) })
        }
        root.addView(bar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))

        // 360° toggle (top right): move the phone to look around (gyro).
        val gyroBtn = android.widget.ImageButton(this).apply {
            setImageResource(R.drawable.ic_360)
            contentDescription = getString(R.string.gyro_desc)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
            setOnClickListener {
                v.gyro = !v.gyro
                styleToggle(this, v.gyro)
                Toast.makeText(this@ViewerActivity, if (v.gyro) R.string.gyro_on else R.string.gyro_off, Toast.LENGTH_SHORT).show()
            }
        }
        styleToggle(gyroBtn, false)
        if (!v.hasGyro) { gyroBtn.isEnabled = false; gyroBtn.alpha = 0.4f }
        root.addView(gyroBtn, FrameLayout.LayoutParams(dp(52f), dp(52f), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(40f); marginEnd = dp(12f)
        })

        // "AI" (below the 360° button): stitch again using AI depth, or open the AI result.
        if (!showAi && d != null && PanoStore.frames(d) > 0 && !File(d, EditState.MANUAL).exists()) {
            val aiBtn = Button(this).apply {
                text = "AI"; textSize = 14f; isAllCaps = false
                setTextColor(Color.WHITE); setBackgroundResource(R.drawable.btn_outline_round)
                compactButton()
                contentDescription = getString(R.string.ai_desc)
                setOnClickListener { onAiButton(d) }
            }
            root.addView(aiBtn, FrameLayout.LayoutParams(dp(52f), dp(52f), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(100f); marginEnd = dp(12f)
            })
        }
    }

    private fun openVariant(d: File, ai: Boolean) {
        startActivity(Intent(this, ViewerActivity::class.java)
            .putExtra(StitchActivity.EXTRA_DIR, d.absolutePath).putExtra(EXTRA_AI, ai))
        finish()
    }

    private fun startAiStitch(d: File) {
        startActivity(Intent(this, StitchActivity::class.java)
            .putExtra(StitchActivity.EXTRA_DIR, d.absolutePath).putExtra(StitchActivity.EXTRA_AI, true))
        finish()
    }

    private fun onAiButton(d: File) {
        if (File(d, PanoStore.PANO_AI).exists()) {
            AlertDialog.Builder(this)
                .setMessage(R.string.ai_exists)
                .setPositiveButton(R.string.ai_view) { _, _ -> openVariant(d, ai = true) }
                .setNeutralButton(R.string.ai_redo) { _, _ -> startAiStitch(d) }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.ai_title)
            .setMessage(getString(R.string.ai_explain) + if (DepthModel.isDownloaded(this)) "" else "\n\n" + getString(R.string.ai_download_note))
            .setPositiveButton(R.string.ai_start) { _, _ -> startAiStitch(d) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Make the AI result the main panorama (replaces pano.jpg and the gallery copy). */
    private fun confirmUseAi(d: File) {
        AlertDialog.Builder(this)
            .setMessage(R.string.ai_use_confirm)
            .setPositiveButton(R.string.ai_use) { _, _ ->
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        try {
                            val bytes = File(d, PanoStore.PANO_AI).readBytes()
                            File(d, PanoStore.PANO).writeBytes(bytes)
                            File(d, PanoStore.DIAG_AI).takeIf { it.exists() }?.copyTo(File(d, PanoStore.DIAG), overwrite = true)
                            File(d, PanoStore.PANO_AI).delete(); File(d, PanoStore.DIAG_AI).delete()
                            File(d, PanoStore.THUMB).delete()
                            PanoStore.deleteGalleryCopy(this@ViewerActivity, d)
                            val name = "PANO360_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date()) + "_AI.jpg"
                            PanoSaver.saveToGallery(this@ViewerActivity, bytes, name)?.let {
                                File(d, PanoStore.GALLERY_URI).writeText(it.toString())
                            }
                            true
                        } catch (e: Exception) { android.util.Log.e("Viewer", "use AI", e); false }
                    }
                    if (ok) Toast.makeText(this@ViewerActivity, R.string.saved, Toast.LENGTH_LONG).show()
                    openVariant(d, ai = false)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun styleToggle(b: android.widget.ImageButton, on: Boolean) {
        b.setBackgroundResource(if (on) R.drawable.btn_primary else R.drawable.btn_outline_round)
        b.setColorFilter(if (on) Color.BLACK else Color.WHITE)
    }

    private fun share() {
        val d = dir
        val shareUri = if (d != null) FileProvider.getUriForFile(this, "$packageName.files", File(d, if (showAi) PanoStore.PANO_AI else PanoStore.PANO)) else uri!!
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"; putExtra(Intent.EXTRA_STREAM, shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, null))
    }

    private fun confirmDelete() {
        val d = dir
        AlertDialog.Builder(this)
            .setMessage(if (d != null) R.string.delete_confirm else R.string.delete_file_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                if (d != null) lifecycleScope.launch {
                    withContext(Dispatchers.IO) { PanoStore.delete(this@ViewerActivity, d) }
                    finish()
                } else deleteUri(uri!!)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Images this app didn't create need the user's confirmation (Android 10+). */
    private fun deleteUri(u: Uri) {
        try {
            contentResolver.delete(u, null, null)
            finish()
        } catch (e: SecurityException) {
            val sender: IntentSender? = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                    MediaStore.createDeleteRequest(contentResolver, listOf(u)).intentSender
                Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && e is RecoverableSecurityException ->
                    e.userAction.actionIntent.intentSender
                else -> null
            }
            if (sender != null) deleteRequest.launch(IntentSenderRequest.Builder(sender).build())
            else Toast.makeText(this, R.string.delete_failed, Toast.LENGTH_LONG).show()
        }
    }
}
