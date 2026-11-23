package ua.kpi.building_audit.domain.readings

import java.time.LocalDate

enum class ReadingStatus(val title: String) { NORMAL("Норма"), ESTIMATED("Розрахунковий"), FAULT("Несправність") }

/** Добові покази лічильників будівлі. */
data class MeterReading(
    val date: LocalDate,
    val heatGcal: Double,
    val electricityKwh: Double,
    val status: ReadingStatus = ReadingStatus.NORMAL,
    val note: String = ""
)

data class ParseResult(val readings: List<MeterReading>, val errors: List<String>)

/**
 * CSV у форматі, який відкриває Excel: роздільник «;», десяткова крапка, поля з «;», лапками
 * чи переносом рядка беруться в лапки. Рядки з помилками не зупиняють розбір, а потрапляють у список помилок.
 */
object ReadingsCsv {
    val HEADER = listOf("date", "heat_gcal", "electricity_kwh", "status", "note")
    private const val SEPARATOR = ';'

    fun write(readings: List<MeterReading>): String = buildString {
        appendLine(HEADER.joinToString(SEPARATOR.toString()))
        readings.forEach { r ->
            appendLine(listOf(r.date.toString(), r.heatGcal.toString(), r.electricityKwh.toString(), r.status.name, quote(r.note)).joinToString(SEPARATOR.toString()))
        }
    }

    fun read(text: String): ParseResult {
        val rows = split(text).filter { row -> row.any { it.isNotBlank() } }
        if (rows.isEmpty()) return ParseResult(emptyList(), emptyList())
        val hasHeader = rows.first().firstOrNull()?.trim() == HEADER.first()
        val body = if (hasHeader) rows.drop(1) else rows
        val firstLine = if (hasHeader) 2 else 1
        val readings = mutableListOf<MeterReading>()
        val errors = mutableListOf<String>()
        body.forEachIndexed { index, fields ->
            runCatching { parse(fields) }
                .onSuccess(readings::add)
                .onFailure { errors += "Рядок ${index + firstLine}: ${it.message}" }
        }
        return ParseResult(readings, errors)
    }

    private fun parse(fields: List<String>): MeterReading {
        require(fields.size >= 3) { "очікувалось щонайменше 3 поля, отримано ${fields.size}" }
        val date = runCatching { LocalDate.parse(fields[0].trim()) }.getOrElse { throw IllegalArgumentException("дата «${fields[0]}» не у форматі РРРР-ММ-ДД") }
        val heat = fields[1].trim().replace(',', '.').toDoubleOrNull() ?: throw IllegalArgumentException("тепло «${fields[1]}» не є числом")
        val electricity = fields[2].trim().replace(',', '.').toDoubleOrNull() ?: throw IllegalArgumentException("електроенергія «${fields[2]}» не є числом")
        require(heat >= 0 && electricity >= 0) { "покази не можуть бути від'ємними" }
        val status = fields.getOrNull(3)?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { name -> ReadingStatus.entries.firstOrNull { it.name == name } ?: throw IllegalArgumentException("невідомий статус «$name»") }
            ?: ReadingStatus.NORMAL
        return MeterReading(date, heat, electricity, status, fields.getOrNull(4).orEmpty())
    }

    private fun quote(value: String): String =
        if (value.any { it == SEPARATOR || it == '"' || it == '\n' }) "\"" + value.replace("\"", "\"\"") + "\"" else value

    /** Розбір за RFC 4180: лапки екрануються подвоєнням, усередині лапок дозволено перенос рядка. */
    private fun split(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == SEPARATOR -> { row += field.toString(); field.clear() }
                !quoted && c == '\n' -> { row += field.toString().trimEnd('\r'); field.clear(); rows += row; row = mutableListOf() }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) rows += (row + field.toString())
        return rows
    }
}

enum class Period(val title: String, val days: Long?) {
    MONTH("30 днів", 30), QUARTER("90 днів", 90), YEAR("Рік", 365), ALL("Усе", null);

    fun filter(readings: List<MeterReading>, today: LocalDate): List<MeterReading> {
        val from = days?.let { today.minusDays(it - 1) }
        return readings.filter { from == null || !it.date.isBefore(from) }.sortedBy { it.date }
    }
}

data class ReadingsSummary(val heatGcal: Double, val electricityKwh: Double, val faults: Int, val days: Int) {
    companion object {
        fun of(readings: List<MeterReading>) = ReadingsSummary(
            heatGcal = readings.sumOf { it.heatGcal },
            electricityKwh = readings.sumOf { it.electricityKwh },
            faults = readings.count { it.status == ReadingStatus.FAULT },
            days = readings.size
        )
    }
}

/** Демонстраційні покази: тепло залежить від сезону, електроенергія — від дня тижня, ~2 % днів із несправністю. */
object ReadingsSeed {
    fun generate(today: LocalDate, days: Int, random: kotlin.random.Random = kotlin.random.Random(14)): List<MeterReading> =
        (days - 1 downTo 0).map { offset ->
            val date = today.minusDays(offset.toLong())
            val season = kotlin.math.cos(2 * Math.PI * (date.dayOfYear - 15) / 365.0)
            val heat = (season * 1.6 + 0.4).coerceAtLeast(0.0) * (1 + random.nextDouble(-0.1, 0.1))
            val weekend = date.dayOfWeek.value >= 6
            val electricity = (if (weekend) 310.0 else 460.0) * (1 + random.nextDouble(-0.08, 0.08))
            val fault = random.nextDouble() < 0.02
            MeterReading(
                date = date,
                heatGcal = if (fault) 0.0 else Math.round(heat * 1000) / 1000.0,
                electricityKwh = Math.round(electricity * 10) / 10.0,
                status = if (fault) ReadingStatus.FAULT else ReadingStatus.NORMAL,
                note = if (fault) "Теплолічильник не передав дані" else ""
            )
        }
}
