package com.titipong.photocap360

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Writes the panorama as JPEG with Google Photo Sphere (GPano) XMP metadata, so Google Photos,
 * Facebook and other 360 viewers recognise it as a 360° photo.
 */
object PanoSaver {

    fun encode(bmp: Bitmap, quality: Int = 92): ByteArray {
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        return injectXmp(bos.toByteArray(), bmp.width, bmp.height)
    }

    fun gpanoXmp(w: Int, h: Int): String = """
        <x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="360PhotoCap">
         <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
          <rdf:Description rdf:about="" xmlns:GPano="http://ns.google.com/photos/1.0/panorama/">
           <GPano:ProjectionType>equirectangular</GPano:ProjectionType>
           <GPano:UsePanoramaViewer>True</GPano:UsePanoramaViewer>
           <GPano:CaptureSoftware>360PhotoCap</GPano:CaptureSoftware>
           <GPano:FullPanoWidthPixels>$w</GPano:FullPanoWidthPixels>
           <GPano:FullPanoHeightPixels>$h</GPano:FullPanoHeightPixels>
           <GPano:CroppedAreaImageWidthPixels>$w</GPano:CroppedAreaImageWidthPixels>
           <GPano:CroppedAreaImageHeightPixels>$h</GPano:CroppedAreaImageHeightPixels>
           <GPano:CroppedAreaLeftPixels>0</GPano:CroppedAreaLeftPixels>
           <GPano:CroppedAreaTopPixels>0</GPano:CroppedAreaTopPixels>
           <GPano:PoseHeadingDegrees>0</GPano:PoseHeadingDegrees>
          </rdf:Description>
         </rdf:RDF>
        </x:xmpmeta>
    """.trimIndent()

    /** Inserts an APP1 XMP segment right after the JPEG SOI marker (FF D8). */
    fun injectXmp(jpeg: ByteArray, w: Int, h: Int): ByteArray {
        require(jpeg.size > 2 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) { "not a JPEG" }
        val payload = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII) +
            gpanoXmp(w, h).toByteArray(Charsets.UTF_8)
        val len = payload.size + 2
        require(len <= 0xFFFF)
        val seg = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), (len and 0xFF).toByte()) + payload
        return jpeg.copyOfRange(0, 2) + seg + jpeg.copyOfRange(2, jpeg.size)
    }

    /** Saves into the chosen folder (PhotoFolder) or Pictures/360PhotoCap, and returns the image Uri. */
    fun saveToGallery(ctx: Context, bytes: ByteArray, name: String): Uri? {
        PhotoFolder.tree(ctx)?.let { return PhotoFolder.save(ctx, it, bytes, name) }
        val resolver = ctx.contentResolver
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/360PhotoCap")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "360PhotoCap")
            dir.mkdirs()
            val file = File(dir, name)
            file.writeBytes(bytes)
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                @Suppress("DEPRECATION")
                put(MediaStore.Images.Media.DATA, file.absolutePath)
            }
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        }
    }
}
