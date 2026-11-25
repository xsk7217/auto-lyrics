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

        private const val ART_MAX_PX = 512
        private const val ART_KEEP = 3
        private val ART_NAME = Regex("""^art_\d+\.jpg$""")
        private var artCounter = 0

        /**
         * Saves the current song's cover and returns a content:// URI for it.
         * Android Auto loads artwork from a URI once, instead of receiving the
         * whole bitmap with every metadata update.
         */
        @Synchronized
        fun saveArt(context: Context, source: Bitmap): String? = try {
            // Hardware bitmaps cannot be scaled or compressed: copy them to memory first.
            val art = if (source.config == Bitmap.Config.HARDWARE) source.copy(Bitmap.Config.ARGB_8888, false) else source
            val dir = File(context.cacheDir, "aa_art").apply { mkdirs() }
            val scale = ART_MAX_PX.toFloat() / maxOf(art.width, art.height)
            val small = if (scale < 1f) {
                Bitmap.createScaledBitmap(art, (art.width * scale).toInt().coerceAtLeast(1), (art.height * scale).toInt().coerceAtLeast(1), true)
            } else art
            // Digits only, so the name matches ART_NAME and sorts by time.
            val name = "art_${System.currentTimeMillis()}${artCounter++ % 10}.jpg"
            val tmp = File(dir, "$name.tmp")
            tmp.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            tmp.renameTo(File(dir, name))
            // Keep only the newest few.
            dir.listFiles { f -> ART_NAME.matches(f.name) }?.sortedByDescending { it.name }
                ?.drop(ART_KEEP)?.forEach { it.delete() }
            "content://${context.packageName}.icons/$name"
        } catch (_: Exception) {
            null
        }
    }

    override fun onCreate() = true

    override fun getType(uri: Uri) =
        if (uri.lastPathSegment?.endsWith(".jpg") == true) "image/jpeg" else "image/png"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val context = context ?: throw FileNotFoundException()
        val segment = uri.lastPathSegment ?: throw FileNotFoundException()
        if (ART_NAME.matches(segment)) {
            val art = File(File(context.cacheDir, "aa_art"), segment)
            if (!art.exists()) throw FileNotFoundException(segment)
            return ParcelFileDescriptor.open(art, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        val name = segment.removeSuffix(".png")
        val resId = ICONS[name] ?: throw FileNotFoundException(uri.toString())
        val file = File(File(context.cacheDir, "aa_icons").apply { mkdirs() }, "$name.png")
        if (!file.exists()) {
            val drawable = ContextCompat.getDrawable(context, resId) ?: throw FileNotFoundException()
            val bitmap = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, SIZE_PX, SIZE_PX)
            drawable.draw(Canvas(bitmap))
            // Write to a temporary file first: two readers never see half a PNG.
            val tmp = File(file.parentFile, "$name.${System.nanoTime()}.tmp")
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!tmp.renameTo(file)) tmp.delete()
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
