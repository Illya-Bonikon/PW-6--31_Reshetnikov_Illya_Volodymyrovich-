package ua.kpi.building_audit.ui.components

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Декодовані мініатюри в пам'яті: прокрутка галереї не декодує той самий файл повторно. */
private object BitmapCache : LruCache<String, Bitmap>(16 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount
}

@Composable
fun rememberBitmap(file: File?): ImageBitmap? {
    val bitmap by produceState<Bitmap?>(null, file?.path, file?.lastModified()) {
        value = file?.let { f ->
            val key = "${f.path}:${f.lastModified()}"
            BitmapCache.get(key) ?: withContext(Dispatchers.IO) { BitmapFactory.decodeFile(f.path) }?.also { BitmapCache.put(key, it) }
        }
    }
    return bitmap?.asImageBitmap()
}

/** Фото з масштабуванням і зсувом двома пальцями. */
@Composable
fun ZoomableImage(file: File, modifier: Modifier = Modifier) {
    val image = rememberBitmap(file) ?: return
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 5f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Image(
        image, contentDescription = null, contentScale = ContentScale.Fit,
        modifier = modifier.fillMaxWidth().transformable(transform).graphicsLayer {
            scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
        }
    )
}

/** Посторінковий перегляд PDF засобами PdfRenderer, без сторонніх бібліотек. */
@Composable
fun PdfPages(file: File, modifier: Modifier = Modifier, widthPx: Int = 1080) {
    var pageIndex by remember(file) { mutableIntStateOf(0) }
    val pageState by produceState<Pair<Bitmap, Int>?>(null, file.path, file.lastModified(), pageIndex) {
        value = withContext(Dispatchers.IO) { renderPage(file, pageIndex, widthPx) }
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        pageState?.let { (bitmap, count) ->
            Image(bitmap.asImageBitmap(), contentDescription = "Сторінка ${pageIndex + 1}", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { pageIndex-- }, enabled = pageIndex > 0) { Text("‹") }
                Text("${pageIndex + 1} / $count")
                OutlinedButton(onClick = { pageIndex++ }, enabled = pageIndex < count - 1) { Text("›") }
            }
        } ?: Text("Не вдалося відкрити PDF")
    }
}

private fun renderPage(file: File, index: Int, widthPx: Int): Pair<Bitmap, Int>? = runCatching {
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            renderer.openPage(index.coerceIn(0, renderer.pageCount - 1)).use { page ->
                val bitmap = Bitmap.createBitmap(widthPx, widthPx * page.height / page.width, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(AndroidColor.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap to renderer.pageCount
            }
        }
    }
}.getOrNull()

/** Відкриває файл у сторонній програмі (DOCX, PDF) або пропонує надіслати його. */
fun Context.openExternally(file: File, mime: String, share: Boolean = false) {
    val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
    val intent = if (share) {
        Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
    } else {
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(intent, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
