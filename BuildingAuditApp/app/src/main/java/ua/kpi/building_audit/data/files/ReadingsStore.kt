package ua.kpi.building_audit.data.files

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ua.kpi.building_audit.domain.readings.MeterReading
import ua.kpi.building_audit.domain.readings.ParseResult
import ua.kpi.building_audit.domain.readings.ReadingStatus
import ua.kpi.building_audit.domain.readings.ReadingsCsv
import java.io.File
import java.time.LocalDate

enum class StorageFormat(val extension: String) { CSV("csv"), JSON("json") }

/** Формат JSON-файлу: дата рядком ISO-8601, щоб файл читався без адаптерів і в інших програмах. */
private data class ReadingJson(
    val date: String,
    @SerializedName("heat_gcal") val heatGcal: Double,
    @SerializedName("electricity_kwh") val electricityKwh: Double,
    val status: String,
    val note: String?
)

/**
 * Покази лічильників у внутрішньому сховищі: після кожної зміни пишуться обидва файли,
 * читається той, що обрав користувач. Усі операції з файлами виконуються на [io].
 */
class ReadingsStore(private val dir: File, private val gson: Gson, private val io: CoroutineDispatcher = Dispatchers.IO) {

    fun file(format: StorageFormat) = File(dir, "readings.${format.extension}")

    suspend fun load(format: StorageFormat): ParseResult = withContext(io) {
        val file = file(format)
        if (!file.exists()) return@withContext ParseResult(emptyList(), emptyList())
        decode(file.readText(), format)
    }

    suspend fun save(readings: List<MeterReading>) = withContext(io) {
        dir.mkdirs()
        val sorted = readings.sortedBy { it.date }
        // Запис через тимчасовий файл: обрив посеред запису не зіпсує попередню версію.
        writeAtomically(file(StorageFormat.CSV), ReadingsCsv.write(sorted))
        writeAtomically(file(StorageFormat.JSON), encodeJson(sorted))
    }

    fun encode(readings: List<MeterReading>, format: StorageFormat): String = when (format) {
        StorageFormat.CSV -> ReadingsCsv.write(readings)
        StorageFormat.JSON -> encodeJson(readings)
    }

    fun decode(text: String, format: StorageFormat): ParseResult = when (format) {
        StorageFormat.CSV -> ReadingsCsv.read(text)
        StorageFormat.JSON -> decodeJson(text)
    }

    private fun encodeJson(readings: List<MeterReading>): String =
        gson.toJson(readings.map { ReadingJson(it.date.toString(), it.heatGcal, it.electricityKwh, it.status.name, it.note) })

    private fun decodeJson(text: String): ParseResult {
        val type = object : TypeToken<List<ReadingJson>>() {}.type
        val items: List<ReadingJson> = runCatching { gson.fromJson<List<ReadingJson>>(text, type) }
            .getOrElse { return ParseResult(emptyList(), listOf("Файл не є коректним JSON: ${it.message}")) } ?: emptyList()
        val errors = mutableListOf<String>()
        val readings = items.mapIndexedNotNull { index, item ->
            runCatching {
                MeterReading(
                    LocalDate.parse(item.date), item.heatGcal, item.electricityKwh,
                    ReadingStatus.valueOf(item.status), item.note.orEmpty()
                )
            }.onFailure { errors += "Запис ${index + 1}: ${it.message}" }.getOrNull()
        }
        return ParseResult(readings, errors)
    }

    private fun writeAtomically(target: File, text: String) {
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeText(text)
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
    }
}
