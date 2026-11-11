package com.titipong.photocap360

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import java.io.File

/** One capture session on this device: its folder, the stitched panorama (if any) and frame count. */
data class PanoItem(val dir: File, val pano: File?, val time: Long, val frames: Int)

/**
 * The app's own panoramas live in filesDir/sessions/<timestamp>/:
 *   frame_*.jpg   captured frames          session.json  orientations
 *   pano.jpg      stitched 360° image       diag.txt      stitch diagnostics
 *   thumb.jpg     list thumbnail (cached)   gallery_uri.txt  copy saved in Pictures/360PhotoCap
 */
object PanoStore {
    const val PANO = "pano.jpg"
    const val THUMB = "thumb.jpg"
    const val DIAG = "diag.txt"
    const val GALLERY_URI = "gallery_uri.txt"

    fun root(ctx: Context) = File(ctx.filesDir, "sessions")

    fun frames(dir: File) = dir.listFiles { f -> f.name.startsWith("frame_") && f.name.endsWith(".jpg") }?.size ?: 0

    /** All sessions, newest first. Empty sessions (camera opened, nothing shot) are removed. */
    fun list(ctx: Context): List<PanoItem> {
        val dirs = root(ctx).listFiles { f -> f.isDirectory } ?: return emptyList()
        val out = ArrayList<PanoItem>()
        for (d in dirs) {
            val pano = File(d, PANO).takeIf { it.exists() }
            val n = frames(d)
            if (pano == null && n == 0) { d.deleteRecursively(); continue }
            out += PanoItem(d, pano, d.name.toLongOrNull() ?: d.lastModified(), n)
        }
        return out.sortedByDescending { it.time }
    }

    /** Thumbnail ~[maxW] px wide: cached thumb.jpg, else from the panorama, else from the first frame. */
    fun thumbnail(item: PanoItem, maxW: Int = 720): Bitmap? {
        val cached = File(item.dir, THUMB)
        if (cached.exists()) BitmapFactory.decodeFile(cached.absolutePath)?.let { return it }
        val src = item.pano ?: item.dir.listFiles { f -> f.name.startsWith("frame_") }?.minByOrNull { it.name } ?: return null
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.absolutePath, b)
        var sample = 1
        while (b.outWidth / (sample * 2) >= maxW) sample *= 2
        val bmp = BitmapFactory.decodeFile(src.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scaled = if (bmp.width > maxW) Bitmap.createScaledBitmap(bmp, maxW, bmp.height * maxW / bmp.width, true) else bmp
        if (item.pano != null) try {
            cached.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        } catch (e: Exception) { Log.w("PanoStore", "thumb cache", e) }
        return scaled
    }

    fun galleryUri(dir: File): Uri? = File(dir, GALLERY_URI).takeIf { it.exists() }?.readText()?.trim()?.let { Uri.parse(it) }

    /** Removes the gallery copy (if this app still owns it). */
    fun deleteGalleryCopy(ctx: Context, dir: File) {
        val uri = galleryUri(dir) ?: return
        try { ctx.contentResolver.delete(uri, null, null) } catch (e: Exception) { Log.w("PanoStore", "gallery delete", e) }
        File(dir, GALLERY_URI).delete()
    }

    fun delete(ctx: Context, dir: File) {
        deleteGalleryCopy(ctx, dir)
        dir.deleteRecursively()
    }
}
