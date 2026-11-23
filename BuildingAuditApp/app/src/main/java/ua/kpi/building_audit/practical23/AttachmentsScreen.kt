package ua.kpi.building_audit.practical23

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ua.kpi.building_audit.domain.attachments.Attachment
import ua.kpi.building_audit.domain.attachments.AttachmentKind
import ua.kpi.building_audit.domain.attachments.AttachmentSort
import ua.kpi.building_audit.ui.components.PdfPages
import ua.kpi.building_audit.ui.components.ZoomableImage
import ua.kpi.building_audit.ui.components.openExternally
import ua.kpi.building_audit.ui.components.rememberBitmap
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DOCUMENT_TYPES = arrayOf("application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AttachmentsScreen(viewModel: AttachmentsViewModel = viewModel(factory = AttachmentsViewModel.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var opened by remember { mutableStateOf<Attachment?>(null) }
    var renaming by remember { mutableStateOf<Attachment?>(null) }
    val cameraUri = remember { context.cameraUri() }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(viewModel::import) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved -> if (saved) viewModel.import(cameraUri) }
    val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::import) }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.buildings.forEach { FilterChip(selected = it.id == state.selected?.id, onClick = { viewModel.select(it) }, label = { Text(it.name) }) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = state.selected != null) { Text("Галерея") }
                    Button(onClick = { camera.launch(cameraUri) }, enabled = state.selected != null) { Text("Камера") }
                    Button(onClick = { documents.launch(DOCUMENT_TYPES) }, enabled = state.selected != null) { Text("Документ") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AttachmentKind.entries.forEach { kind ->
                        FilterChip(selected = kind in state.query.kinds, onClick = { viewModel.toggleKind(kind) }, label = { Text(kind.title) })
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AttachmentSort.entries.forEach { sort ->
                        FilterChip(selected = state.query.sort == sort, onClick = { viewModel.updateQuery { it.copy(sort = sort) } }, label = { Text(sort.title) })
                    }
                }
                state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (state.items.isEmpty()) Text("Для цієї будівлі ще немає файлів")
            }
        }
        items(state.items, key = { it.id }) { attachment ->
            AttachmentTile(
                attachment = attachment,
                thumbnail = viewModel.store.thumbnail(attachment),
                onOpen = {
                    if (attachment.kind == AttachmentKind.PHOTO || attachment.kind == AttachmentKind.PDF) opened = attachment
                    else context.openExternally(viewModel.store.file(attachment), attachment.mime)
                },
                onRename = { renaming = attachment }
            )
        }
    }

    opened?.let { attachment ->
        ViewerDialog(attachment, viewModel.store.file(attachment), onDelete = { viewModel.delete(attachment); opened = null }) { opened = null }
    }
    renaming?.let { attachment ->
        RenameDialog(attachment.title, onConfirm = { viewModel.rename(attachment, it); renaming = null }) { renaming = null }
    }
}

@Composable
private fun AttachmentTile(attachment: Attachment, thumbnail: File?, onOpen: () -> Unit, onRename: () -> Unit) {
    val image = rememberBitmap(thumbnail)
    Card(Modifier.clickable(onClick = onOpen)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.3f).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            if (image != null) Image(image, contentDescription = attachment.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Text(attachment.kind.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                attachment.kind.title,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.primary).padding(horizontal = 6.dp, vertical = 2.dp),
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
        Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(attachment.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text("${DATE.format(Date(attachment.addedAtMs))} · ${attachment.sizeBytes / 1024} КБ", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onRename) { Text("✎") }
        }
    }
}

@Composable
private fun ViewerDialog(attachment: Attachment, file: File, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(attachment.title) },
        text = {
            if (attachment.kind == AttachmentKind.PHOTO) ZoomableImage(file) else PdfPages(file)
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрити") } },
        dismissButton = {
            Row {
                TextButton(onClick = { context.openExternally(file, attachment.mime, share = true) }) { Text("Надіслати") }
                TextButton(onClick = onDelete) { Text("Видалити") }
            }
        }
    )
}

@Composable
private fun RenameDialog(current: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Назва файлу") },
        text = { OutlinedTextField(title, { title = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(title) }) { Text("Зберегти") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Скасувати") } }
    )
}

private val DATE = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("uk", "UA"))

/** Камера записує знімок у тимчасовий файл, звідки він стискається в сховище вкладень. */
private fun Context.cameraUri(): Uri {
    val file = File(cacheDir, "camera/capture.jpg").apply { parentFile?.mkdirs() }
    return FileProvider.getUriForFile(this, "$packageName.files", file)
}
