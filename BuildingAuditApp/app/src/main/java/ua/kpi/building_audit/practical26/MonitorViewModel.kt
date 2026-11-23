package ua.kpi.building_audit.practical26

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ua.kpi.building_audit.domain.EnergyCalculator
import ua.kpi.building_audit.domain.model.ElementKind
import ua.kpi.building_audit.domain.model.EnvelopeElement
import ua.kpi.building_audit.domain.model.Layer
import ua.kpi.building_audit.domain.model.Tariffs
import ua.kpi.building_audit.domain.resources.ResourceSnapshot
import ua.kpi.building_audit.monitor.ResourceMonitor
import ua.kpi.building_audit.settings.ThemeMode
import ua.kpi.building_audit.settings.ThemeSettings
import ua.kpi.building_audit.ui.containerFactory
import kotlin.random.Random

data class MonitorUiState(
    val latest: ResourceSnapshot? = null,
    val history: List<ResourceSnapshot> = emptyList(),
    val loadRunning: Boolean = false,
    val loadResult: String? = null
)

class MonitorViewModel(monitor: ResourceMonitor, private val themes: ThemeSettings) : ViewModel() {
    private val _state = MutableStateFlow(MonitorUiState())
    val state: StateFlow<MonitorUiState> = _state.asStateFlow()
    val theme: StateFlow<ThemeMode> = themes.mode
    private var load: Job? = null

    init {
        monitor.snapshots()
            .onEach { snapshot -> _state.update { it.copy(latest = snapshot, history = (it.history + snapshot).takeLast(HISTORY)) } }
            .launchIn(viewModelScope)
    }

    fun setTheme(mode: ThemeMode) = themes.set(mode)

    /**
     * Перевірка монітора під навантаженням: на кожному ядрі паралельно рахуються тепловтрати
     * випадкових будівель, доки користувач не зупинить або не мине 10 с.
     */
    fun toggleLoad() {
        load?.let {
            it.cancel()
            load = null
            _state.update { s -> s.copy(loadRunning = false) }
            return
        }
        _state.update { it.copy(loadRunning = true, loadResult = null) }
        load = viewModelScope.launch {
            val deadline = System.currentTimeMillis() + 10_000
            val cores = Runtime.getRuntime().availableProcessors()
            val counts = (1..cores).map { worker ->
                async(Dispatchers.Default) {
                    val random = Random(worker)
                    var count = 0L
                    while (isActive && System.currentTimeMillis() < deadline) {
                        EnergyCalculator.metrics(randomEnvelope(random), Tariffs.FALLBACK.climate)
                        count++
                    }
                    count
                }
            }.awaitAll()
            _state.update { it.copy(loadRunning = false, loadResult = "Розраховано ${counts.sum()} будівель на $cores ядрах") }
            load = null
        }
    }

    private fun randomEnvelope(random: Random) = listOf(
        EnvelopeElement(ElementKind.WALL, random.nextDouble(200.0, 3000.0), listOf(Layer("Цегла", random.nextDouble(250.0, 640.0), 0.81))),
        EnvelopeElement(ElementKind.WINDOW, random.nextDouble(50.0, 600.0), uValue = random.nextDouble(1.0, 3.0))
    )

    companion object {
        private const val HISTORY = 60
        val Factory = containerFactory { MonitorViewModel(it.resourceMonitor, it.themeSettings) }
    }
}
