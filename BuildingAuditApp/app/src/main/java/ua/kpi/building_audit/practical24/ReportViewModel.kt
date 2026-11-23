package ua.kpi.building_audit.practical24

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.kpi.building_audit.data.files.AttachmentStore
import ua.kpi.building_audit.data.repository.BuildingRepository
import ua.kpi.building_audit.domain.attachments.AttachmentKind
import ua.kpi.building_audit.domain.model.Building
import ua.kpi.building_audit.domain.report.AuditReport
import ua.kpi.building_audit.domain.report.ReportOptions
import ua.kpi.building_audit.domain.report.ReportSection
import ua.kpi.building_audit.report.AuditReportPdf
import ua.kpi.building_audit.ui.containerFactory
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class ReportUiState(
    val buildings: List<Building> = emptyList(),
    val selected: Building? = null,
    val auditor: String = "Решетніков І. В.",
    val notes: String = "",
    val sections: Set<ReportSection> = ReportSection.entries.toSet(),
    val busy: Boolean = false,
    val reports: List<File> = emptyList(),
    val preview: File? = null,
    val error: String? = null
)

class ReportViewModel(
    private val repository: BuildingRepository,
    private val attachments: AttachmentStore,
    private val reportsDir: File
) : ViewModel() {
    private val _state = MutableStateFlow(ReportUiState())
    val state: StateFlow<ReportUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeCached().collect { list ->
                _state.update { it.copy(buildings = list, selected = it.selected?.let { s -> list.firstOrNull { b -> b.id == s.id } } ?: list.firstOrNull()) }
            }
        }
        viewModelScope.launch {
            attachments.load()
            refreshList()
        }
    }

    fun select(building: Building) = _state.update { it.copy(selected = building) }
    fun setAuditor(value: String) = _state.update { it.copy(auditor = value) }
    fun setNotes(value: String) = _state.update { it.copy(notes = value) }
    fun toggle(section: ReportSection) = _state.update { it.copy(sections = if (section in it.sections) it.sections - section else it.sections + section) }
    fun preview(file: File) = _state.update { it.copy(preview = file) }

    fun generate() {
        val current = _state.value
        val building = current.selected ?: return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            runCatching {
                val tariffs = repository.tariffs().value
                val report = AuditReport.build(building, tariffs, ReportOptions(current.auditor, current.notes, current.sections), LocalDate.now())
                val photos = if (ReportSection.PHOTOS in current.sections) {
                    attachments.items.value.filter { it.buildingId == building.id && it.kind == AttachmentKind.PHOTO }
                        .take(MAX_PHOTOS).mapNotNull { attachments.photoBitmap(it) }
                } else emptyList()
                withContext(Dispatchers.IO) { AuditReportPdf(photos).write(report, File(reportsDir, fileName(building))) }
            }.onSuccess { file ->
                refreshList()
                _state.update { it.copy(busy = false, preview = file) }
            }.onFailure { error ->
                _state.update { it.copy(busy = false, error = "Не вдалося сформувати звіт: ${error.message}") }
            }
        }
    }

    fun delete(file: File) {
        file.delete()
        _state.update { it.copy(preview = it.preview?.takeIf { p -> p != file }) }
        viewModelScope.launch { refreshList() }
    }

    private suspend fun refreshList() {
        val files = withContext(Dispatchers.IO) { reportsDir.listFiles().orEmpty().filter { it.extension == "pdf" }.sortedByDescending { it.lastModified() } }
        _state.update { it.copy(reports = files) }
    }

    private fun fileName(building: Building): String {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val safe = building.name.replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_')
        return "audit_${safe}_$stamp.pdf"
    }

    companion object {
        private const val MAX_PHOTOS = 4
        val Factory = containerFactory { ReportViewModel(it.buildingRepository, it.attachmentStore, it.reportsDir) }
    }
}
