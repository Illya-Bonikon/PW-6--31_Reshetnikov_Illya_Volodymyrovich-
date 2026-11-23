package ua.kpi.building_audit.practical26

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ua.kpi.building_audit.domain.resources.LevelScale
import ua.kpi.building_audit.domain.resources.ResourceLevel
import ua.kpi.building_audit.settings.ThemeMode
import ua.kpi.building_audit.ui.InfoRow
import ua.kpi.building_audit.ui.SectionCard
import ua.kpi.building_audit.ui.components.Sparkline
import ua.kpi.building_audit.ui.format

@Composable
fun MonitorScreen(viewModel: MonitorViewModel = viewModel(factory = MonitorViewModel.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val s = state.latest

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Spacer(Modifier.padding(top = 4.dp))
            SectionCard("Тема оформлення") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ThemeMode.entries.forEach { FilterChip(selected = theme == it, onClick = { viewModel.setTheme(it) }, label = { Text(it.title) }) }
                }
            }
        }
        item {
            SectionCard("Зведення") {
                Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.padding(vertical = 4.dp)) {
                    LevelRing("CPU", s?.cpuPercent, LevelScale.CPU, Modifier.weight(1f))
                    LevelRing("Пам'ять", s?.memoryUsedPercent, LevelScale.MEMORY, Modifier.weight(1f))
                    LevelRing("Батарея", s?.batteryPercent?.toDouble(), LevelScale.BATTERY, Modifier.weight(1f))
                    LevelRing("Сховище", s?.storageUsedPercent, LevelScale.STORAGE, Modifier.weight(1f))
                }
            }
        }
        item {
            ResourceCard("Процесор (процес застосунку)", state.history.map { it.cpuPercent }, LevelScale.CPU.of(s?.cpuPercent ?: 0.0)) {
                InfoRow("Завантаження", "${(s?.cpuPercent ?: 0.0).format(1)} %")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = viewModel::toggleLoad) { Text(if (state.loadRunning) "Зупинити" else "Навантажити на 10 с") }
                    state.loadResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        item {
            ResourceCard("Пам'ять", state.history.map { it.memoryUsedPercent }, LevelScale.MEMORY.of(s?.memoryUsedPercent ?: 0.0)) {
                InfoRow("Вільно в системі", "${(s?.memoryAvailableMb ?: 0.0).format(0)} МБ")
                InfoRow("Купа застосунку", "${(s?.appHeapMb ?: 0.0).format(1)} МБ")
            }
        }
        item {
            ResourceCard("Батарея", state.history.map { it.batteryPercent.toDouble() }, LevelScale.BATTERY.of(s?.batteryPercent?.toDouble() ?: 100.0)) {
                InfoRow("Заряд", "${s?.batteryPercent ?: 0} %" + if (s?.charging == true) " · заряджається" else "")
                InfoRow("Температура", "${(s?.batteryTemperatureC ?: 0.0).format(1)} °C")
            }
        }
        item {
            ResourceCard("Мережа застосунку", state.history.map { it.rxKbPerSec + it.txKbPerSec }, ResourceLevel.NORMAL) {
                InfoRow("Отримано", "${(s?.rxKbPerSec ?: 0.0).format(1)} КБ/с")
                InfoRow("Надіслано", "${(s?.txKbPerSec ?: 0.0).format(1)} КБ/с")
                InfoRow("Сховище", "вільно ${(s?.storageFreeGb ?: 0.0).format(1)} з ${(s?.storageTotalGb ?: 0.0).format(1)} ГБ")
            }
        }
    }
}

@Composable
private fun levelColor(level: ResourceLevel): Color {
    val target = when (level) {
        ResourceLevel.NORMAL -> MaterialTheme.colorScheme.primary
        ResourceLevel.WARNING -> Color(0xFFF57C00)
        ResourceLevel.CRITICAL -> MaterialTheme.colorScheme.error
    }
    return animateColorAsState(target, tween(600), label = "level").value
}

/** Кільцевий індикатор: дуга плавно добігає до нового значення і змінює колір за порогами. */
@Composable
private fun LevelRing(label: String, value: Double?, scale: LevelScale, modifier: Modifier = Modifier) {
    val progress by animateFloatAsState(((value ?: 0.0) / 100).toFloat().coerceIn(0f, 1f), tween(800), label = "ring")
    val color = levelColor(scale.of(value ?: if (scale.higherIsWorse) 0.0 else 100.0))
    val track = MaterialTheme.colorScheme.outlineVariant
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(68.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(68.dp)) {
                val stroke = Stroke(7.dp.toPx(), cap = StrokeCap.Round)
                drawArc(track, -90f, 360f, false, style = stroke)
                drawArc(color, -90f, 360f * progress, false, style = stroke)
            }
            Text(value?.let { "${it.format(0)}%" } ?: "—", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
        }
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ResourceCard(title: String, history: List<Double>, level: ResourceLevel, content: @Composable () -> Unit) {
    SectionCard(title) {
        content()
        Sparkline(history, levelColor(level))
    }
}

