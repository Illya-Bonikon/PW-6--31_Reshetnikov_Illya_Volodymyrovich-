package ua.kpi.building_audit.domain.report

import ua.kpi.building_audit.domain.BuildingAssessment
import ua.kpi.building_audit.domain.EnergyCalculator
import ua.kpi.building_audit.domain.SavingsForecast
import ua.kpi.building_audit.domain.SavingsForecaster
import ua.kpi.building_audit.domain.consumption.ConsumptionHistory
import ua.kpi.building_audit.domain.consumption.MonthlyConsumption
import ua.kpi.building_audit.domain.model.Building
import ua.kpi.building_audit.domain.model.ElementKind
import ua.kpi.building_audit.domain.model.Tariffs
import java.time.LocalDate
import java.time.YearMonth

/** Розділи, які користувач вмикає перед формуванням звіту. */
enum class ReportSection(val title: String) {
    ENVELOPE("Огороджувальні конструкції"),
    CONSUMPTION("Споживання тепла за рік"),
    MEASURES("Заходи термомодернізації"),
    PHOTOS("Фотофіксація"),
    NOTES("Висновки аудитора")
}

data class ReportOptions(
    val auditor: String,
    val notes: String,
    val sections: Set<ReportSection> = ReportSection.entries.toSet()
)

data class EnvelopeRow(val kind: ElementKind, val areaM2: Double, val resistance: Double, val lossKw: Double, val sharePercent: Double)

/** Усі числа звіту, підготовлені без Android: їх перевіряють юніт-тести, а PDF лише розкладає їх на сторінці. */
data class AuditReport(
    val date: LocalDate,
    val options: ReportOptions,
    val assessment: BuildingAssessment,
    val envelope: List<EnvelopeRow>,
    val consumption: List<MonthlyConsumption>,
    val measures: List<SavingsForecast>,
    val conclusion: String
) {
    val building: Building get() = assessment.building

    companion object {
        fun build(building: Building, tariffs: Tariffs, options: ReportOptions, today: LocalDate): AuditReport {
            val assessment = EnergyCalculator.assess(building, tariffs)
            val deltaT = tariffs.climate.designDeltaT
            val losses = building.envelope.groupBy { it.kind }.map { (kind, elements) ->
                val area = elements.sumOf { it.areaM2 }
                val h = elements.sumOf { it.areaM2 / EnergyCalculator.resistance(it) }
                Triple(kind, area, h)
            }
            val totalH = losses.sumOf { it.third }
            val envelope = losses.map { (kind, area, h) ->
                EnvelopeRow(kind, area, area / h, h * deltaT / 1000, h / totalH * 100)
            }.sortedByDescending { it.lossKw }
            val lastMonth = YearMonth.from(today).minusMonths(1)
            val consumption = ConsumptionHistory.generate(building, tariffs, lastMonth.year, lastMonth.monthValue, 12)
            val measures = SavingsForecaster.forecastAll(building, tariffs)
            return AuditReport(today, options, assessment, envelope, consumption, measures, conclusion(assessment, envelope, measures))
        }

        /** Автоматичний висновок: головне джерело втрат і захід із найкоротшою окупністю. */
        fun conclusion(assessment: BuildingAssessment, envelope: List<EnvelopeRow>, measures: List<SavingsForecast>): String {
            val worst = envelope.maxByOrNull { it.lossKw }
            val best = measures.firstOrNull { it.paybackYears != null }
            return buildString {
                append("Будівля належить до класу енергоефективності ${assessment.energyClass.label}. ")
                worst?.let { append("Найбільша частка тепловтрат (${"%.0f".format(it.sharePercent)} %) припадає на «${it.kind.title.lowercase()}». ") }
                if (best != null) {
                    append("Першочерговий захід — «${best.measure.title}»: окупність ${"%.1f".format(best.paybackYears)} р., ")
                    append("клас після впровадження — ${best.classAfter.label}.")
                } else {
                    append("Заходи з позитивною окупністю для цієї будівлі не знайдено.")
                }
            }
        }
    }
}
