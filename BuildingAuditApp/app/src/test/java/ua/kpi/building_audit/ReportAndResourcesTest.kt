package ua.kpi.building_audit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.kpi.building_audit.domain.attachments.Attachment
import ua.kpi.building_audit.domain.attachments.AttachmentKind
import ua.kpi.building_audit.domain.attachments.AttachmentQuery
import ua.kpi.building_audit.domain.attachments.AttachmentSort
import ua.kpi.building_audit.domain.attachments.fitInside
import ua.kpi.building_audit.domain.attachments.sampleSize
import ua.kpi.building_audit.domain.model.ElementKind
import ua.kpi.building_audit.domain.report.AuditReport
import ua.kpi.building_audit.domain.report.ReportOptions
import ua.kpi.building_audit.domain.resources.LevelScale
import ua.kpi.building_audit.domain.resources.RawCounters
import ua.kpi.building_audit.domain.resources.ResourceLevel
import ua.kpi.building_audit.domain.resources.ResourceMath
import java.time.LocalDate

class AuditReportTest {
    private val report = AuditReport.build(DORMITORY, TARIFFS, ReportOptions("Аудитор", ""), LocalDate.of(2026, 11, 23))

    @Test
    fun envelopeSharesSumToHundredAndSortedByLoss() {
        assertEquals(100.0, report.envelope.sumOf { it.sharePercent }, 1e-6)
        assertEquals(report.envelope.sortedByDescending { it.lossKw }, report.envelope)
        assertEquals(report.assessment.metrics.designHeatLossKw, report.envelope.sumOf { it.lossKw }, 1e-6)
    }

    @Test
    fun wallsDominateDormitoryLosses() {
        assertEquals(ElementKind.WALL, report.envelope.first().kind)
        assertTrue(report.conclusion.contains("зовнішні стіни"))
    }

    @Test
    fun consumptionCoversPreviousTwelveMonths() {
        assertEquals(12, report.consumption.size)
        assertEquals("10.26", report.consumption.last().label)
    }

    @Test
    fun conclusionNamesFastestPaybackMeasure() {
        val best = report.measures.first { it.paybackYears != null }
        assertTrue(report.conclusion.contains(best.measure.title))
        assertTrue(report.conclusion.startsWith("Будівля належить до класу енергоефективності ${report.assessment.energyClass.label}"))
    }
}

class AttachmentsTest {
    private fun item(id: String, kind: AttachmentKind, title: String, at: Long, building: Long = 1) =
        Attachment(id, building, kind, title, "$id.bin", "x", 1, at)

    private val items = listOf(
        item("a", AttachmentKind.PDF, "Паспорт", 3),
        item("b", AttachmentKind.PHOTO, "Фасад", 1),
        item("c", AttachmentKind.PHOTO, "акт", 2),
        item("d", AttachmentKind.DOCX, "Договір", 4, building = 2)
    )

    @Test
    fun kindByMimeOrExtension() {
        assertEquals(AttachmentKind.PHOTO, Attachment.kindOf("image/heic", "x.heic"))
        assertEquals(AttachmentKind.PDF, Attachment.kindOf("application/octet-stream", "Звіт.PDF"))
        assertEquals(AttachmentKind.DOCX, Attachment.kindOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "a"))
        assertEquals(AttachmentKind.OTHER, Attachment.kindOf("text/plain", "a.txt"))
    }

    @Test
    fun queryFiltersByBuildingAndKindAndSorts() {
        assertEquals(listOf("a", "c", "b"), AttachmentQuery(buildingId = 1).apply(items).map { it.id })
        assertEquals(listOf("b", "c"), AttachmentQuery(buildingId = 1, kinds = setOf(AttachmentKind.PHOTO), sort = AttachmentSort.OLDEST).apply(items).map { it.id })
        assertEquals(listOf("c", "a", "b"), AttachmentQuery(buildingId = 1, sort = AttachmentSort.NAME).apply(items).map { it.id })
        assertEquals(listOf("c", "b", "a"), AttachmentQuery(buildingId = 1, sort = AttachmentSort.TYPE).apply(items).map { it.id })
    }

    @Test
    fun scalingKeepsAspectRatio() {
        assertEquals(1600 to 1200, fitInside(4000, 3000, 1600))
        assertEquals(900 to 1600, fitInside(2700, 4800, 1600))
        assertEquals(800 to 600, fitInside(800, 600, 1600))
    }

    @Test
    fun sampleSizeNeverGoesBelowTarget() {
        assertEquals(2, sampleSize(4000, 3000, 1600))
        assertEquals(8, sampleSize(4000, 3000, 320))
        assertEquals(1, sampleSize(1000, 800, 1600))
    }
}

class ResourceMathTest {
    @Test
    fun cpuPercentAcrossCores() {
        val a = RawCounters(wallMs = 0, cpuMs = 0, rxBytes = 0, txBytes = 0)
        val b = RawCounters(wallMs = 1_000, cpuMs = 2_000, rxBytes = 0, txBytes = 0)
        assertEquals(50.0, ResourceMath.cpuPercent(a, b, cores = 4), 1e-9)
        assertEquals(0.0, ResourceMath.cpuPercent(a, a, cores = 4), 1e-9)
    }

    @Test
    fun networkRateIgnoresCounterReset() {
        assertEquals(2.0, ResourceMath.rateKbPerSec(0, 4096, 2_000), 1e-9)
        assertEquals(0.0, ResourceMath.rateKbPerSec(5000, 100, 1_000), 1e-9)
    }

    @Test
    fun levelsForBothDirections() {
        assertEquals(ResourceLevel.NORMAL, LevelScale.CPU.of(10.0))
        assertEquals(ResourceLevel.WARNING, LevelScale.CPU.of(60.0))
        assertEquals(ResourceLevel.CRITICAL, LevelScale.CPU.of(90.0))
        assertEquals(ResourceLevel.NORMAL, LevelScale.BATTERY.of(80.0))
        assertEquals(ResourceLevel.WARNING, LevelScale.BATTERY.of(25.0))
        assertEquals(ResourceLevel.CRITICAL, LevelScale.BATTERY.of(10.0))
    }
}
