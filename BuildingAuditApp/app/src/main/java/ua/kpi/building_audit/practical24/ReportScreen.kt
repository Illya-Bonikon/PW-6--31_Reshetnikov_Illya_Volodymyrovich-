package ua.kpi.building_audit.practical24

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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ua.kpi.building_audit.domain.report.ReportSection
import ua.kpi.building_audit.ui.Banner
import ua.kpi.building_audit.ui.SectionCard
import ua.kpi.building_audit.ui.components.PdfPages
import ua.kpi.building_audit.ui.components.openExternally
import ua.kpi.building_audit.ui.theme.ErrorRed

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReportScreen(viewModel: ReportViewModel = viewModel(factory = ReportViewModel.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Spacer(Modifier.padding(top = 4.dp))
            SectionCard("Параметри звіту") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.buildings.forEach { FilterChip(selected = it.id == state.selected?.id, onClick = { viewModel.select(it) }, label = { Text(it.name) }) }
                }
                OutlinedTextField(state.auditor, viewModel::setAuditor, label = { Text("Аудитор") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(state.notes, viewModel::setNotes, label = { Text("Висновки аудитора") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                ReportSection.entries.forEach { section ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = section in state.sections, onCheckedChange = { viewModel.toggle(section) })
                        Text(section.title)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = viewModel::generate, enabled = state.selected != null && !state.busy) { Text("Сформувати PDF") }
                    if (state.busy) CircularProgressIndicator(Modifier.padding(4.dp))
                }
                state.error?.let { Banner(it, ErrorRed) }
            }
        }
        state.preview?.let { file ->
            item {
                SectionCard(file.name) {
                    PdfPages(file)
                    Row {
                        TextButton(onClick = { context.openExternally(file, "application/pdf") }) { Text("Відкрити") }
                        TextButton(onClick = { context.openExternally(file, "application/pdf", share = true) }) { Text("Надіслати") }
                    }
                }
            }
        }
        item { Text("Збережені звіти", style = MaterialTheme.typography.titleMedium) }
        items(state.reports, key = { it.path }) { file ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${file.name} · ${file.length() / 1024} КБ", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { viewModel.preview(file) }) { Text("Переглянути") }
                TextButton(onClick = { viewModel.delete(file) }) { Text("✕") }
            }
        }
    }
}
