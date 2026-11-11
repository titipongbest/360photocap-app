package com.titipong.photocap360

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.Bitmap
import android.os.Bundle
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Home: the 360° photos taken with this app, plus a camera button to start a new capture. */
class MainActivity : ComponentActivity() {

    private lateinit var empty: TextView
    private val adapter = Adapter()
    private val thumbs = LruCache<String, Bitmap>(24)
    private val dateFmt = SimpleDateFormat("d MMM yyyy · HH:mm", Locale("th", "TH"))

    // Reading images in Pictures/360PhotoCap that this app didn't create needs media permission.
    private val readPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE
    private val requestRead = registerForActivityResult(ActivityResultContracts.RequestPermission()) { reload() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        padForSystemBars()
        empty = findViewById(R.id.empty)
        findViewById<TextView>(R.id.version).text = "v${BuildConfig.VERSION_NAME} · ${BuildConfig.GIT_SHA}"
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }
        findViewById<View>(R.id.camera).setOnClickListener {
            startActivity(Intent(this, IntroActivity::class.java))
        }
        if (savedInstanceState == null &&
            ContextCompat.checkSelfPermission(this, readPermission) != PackageManager.PERMISSION_GRANTED
        ) requestRead.launch(readPermission)
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { PanoStore.list(this@MainActivity) }
            // A re-stitched panorama has a new thumbnail: drop cached bitmaps that are gone.
            thumbs.evictAll()
            adapter.items = items
            adapter.notifyDataSetChanged()
            empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun open(item: PanoItem) {
        val i = when {
            item.viewable -> Intent(this, ViewerActivity::class.java).apply {
                if (item.stitched) putExtra(StitchActivity.EXTRA_DIR, item.dir!!.absolutePath)
                else putExtra(ViewerActivity.EXTRA_URI, item.uri.toString())
            }
            else -> Intent(this, StitchActivity::class.java).putExtra(StitchActivity.EXTRA_DIR, item.dir!!.absolutePath)
        }
        startActivity(i)
    }

    private class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val title: TextView = v.findViewById(R.id.title)
        val sub: TextView = v.findViewById(R.id.sub)
        var key: String? = null
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        var items: List<PanoItem> = emptyList()

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_pano, parent, false))

        override fun onBindViewHolder(h: Holder, position: Int) {
            val item = items[position]
            h.title.text = dateFmt.format(Date(item.time))
            h.sub.text = when {
                item.dir == null -> item.name
                item.viewable -> "${item.frames} ภาพ"
                else -> "${item.frames} ภาพ · " + getString(R.string.not_stitched)
            }
            h.itemView.setOnClickListener { open(item) }
            val key = item.uri?.toString() ?: item.dir!!.absolutePath
            h.key = key
            val cached = thumbs.get(key)
            h.thumb.setImageBitmap(cached)
            if (cached == null) lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) { runCatching { PanoStore.thumbnail(this@MainActivity, item) }.getOrNull() }
                if (bmp != null) {
                    thumbs.put(key, bmp)
                    if (h.key == key) h.thumb.setImageBitmap(bmp)
                }
            }
        }
    }
}
