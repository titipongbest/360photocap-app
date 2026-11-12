package com.titipong.photocap360

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * One entry on the home screen.
 *  - [dir]: a capture session made by this app (frames + session.json), null for images that only
 *    exist in the Pictures/360PhotoCap folder (e.g. copied there from elsewhere).
 *  - [uri]: the image in Pictures/360PhotoCap, null if the session has no copy there.
 */
data class PanoItem(
    val dir: File?,
    val uri: Uri?,
    val name: String,
    val time: Long,
    val frames: Int,
    val stitched: Boolean,
) {
    val viewable: Boolean get() = stitched || uri != null
}

/**
 * Sessions live in filesDir/sessions/<timestamp>/:
 *   frame_*.jpg   captured frames          session.json  orientations
 *   pano.jpg      stitched 360° image       diag.txt      stitch diagnostics
 *   thumb.jpg     list thumbnail (cached)   gallery_uri.txt  copy saved in Pictures/360PhotoCap
 */
object PanoStore {
    const val PANO = "pano.jpg"
    const val THUMB = "thumb.jpg"
    const val DIAG = "diag.txt"
    /** Result of "stitch with AI depth", kept beside the normal one until the user picks it. */
    const val PANO_AI = "pano_ai.jpg"
    const val DIAG_AI = "diag_ai.txt"
    const val GALLERY_URI = "gallery_uri.txt"
    const val FOLDER = "360PhotoCap"

    fun root(ctx: Context) = File(ctx.filesDir, "sessions")

    fun frames(dir: File) = dir.listFiles { f -> f.name.startsWith("frame_") && f.name.endsWith(".jpg") }?.size ?: 0

    private class FolderImage(val uri: Uri, val name: String, val time: Long)

    /** Images in Pictures/360PhotoCap (all of them with read permission, otherwise this app's own). */
    @Suppress("DEPRECATION")
    private fun queryFolder(ctx: Context): List<FolderImage> {
        val coll = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.ImageColumns.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED,
        )
        val (sel, args) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?" to arrayOf("${Environment.DIRECTORY_PICTURES}/$FOLDER%")
        } else {
            "${MediaStore.Images.Media.DATA} LIKE ?" to arrayOf("%/${Environment.DIRECTORY_PICTURES}/$FOLDER/%")
        }
        val out = ArrayList<FolderImage>()
        try {
            ctx.contentResolver.query(coll, proj, sel, args, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val taken = c.getLong(2)
                    val added = c.getLong(3) * 1000
                    out += FolderImage(ContentUris.withAppendedId(coll, id), c.getString(1) ?: "$id", if (taken > 0) taken else added)
                }
            }
        } catch (e: Exception) { Log.w("PanoStore", "query folder", e) }
        return out
    }

    /** Everything to show on the home screen, newest first. Empty sessions are removed. */
    fun list(ctx: Context): List<PanoItem> {
        val sessions = HashMap<String, File>()   // gallery uri → session dir
        val loose = ArrayList<File>()            // sessions without a gallery copy
        for (d in root(ctx).listFiles { f -> f.isDirectory } ?: emptyArray()) {
            val stitched = File(d, PANO).exists()
            if (!stitched && frames(d) == 0) { d.deleteRecursively(); continue }
            val u = galleryUri(d)
            if (u != null) sessions[u.toString()] = d else loose += d
        }
        val out = ArrayList<PanoItem>()
        for (img in queryFolder(ctx)) {
            val d = sessions.remove(img.uri.toString())
            out += PanoItem(d, img.uri, img.name, d?.name?.toLongOrNull() ?: img.time,
                d?.let { frames(it) } ?: 0, d != null && File(d, PANO).exists())
        }
        // Sessions whose gallery copy is gone, or that were never stitched.
        for (d in loose + sessions.values) {
            out += PanoItem(d, null, d.name, d.name.toLongOrNull() ?: d.lastModified(), frames(d), File(d, PANO).exists())
        }
        return out.sortedByDescending { it.time }
    }

    private fun sampleFor(width: Int, maxW: Int): Int {
        var s = 1
        while (width / (s * 2) >= maxW) s *= 2
        return s
    }

    private fun scaleTo(bmp: Bitmap, maxW: Int) =
        if (bmp.width > maxW) Bitmap.createScaledBitmap(bmp, maxW, bmp.height * maxW / bmp.width, true) else bmp

    /** Thumbnail ~[maxW] px wide from the session (cached thumb.jpg) or the folder image. */
    fun thumbnail(ctx: Context, item: PanoItem, maxW: Int = 720): Bitmap? {
        val dir = item.dir
        if (dir != null) {
            val cached = File(dir, THUMB)
            if (cached.exists()) BitmapFactory.decodeFile(cached.absolutePath)?.let { return it }
            val src = File(dir, PANO).takeIf { it.exists() }
                ?: if (item.uri == null) dir.listFiles { f -> f.name.startsWith("frame_") }?.minByOrNull { it.name } else null
            if (src != null) {
                val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(src.absolutePath, b)
                val bmp = BitmapFactory.decodeFile(src.absolutePath, BitmapFactory.Options().apply { inSampleSize = sampleFor(b.outWidth, maxW) })
                    ?: return null
                val scaled = scaleTo(bmp, maxW)
                if (src.name == PANO) try {
                    cached.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                } catch (e: Exception) { Log.w("PanoStore", "thumb cache", e) }
                return scaled
            }
        }
        val uri = item.uri ?: return null
        return try {
            val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, b) }
            val bmp = ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sampleFor(b.outWidth, maxW) })
            } ?: return null
            scaleTo(bmp, maxW)
        } catch (e: Exception) { Log.w("PanoStore", "thumb uri", e); null }
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
