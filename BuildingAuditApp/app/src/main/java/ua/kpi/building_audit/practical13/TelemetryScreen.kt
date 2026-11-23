package ua.kpi.building_audit.practical13

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ua.kpi.building_audit.ui.Banner
import ua.kpi.building_audit.ui.InfoRow
import ua.kpi.building_audit.ui.SectionCard
import ua.kpi.building_audit.ui.components.Sparkline
import ua.kpi.building_audit.ui.format
import ua.kpi.building_audit.ui.theme.BluePrimary
import ua.kpi.building_audit.ui.theme.ErrorRed
import ua.kpi.building_audit.ui.theme.GreenPrimary
import ua.kpi.building_audit.ui.theme.WarningAmber

@Composable
fun TelemetryScreen(viewModel: TelemetryViewModel = viewModel(factory = TelemetryViewModel.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Spacer(Modifier.padding(top = 4.dp))
            if (state.buildings.isEmpty()) {
                Banner("Кеш будівель порожній: спершу завантажте реєстр на екрані «Реєстр будівель».", WarningAmber)
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(state.buildings, key = { it.id }) { building ->
                    FilterChip(selected = state.selected?.id == building.id, onClick = { viewModel.select(building) }, label = { Text(building.name) })
                }
            }
        }
        item { SensorCard("Температура в приміщенні", state.indoor, "°C", GreenPrimary, "відкинуто збоїв: ${state.rejectedGlitches}") }
        item { SensorCard("Зовнішня температура (метеомодель)", state.outdoor, "°C", BluePrimary) }
        item { SensorCard("Теплолічильник", state.power, "кВт", WarningAmber) }
        item {
            SectionCard("Комбінований потік: фактичний стан оболонки") {
                val d = state.diagnosis
                if (d == null) {
                    Text("Очікую дані з трьох джерел...")
                } else {
                    InfoRow("Фактичний H = P / (Tв − Tз)", "${d.measuredHeatTransferWPerK.format()} Вт/К")
                    InfoRow("Проєктний H з паспорта", "${d.designHeatTransferWPerK.format()} Вт/К")
                    InfoRow("Перевищення", "${d.deviationPercent.format(1)} %")
                    if (d.deviationPercent > 15) Banner("Фактичні тепловтрати суттєво вищі за проєктні: варто перевірити оболонку тепловізором.", ErrorRed)
                }
            }
        }
        item {
            SectionCard("Прогноз потужності для заданої температури") {
                OutlinedTextField(
                    value = state.setpoint,
                    onValueChange = viewModel::onSetpointChange,
                    label = { Text("Температура в приміщенні, °C (10..30)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(state.setpointPowerKw?.let { "Потрібна теплова потужність: ${it.format(1)} кВт" } ?: "Введіть значення від 10 до 30")
            }
        }
        item {
            SectionCard("Журнал потоків") {
                state.events.forEach { Text(it, fontSize = 12.sp) }
                OutlinedButton(onClick = viewModel::restart) { Text("Перезапустити потоки") }
            }
        }
    }
}

@Composable
private fun SensorCard(title: String, values: List<Double>, unit: String, color: Color, note: String? = null) {
    SectionCard(title) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(values.lastOrNull()?.let { "${it.format(1)} $unit" } ?: "—", style = MaterialTheme.typography.titleLarge, color = color)
            note?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
        Sparkline(values, color)
    }
}
