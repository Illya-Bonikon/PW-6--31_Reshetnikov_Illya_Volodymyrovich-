package ua.kpi.building_audit.data.files

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import ua.kpi.building_audit.domain.attachments.Attachment
import ua.kpi.building_audit.domain.attachments.AttachmentKind
import ua.kpi.building_audit.domain.attachments.fitInside
import ua.kpi.building_audit.domain.attachments.sampleSize
import java.io.File
import java.util.UUID

/**
 * Фото й документи будівель у внутрішньому сховищі: кожен файл копіюється в attachments/,
 * фото стискаються до 1600 px, для фото й PDF зберігається мініатюра 320 px.
 * Метадані лежать в index.json поруч із файлами.
 */
class AttachmentStore(private val dir: File, private val resolver: ContentResolver, private val gson: Gson) {
    private val index = File(dir, "index.json")
    private val _items = MutableStateFlow<List<Attachment>>(emptyList())
    val items: StateFlow<List<Attachment>> = _items.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        if (!index.exists()) return@withContext
        val type = object : TypeToken<List<Attachment>>() {}.type
        _items.value = runCatching { gson.fromJson<List<Attachment>>(index.readText(), type) }.getOrNull().orEmpty()
    }

    fun file(attachment: Attachment) = File(dir, attachment.fileName)
    fun thumbnail(attachment: Attachment) = attachment.thumbnailName?.let { File(dir, it) }

    suspend fun import(uri: Uri, buildingId: Long): Attachment = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val displayName = displayName(uri) ?: "file"
        val kind = Attachment.kindOf(mime, displayName)
        val id = UUID.randomUUID().toString()
        val extension = if (kind == AttachmentKind.PHOTO) "jpg" else displayName.substringAfterLast('.', "bin")
        val target = File(dir, "$id.$extension")

        if (kind == AttachmentKind.PHOTO) savePhoto(uri, target) else copy(uri, target)
        val thumb = File(dir, "$id.thumb.jpg")
        val hasThumb = when (kind) {
            AttachmentKind.PHOTO -> writeThumbnail(decodeScaled(target, THUMB_SIDE), thumb)
            AttachmentKind.PDF -> writeThumbnail(renderFirstPage(target), thumb)
            else -> false
        }
        val attachment = Attachment(
            id = id,
            buildingId = buildingId,
            kind = kind,
            title = displayName.substringBeforeLast('.'),
            fileName = target.name,
            mime = if (kind == AttachmentKind.PHOTO) "image/jpeg" else mime,
            sizeBytes = target.length(),
            addedAtMs = System.currentTimeMillis(),
            thumbnailName = thumb.name.takeIf { hasThumb }
        )
        update(_items.value + attachment)
        attachment
    }

    /** Фото, зменшене для вставки у звіт. */
    suspend fun photoBitmap(attachment: Attachment, side: Int = 800): Bitmap? = withContext(Dispatchers.IO) {
        decodeScaled(file(attachment), side)
    }

    suspend fun rename(id: String, title: String) = withContext(Dispatchers.IO) {
        update(_items.value.map { if (it.id == id) it.copy(title = title.trim().ifEmpty { it.title }) else it })
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val attachment = _items.value.firstOrNull { it.id == id } ?: return@withContext
        file(attachment).delete()
        thumbnail(attachment)?.delete()
        update(_items.value - attachment)
    }

    private fun update(items: List<Attachment>) {
        index.writeText(gson.toJson(items))
        _items.value = items
    }

    /** Фото з камери важать 3–8 МБ; після стиснення до 1600 px — 200–400 КБ без помітної втрати для звіту. */
    private fun savePhoto(uri: Uri, target: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, PHOTO_SIDE) }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IllegalArgumentException("Не вдалося прочитати зображення")
        val rotation = resolver.openInputStream(uri)?.use { exifRotation(ExifInterface(it)) } ?: 0
        val (w, h) = fitInside(decoded.width, decoded.height, PHOTO_SIDE)
        val matrix = Matrix().apply {
            postScale(w.toFloat() / decoded.width, h.toFloat() / decoded.height)
            postRotate(rotation.toFloat())
        }
        val result = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        target.outputStream().use { result.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    }

    private fun copy(uri: Uri, target: File) {
        val input = resolver.openInputStream(uri) ?: throw IllegalArgumentException("Файл недоступний")
        input.use { source -> target.outputStream().use { source.copyTo(it) } }
    }

    private fun decodeScaled(file: File, side: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, side) })
    }

    private fun renderFirstPage(file: File): Bitmap? = runCatching {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(0).use { page ->
                    val (w, h) = fitInside(page.width, page.height, THUMB_SIDE)
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(Color.WHITE)
                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }
    }.getOrNull()

    private fun writeThumbnail(bitmap: Bitmap?, target: File): Boolean {
        bitmap ?: return false
        val (w, h) = fitInside(bitmap.width, bitmap.height, THUMB_SIDE)
        target.outputStream().use { Bitmap.createScaledBitmap(bitmap, w, h, true).compress(Bitmap.CompressFormat.JPEG, 80, it) }
        return true
    }

    private fun displayName(uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment

    private fun exifRotation(exif: ExifInterface): Int = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }

    companion object {
        private const val PHOTO_SIDE = 1600
        private const val THUMB_SIDE = 320
    }
}
