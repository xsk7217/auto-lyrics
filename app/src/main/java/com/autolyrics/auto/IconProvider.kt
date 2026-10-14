package com.autolyrics.auto

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import com.autolyrics.R
import java.io.File
import java.io.FileNotFoundException

/**
 * Serves Android Auto custom browse action icons as content:// URIs (what the
 * MediaConstants docs ask for): white-on-transparent PNGs that the car tints.
 */
class IconProvider : ContentProvider() {

    companion object {
        const val JUMP = "jump"
        private const val SIZE_PX = 96
        private val ICONS = mapOf(JUMP to R.drawable.ic_jump_line)

        fun uri(context: Context, name: String): String =
            "content://${context.packageName}.icons/$name.png"
    }

    override fun onCreate() = true

    override fun getType(uri: Uri) = "image/png"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val context = context ?: throw FileNotFoundException()
        val name = uri.lastPathSegment?.removeSuffix(".png")
        val resId = ICONS[name] ?: throw FileNotFoundException(uri.toString())
        val file = File(File(context.cacheDir, "aa_icons").apply { mkdirs() }, "$name.png")
        if (!file.exists()) {
            val drawable = ContextCompat.getDrawable(context, resId) ?: throw FileNotFoundException()
            val bitmap = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, SIZE_PX, SIZE_PX)
            drawable.draw(Canvas(bitmap))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
