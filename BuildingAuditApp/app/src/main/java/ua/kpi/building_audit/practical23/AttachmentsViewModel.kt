package ua.kpi.building_audit.practical23

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ua.kpi.building_audit.data.files.AttachmentStore
import ua.kpi.building_audit.data.repository.BuildingRepository
import ua.kpi.building_audit.domain.attachments.Attachment
import ua.kpi.building_audit.domain.attachments.AttachmentKind
import ua.kpi.building_audit.domain.attachments.AttachmentQuery
import ua.kpi.building_audit.domain.model.Building
import ua.kpi.building_audit.ui.containerFactory

data class AttachmentsUiState(
    val buildings: List<Building> = emptyList(),
    val selected: Building? = null,
    val query: AttachmentQuery = AttachmentQuery(),
    val items: List<Attachment> = emptyList(),
    val message: String? = null
)

class AttachmentsViewModel(repository: BuildingRepository, val store: AttachmentStore) : ViewModel() {
    private val query = MutableStateFlow(AttachmentQuery())
    private val message = MutableStateFlow<String?>(null)

    val state: StateFlow<AttachmentsUiState> = combine(repository.observeCached(), store.items, query, message) { buildings, items, q, msg ->
        val building = buildings.firstOrNull { it.id == q.buildingId } ?: buildings.firstOrNull()
        val effective = q.copy(buildingId = building?.id)
        AttachmentsUiState(buildings, building, effective, effective.apply(items), msg)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AttachmentsUiState())

    init {
        viewModelScope.launch { store.load() }
    }

    fun select(building: Building) = query.update { it.copy(buildingId = building.id) }

    fun toggleKind(kind: AttachmentKind) = query.update { it.copy(kinds = if (kind in it.kinds) it.kinds - kind else it.kinds + kind) }

    fun updateQuery(transform: (AttachmentQuery) -> AttachmentQuery) = query.update(transform)

    fun import(uri: Uri) {
        val buildingId = state.value.selected?.id ?: return
        viewModelScope.launch {
            message.value = runCatching { store.import(uri, buildingId) }
                .fold({ "Додано: ${it.title} (${it.sizeBytes / 1024} КБ)" }, { "Не вдалося додати файл: ${it.message}" })
        }
    }

    fun rename(attachment: Attachment, title: String) = viewModelScope.launch { store.rename(attachment.id, title) }

    fun delete(attachment: Attachment) = viewModelScope.launch { store.delete(attachment.id) }

    companion object {
        val Factory = containerFactory { AttachmentsViewModel(it.buildingRepository, it.attachmentStore) }
    }
}
