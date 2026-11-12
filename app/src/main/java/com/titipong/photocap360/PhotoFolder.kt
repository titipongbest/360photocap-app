package com.titipong.photocap360

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log

/**
 * Where 360° photos are shown from and saved to.
 *  - default: Pictures/360PhotoCap through MediaStore (no folder chosen)
 *  - custom:  any folder the user picked with the system folder picker (Storage Access Framework).
 *    The tree Uri and its persistable read/write permission are kept across restarts.
 */
object PhotoFolder {

    class Image(val uri: Uri, val name: String, val time: Long)

    private const val PREFS = "settings"
    private const val KEY_TREE = "folder_tree"
    const val DEFAULT_NAME = "Pictures/360PhotoCap"

    private val IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/webp")

    /** The chosen folder, or null for the default (also null if its permission was revoked). */
    fun tree(ctx: Context): Uri? {
        val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TREE, null) ?: return null
        val uri = Uri.parse(s)
        val granted = ctx.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }
        return if (granted) uri else null
    }

    /** Use [uri] (from ACTION_OPEN_DOCUMENT_TREE) from now on; null = back to the default folder. */
    fun set(ctx: Context, uri: Uri?) {
        val old = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TREE, null)?.let { Uri.parse(it) }
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (uri != null) ctx.contentResolver.takePersistableUriPermission(uri, flags)
        if (old != null && old != uri) try { ctx.contentResolver.releasePersistableUriPermission(old, flags) } catch (_: Exception) {}
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TREE, uri?.toString()).apply()
    }

    private fun rootDoc(tree: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    /** Short name for the UI, e.g. "DCIM/360" (from the document id "primary:DCIM/360"). */
    fun displayName(ctx: Context): String {
        val tree = tree(ctx) ?: return DEFAULT_NAME
        val id = DocumentsContract.getTreeDocumentId(tree)
        val path = id.substringAfter(':', "")
        if (path.isNotEmpty()) return path
        return try {
            ctx.contentResolver.query(rootDoc(tree), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) { null } ?: id
    }

    /** Whether [uri] (a saved copy) is in the folder currently shown. */
    fun isCurrent(ctx: Context, uri: Uri): Boolean {
        val tree = tree(ctx)
        return if (tree == null) uri.authority == "media" else uri.toString().startsWith(tree.toString())
    }

    /** Images directly in the chosen folder (only for a custom folder). */
    fun list(ctx: Context, tree: Uri): List<Image> {
        val out = ArrayList<Image>()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val proj = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        try {
            ctx.contentResolver.query(children, proj, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(2) !in IMAGE_TYPES) continue
                    out += Image(DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)), c.getString(1) ?: "", c.getLong(3))
                }
            }
        } catch (e: Exception) { Log.w("PhotoFolder", "list", e) }
        return out
    }

    /** Writes a JPEG into the chosen folder. */
    fun save(ctx: Context, tree: Uri, bytes: ByteArray, name: String): Uri? {
        val doc = DocumentsContract.createDocument(ctx.contentResolver, rootDoc(tree), "image/jpeg", name) ?: return null
        ctx.contentResolver.openOutputStream(doc)?.use { it.write(bytes) }
        return doc
    }

    /** Deletes a saved image: a folder document or a MediaStore entry. Throws SecurityException like ContentResolver.delete. */
    fun delete(ctx: Context, uri: Uri): Boolean =
        if (DocumentsContract.isDocumentUri(ctx, uri)) DocumentsContract.deleteDocument(ctx.contentResolver, uri)
        else ctx.contentResolver.delete(uri, null, null) > 0
}
