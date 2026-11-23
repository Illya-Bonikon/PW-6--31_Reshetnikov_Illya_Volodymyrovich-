package ua.kpi.building_audit.practical21

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ua.kpi.building_audit.data.repository.BuildingRepository
import ua.kpi.building_audit.domain.EnergyCalculator
import ua.kpi.building_audit.domain.EnergyClass
import ua.kpi.building_audit.domain.SavingsForecaster
import ua.kpi.building_audit.domain.model.Building
import ua.kpi.building_audit.domain.model.ElementKind
import ua.kpi.building_audit.domain.model.EnvelopeElement
import ua.kpi.building_audit.domain.model.RetrofitMeasure
import ua.kpi.building_audit.domain.model.Tariffs
import ua.kpi.building_audit.ui.containerFactory

/** Тепловтрати одного виду огороджень, кВт при розрахунковій різниці температур. */
data class ElementLoss(val kind: ElementKind, val beforeKw: Double, val afterKw: Double)

data class RetrofitUiState(
    val buildings: List<Building> = emptyList(),
    val selected: Building? = null,
    val measures: List<RetrofitMeasure> = emptyList(),
    val chosen: Set<String> = emptySet(),
    val losses: List<ElementLoss> = emptyList(),
    val classBefore: EnergyClass? = null,
    val classAfter: EnergyClass? = null,
    val costBeforeUah: Double = 0.0,
    val costAfterUah: Double = 0.0,
    val investmentUah: Double = 0.0
) {
    val savingUah: Double get() = costBeforeUah - costAfterUah
}

/** Віртуальна термомодернізація: користувач вмикає заходи, а екран анімовано показує наслідки. */
class RetrofitViewModel(private val repository: BuildingRepository) : ViewModel() {
    private val selectedId = MutableStateFlow<Long?>(null)
    private val chosen = MutableStateFlow<Set<String>>(emptySet())
    private val tariffs = MutableStateFlow(Tariffs.FALLBACK)

    val state: StateFlow<RetrofitUiState> = combine(repository.observeCached(), selectedId, chosen, tariffs) { buildings, id, ids, tariff ->
        val building = buildings.firstOrNull { it.id == id } ?: buildings.firstOrNull()
            ?: return@combine RetrofitUiState(measures = tariff.measures)
        val measures = tariff.measures.filter { measure -> building.envelope.any { it.kind == measure.target } }
        val active = measures.filter { it.id in ids }
        val improved = SavingsForecaster.applyAll(building.envelope, active)
        val before = EnergyCalculator.metrics(building.envelope, tariff.climate)
        val after = EnergyCalculator.metrics(improved, tariff.climate)
        val deltaT = tariff.climate.designDeltaT
        RetrofitUiState(
            buildings = buildings,
            selected = building,
            measures = measures,
            chosen = ids,
            losses = ElementKind.entries.mapNotNull { kind ->
                val was = building.envelope.filter { it.kind == kind }
                if (was.isEmpty()) return@mapNotNull null
                val now = improved.filter { it.kind == kind }
                ElementLoss(kind, lossKw(was, deltaT), lossKw(now, deltaT))
            },
            classBefore = EnergyClass.of(before.specificHeatLossWPerM2),
            classAfter = EnergyClass.of(after.specificHeatLossWPerM2),
            costBeforeUah = before.annualHeatGcal * tariff.heatPriceUahPerGcal,
            costAfterUah = after.annualHeatGcal * tariff.heatPriceUahPerGcal,
            investmentUah = active.sumOf { measure -> building.envelope.filter { it.kind == measure.target }.sumOf { it.areaM2 } * measure.priceUahPerM2 }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RetrofitUiState())

    init {
        viewModelScope.launch { tariffs.value = repository.tariffs().value }
    }

    fun select(building: Building) {
        selectedId.value = building.id
        chosen.value = emptySet()
    }

    fun toggle(measure: RetrofitMeasure) = chosen.update { if (measure.id in it) it - measure.id else it + measure.id }

    private fun lossKw(elements: List<EnvelopeElement>, deltaT: Double): Double =
        elements.sumOf { it.areaM2 / EnergyCalculator.resistance(it) } * deltaT / 1000

    companion object {
        val Factory = containerFactory { RetrofitViewModel(it.buildingRepository) }
    }
}
