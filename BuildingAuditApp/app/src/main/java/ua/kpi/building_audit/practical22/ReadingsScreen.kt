package ua.kpi.building_audit.practical22

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.kpi.building_audit.data.files.StorageFormat
import ua.kpi.building_audit.domain.readings.MeterReading
import ua.kpi.building_audit.domain.readings.Period
import ua.kpi.building_audit.domain.readings.ReadingStatus
import ua.kpi.building_audit.ui.Banner
import ua.kpi.building_audit.ui.InfoRow
import ua.kpi.building_audit.ui.SectionCard
import ua.kpi.building_audit.ui.components.BarChart
import ua.kpi.building_audit.ui.format
import ua.kpi.building_audit.ui.theme.ErrorRed
import ua.kpi.building_audit.ui.theme.GreenTertiary
import ua.kpi.building_audit.ui.theme.WarningAmber
import java.time.format.DateTimeFormatter

private val SHORT_DATE = DateTimeFormatter.ofPattern("dd.MM")
private const val LIST_LIMIT = 60

@Composable
fun ReadingsScreen(viewModel: ReadingsViewModel = viewModel(factory = ReadingsViewModel.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val format = state.format

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(format.mime)) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val text = viewModel.exportText(format)
        scope.launch { context.write(uri, text) }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch { viewModel.importText(context.read(uri), format) }
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Spacer(Modifier.padding(top = 4.dp))
            SectionCard("Сховище") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StorageFormat.entries.forEach { f ->
                        FilterChip(selected = f == format, onClick = { viewModel.setFormat(f) }, label = { Text("${f.name} · ${(state.fileSizes[f] ?: 0) / 1024} КБ") })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { exporter.launch("readings.${format.extension}") }) { Text("Експорт ${format.name}") }
                    OutlinedButton(onClick = { importer.launch(arrayOf(format.mime, "text/*", "application/octet-stream")) }) { Text("Імпорт") }
                }
                state.errors.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("Імпортовано")) MaterialTheme.colorScheme.onSurface else ErrorRed) }
            }
        }
        item { PeriodCard(state, viewModel::setPeriod) }
        item { FormCard(state, viewModel) }
        items(state.visible.asReversed().take(LIST_LIMIT), key = { it.date.toEpochDay() }) { reading -> ReadingRow(reading) { viewModel.delete(reading) } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PeriodCard(state: ReadingsUiState, onPeriod: (Period) -> Unit) {
    SectionCard("Тепло за добу, Гкал") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Period.entries.forEach { FilterChip(selected = it == state.period, onClick = { onPeriod(it) }, label = { Text(it.title) }) }
        }
        val visible = state.visible
        BarChart(
            values = visible.map { it.heatGcal },
            labels = visible.map { it.date.format(SHORT_DATE) },
            barColor = GreenTertiary,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            highlight = { index -> if (visible[index].status == ReadingStatus.FAULT) ErrorRed else null }
        )
        InfoRow("Тепло", "${state.summary.heatGcal.format(2)} Гкал")
        InfoRow("Електроенергія", "${state.summary.electricityKwh.format(0)} кВт·год")
        InfoRow("Днів із несправністю", "${state.summary.faults} з ${state.summary.days}")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormCard(state: ReadingsUiState, viewModel: ReadingsViewModel) {
    val form = state.form
    SectionCard("Новий показ") {
        OutlinedTextField(form.date, { v -> viewModel.editForm { it.copy(date = v) } }, label = { Text("Дата, РРРР-ММ-ДД") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                form.heat, { v -> viewModel.editForm { it.copy(heat = v) } }, label = { Text("Тепло, Гкал") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                form.electricity, { v -> viewModel.editForm { it.copy(electricity = v) } }, label = { Text("Ел., кВт·год") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f)
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ReadingStatus.entries.forEach { status ->
                FilterChip(selected = form.status == status, onClick = { viewModel.editForm { it.copy(status = status) } }, label = { Text(status.title) })
            }
        }
        OutlinedTextField(form.note, { v -> viewModel.editForm { it.copy(note = v) } }, label = { Text("Примітка") }, modifier = Modifier.fillMaxWidth())
        state.formError?.let { Banner(it, WarningAmber) }
        Button(onClick = viewModel::submit) { Text("Зберегти") }
    }
}

@Composable
private fun ReadingRow(reading: MeterReading, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(reading.date.toString(), modifier = Modifier.weight(1f))
        Text("${reading.heatGcal.format(3)} Гкал", modifier = Modifier.weight(1f))
        Text("${reading.electricityKwh.format(0)} кВт·год", modifier = Modifier.weight(1f))
        if (reading.status != ReadingStatus.NORMAL) Text(reading.status.title, color = ErrorRed, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onDelete) { Text("✕") }
    }
}

private val StorageFormat.mime: String get() = if (this == StorageFormat.CSV) "text/csv" else "application/json"

private suspend fun Context.write(uri: Uri, text: String) = withContext(Dispatchers.IO) {
    contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) }
}

private suspend fun Context.read(uri: Uri): String = withContext(Dispatchers.IO) {
    contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
}
