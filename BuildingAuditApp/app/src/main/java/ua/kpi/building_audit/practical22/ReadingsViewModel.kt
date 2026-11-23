package ua.kpi.building_audit.practical22

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ua.kpi.building_audit.data.files.ReadingsStore
import ua.kpi.building_audit.data.files.StorageFormat
import ua.kpi.building_audit.domain.readings.MeterReading
import ua.kpi.building_audit.domain.readings.Period
import ua.kpi.building_audit.domain.readings.ReadingStatus
import ua.kpi.building_audit.domain.readings.ReadingsSeed
import ua.kpi.building_audit.domain.readings.ReadingsSummary
import ua.kpi.building_audit.ui.containerFactory
import java.time.LocalDate

data class ReadingForm(
    val date: String = LocalDate.now().toString(),
    val heat: String = "",
    val electricity: String = "",
    val status: ReadingStatus = ReadingStatus.NORMAL,
    val note: String = ""
)

data class ReadingsUiState(
    val format: StorageFormat = StorageFormat.CSV,
    val all: List<MeterReading> = emptyList(),
    val period: Period = Period.QUARTER,
    val form: ReadingForm = ReadingForm(),
    val formError: String? = null,
    val errors: List<String> = emptyList(),
    val fileSizes: Map<StorageFormat, Long> = emptyMap(),
    val today: LocalDate = LocalDate.now()
) {
    val visible: List<MeterReading> get() = period.filter(all, today)
    val summary: ReadingsSummary get() = ReadingsSummary.of(visible)
}

class ReadingsViewModel(private val store: ReadingsStore) : ViewModel() {
    private val _state = MutableStateFlow(ReadingsUiState())
    val state: StateFlow<ReadingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            if (store.load(StorageFormat.JSON).readings.isEmpty()) store.save(ReadingsSeed.generate(LocalDate.now(), days = 400))
            reload()
        }
    }

    fun setFormat(format: StorageFormat) {
        _state.update { it.copy(format = format) }
        viewModelScope.launch { reload() }
    }

    fun setPeriod(period: Period) = _state.update { it.copy(period = period) }

    fun editForm(transform: (ReadingForm) -> ReadingForm) = _state.update { it.copy(form = transform(it.form), formError = null) }

    fun submit() {
        val form = _state.value.form
        val reading = runCatching {
            MeterReading(
                date = LocalDate.parse(form.date.trim()),
                heatGcal = form.heat.replace(',', '.').toDouble().also { require(it >= 0) },
                electricityKwh = form.electricity.replace(',', '.').toDouble().also { require(it >= 0) },
                status = form.status,
                note = form.note.trim()
            )
        }.getOrElse {
            _state.update { it.copy(formError = "Перевірте дату (РРРР-ММ-ДД) і невід'ємні числові покази") }
            return
        }
        // Один показ на добу: новий запис замінює наявний за ту саму дату.
        persist(_state.value.all.filterNot { it.date == reading.date } + reading)
        _state.update { it.copy(form = ReadingForm(date = reading.date.plusDays(1).toString())) }
    }

    fun delete(reading: MeterReading) = persist(_state.value.all - reading)

    fun exportText(format: StorageFormat): String = store.encode(_state.value.visible, format)

    /** Імпорт додає записи до наявних; дата з файлу має пріоритет. */
    fun importText(text: String, format: StorageFormat) {
        val parsed = store.decode(text, format)
        val dates = parsed.readings.map { it.date }.toSet()
        val summary = "Імпортовано ${parsed.readings.size} записів"
        persist(_state.value.all.filterNot { it.date in dates } + parsed.readings, listOf(summary) + parsed.errors)
    }

    private fun persist(readings: List<MeterReading>, messages: List<String> = emptyList()) {
        viewModelScope.launch {
            store.save(readings)
            reload(messages)
        }
    }

    private suspend fun reload(messages: List<String> = emptyList()) {
        val format = _state.value.format
        val result = store.load(format)
        _state.update { state ->
            state.copy(
                all = result.readings,
                errors = messages + result.errors,
                fileSizes = StorageFormat.entries.associateWith { store.file(it).length() }
            )
        }
    }

    companion object {
        val Factory = containerFactory { ReadingsViewModel(it.readingsStore) }
    }
}
