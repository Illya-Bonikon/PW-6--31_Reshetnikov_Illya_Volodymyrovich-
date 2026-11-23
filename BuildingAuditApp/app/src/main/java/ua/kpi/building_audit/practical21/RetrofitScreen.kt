package ua.kpi.building_audit.practical21

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ua.kpi.building_audit.domain.EnergyClass
import ua.kpi.building_audit.domain.sensors.SensorKind
import ua.kpi.building_audit.practical20.InstrumentsViewModel
import ua.kpi.building_audit.ui.components.SensorVectorsCard
import ua.kpi.building_audit.ui.EnergyClassBadge
import ua.kpi.building_audit.ui.InfoRow
import ua.kpi.building_audit.ui.SectionCard
import ua.kpi.building_audit.ui.format
import ua.kpi.building_audit.ui.theme.ErrorRed
import ua.kpi.building_audit.ui.theme.GreenPrimary

@Composable
fun RetrofitScreen(
    viewModel: RetrofitViewModel = viewModel(factory = RetrofitViewModel.Factory),
    sensors: InstrumentsViewModel = viewModel(factory = InstrumentsViewModel.Factory)
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sensorState by sensors.state.collectAsStateWithLifecycle()

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Spacer(Modifier.padding(top = 4.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(state.buildings, key = { it.id }) { building ->
                    FilterChip(selected = state.selected?.id == building.id, onClick = { viewModel.select(building) }, label = { Text(building.name) })
                }
            }
        }
        item {
            SectionCard("Енергетичний клас") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    state.classBefore?.let { EnergyClassBadge(it) }
                    Text("→")
                    AnimatedClass(state.classAfter)
                    Spacer(Modifier.weight(1f))
                    AnimatedMoney(state.costAfterUah)
                }
            }
        }
        item {
            SectionCard("Заходи термомодернізації") {
                if (state.measures.isEmpty()) Text("Тарифи ще завантажуються або сервер не повернув заходів")
                state.measures.forEach { measure ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = measure.id in state.chosen, onCheckedChange = { viewModel.toggle(measure) })
                        Text("${measure.title} — ${measure.target.title.lowercase()}")
                    }
                }
            }
        }
        item {
            SectionCard("Тепловтрати за видами огороджень, кВт") {
                val maxLoss = state.losses.maxOfOrNull { it.beforeKw } ?: 1.0
                val worst = state.losses.maxByOrNull { it.afterKw }?.kind
                state.losses.forEach { loss -> LossBar(loss, maxLoss, highlighted = loss.kind == worst) }
            }
        }
        item {
            AnimatedVisibility(
                visible = state.chosen.isNotEmpty(),
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                SectionCard("Ефект модернізації") {
                    InfoRow("Економія за рік", "${state.savingUah.format(0)} грн")
                    InfoRow("Інвестиції", "${state.investmentUah.format(0)} грн")
                    if (state.savingUah > 0) InfoRow("Окупність", "${(state.investmentUah / state.savingUah).format(1)} р.")
                }
            }
        }
        item {
            SensorVectorsCard(
                accel = sensorState.values[SensorKind.ACCELEROMETER]?.values,
                gyro = sensorState.values[SensorKind.GYROSCOPE]?.values,
                gridColor = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}

/** Нова літера класу «виїжджає» знизу, коли клас покращується, і зверху — коли погіршується. */
@Composable
private fun AnimatedClass(energyClass: EnergyClass?) {
    AnimatedContent(
        targetState = energyClass,
        transitionSpec = {
            val better = (targetState?.ordinal ?: 0) < (initialState?.ordinal ?: 0)
            val direction = if (better) 1 else -1
            slideInVertically { it * direction } + fadeIn() togetherWith slideOutVertically { -it * direction } + fadeOut()
        },
        label = "class"
    ) { target -> target?.let { EnergyClassBadge(it) } }
}

/** Сума «докручується» до нового значення, як лічильник. */
@Composable
private fun AnimatedMoney(value: Double) {
    val animated by animateFloatAsState(value.toFloat(), tween(durationMillis = 900), label = "cost")
    Column(horizontalAlignment = Alignment.End) {
        Text("${animated.toDouble().format(0)} грн", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("на опалення за рік", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LossBar(loss: ElementLoss, maxLoss: Double, highlighted: Boolean) {
    val fraction by animateFloatAsState((loss.afterKw / maxLoss).toFloat(), spring(dampingRatio = 0.6f), label = "bar")
    val improvement = 1 - loss.afterKw / loss.beforeKw
    val color by animateColorAsState(if (improvement > 0.01) GreenPrimary else ErrorRed, label = "barColor")
    // Найбільше джерело втрат «пульсує», щоб привернути увагу.
    val pulse = rememberInfiniteTransition(label = "pulse")
    val alpha by pulse.animateFloat(1f, if (highlighted) 0.45f else 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "alpha")

    Column {
        Row {
            Text(loss.kind.title, modifier = Modifier.weight(1f))
            Text("${loss.afterKw.format(1)} кВт" + if (improvement > 0.01) "  (−${(improvement * 100).format(0)} %)" else "")
        }
        Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(MaterialTheme.colorScheme.outlineVariant)) {
            Box(
                Modifier.fillMaxHeight().fillMaxWidth(fraction).graphicsLayer { this.alpha = alpha }
                    .background(color, RoundedCornerShape(7.dp))
            )
        }
    }
}
