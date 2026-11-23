package ua.kpi.building_audit.domain.attachments

enum class AttachmentKind(val title: String) { PHOTO("Фото"), PDF("PDF"), DOCX("DOCX"), OTHER("Інше") }

data class Attachment(
    val id: String,
    val buildingId: Long,
    val kind: AttachmentKind,
    val title: String,
    val fileName: String,
    val mime: String,
    val sizeBytes: Long,
    val addedAtMs: Long,
    /** Мініатюра є для фото і першої сторінки PDF. */
    val thumbnailName: String? = null
) {
    companion object {
        fun kindOf(mime: String, name: String): AttachmentKind = when {
            mime.startsWith("image/") -> AttachmentKind.PHOTO
            mime == "application/pdf" || name.endsWith(".pdf", ignoreCase = true) -> AttachmentKind.PDF
            mime.contains("wordprocessingml") || name.endsWith(".docx", ignoreCase = true) -> AttachmentKind.DOCX
            else -> AttachmentKind.OTHER
        }
    }
}

enum class AttachmentSort(val title: String) { NEWEST("Новіші"), OLDEST("Старіші"), TYPE("Тип"), NAME("Назва") }

data class AttachmentQuery(val buildingId: Long? = null, val kinds: Set<AttachmentKind> = emptySet(), val sort: AttachmentSort = AttachmentSort.NEWEST) {
    fun apply(items: List<Attachment>): List<Attachment> {
        val filtered = items.filter { (buildingId == null || it.buildingId == buildingId) && (kinds.isEmpty() || it.kind in kinds) }
        return when (sort) {
            AttachmentSort.NEWEST -> filtered.sortedByDescending { it.addedAtMs }
            AttachmentSort.OLDEST -> filtered.sortedBy { it.addedAtMs }
            AttachmentSort.TYPE -> filtered.sortedWith(compareBy<Attachment> { it.kind.ordinal }.thenByDescending { it.addedAtMs })
            AttachmentSort.NAME -> filtered.sortedBy { it.title.lowercase() }
        }
    }
}

/** Розмір зі збереженням пропорцій, за якого довша сторона не перевищує [maxSide]. */
fun fitInside(width: Int, height: Int, maxSide: Int): Pair<Int, Int> {
    val longest = maxOf(width, height)
    if (longest <= maxSide) return width to height
    val ratio = maxSide.toDouble() / longest
    return (width * ratio).toInt().coerceAtLeast(1) to (height * ratio).toInt().coerceAtLeast(1)
}

/** Найбільша степінь двійки для BitmapFactory.inSampleSize, за якої зображення ще не менше за [target]. */
fun sampleSize(width: Int, height: Int, target: Int): Int {
    var sample = 1
    while (maxOf(width, height) / (sample * 2) >= target) sample *= 2
    return sample
}
