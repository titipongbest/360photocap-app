package com.titipong.photocap360

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity

/** Intro tips (stand away from objects, phone at chest, rotate in place, aim at dots) → capture. */
class MainActivity : ComponentActivity() {

    private val tips = intArrayOf(R.string.tip1, R.string.tip2, R.string.tip3, R.string.tip4)
    private var page = 0

    private lateinit var step: TextView
    private lateinit var tip: TextView
    private lateinit var back: Button
    private lateinit var next: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        padForSystemBars()
        step = findViewById(R.id.step)
        tip = findViewById(R.id.tip)
        back = findViewById(R.id.back)
        next = findViewById(R.id.next)

        back.setOnClickListener { if (page == 0) startCapture() else { page--; render() } }
        next.setOnClickListener { if (page == tips.lastIndex) startCapture() else { page++; render() } }
        render()
    }

    private fun render() {
        step.text = "${page + 1} / ${tips.size}"
        tip.setText(tips[page])
        back.setText(if (page == 0) R.string.skip else R.string.back)
        next.setText(if (page == tips.lastIndex) R.string.start else R.string.next)
    }

    private fun startCapture() {
        startActivity(Intent(this, CaptureActivity::class.java))
        page = 0
        render()
    }
}
