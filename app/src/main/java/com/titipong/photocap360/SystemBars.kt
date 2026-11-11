package com.titipong.photocap360

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Android 15 (targetSdk 35) draws every app edge-to-edge, under the status and navigation bars.
 * Pad the content view by the system bar / display cutout sizes so nothing is hidden behind them.
 */
fun Activity.padForSystemBars() {
    val content = findViewById<View>(android.R.id.content)
    ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
        val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        v.setPadding(b.left, b.top, b.right, b.bottom)
        WindowInsetsCompat.CONSUMED
    }
    ViewCompat.requestApplyInsets(content)
}

/** dp → px. */
fun Context.dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

/**
 * Buttons made in code: the platform theme gives them a 48dp minimum height and ~12dp vertical
 * padding, which clips the text when the button is sized smaller. Remove both; keep side padding.
 */
fun TextView.compactButton() {
    minHeight = 0; minimumHeight = 0
    setPadding(context.dp(12f), 0, context.dp(12f), 0)
}
