package ua.kpi.building_audit.report

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import ua.kpi.building_audit.domain.report.AuditReport
import ua.kpi.building_audit.domain.report.ReportSection
import java.io.File
import java.util.Locale

/**
 * Розкладає AuditReport на сторінки A4 (595×842 pt). Курсор [y] рухається вниз; якщо наступний
 * блок не вміщується, починається нова сторінка з колонтитулом. Так звіт довільної довжини
 * розбивається на сторінки без ручного підрахунку.
 */
class AuditReportPdf(private val photos: List<Bitmap>) {
    private val document = PdfDocument()
    private lateinit var page: PdfDocument.Page
    private lateinit var canvas: Canvas
    private var pageNumber = 0
    private var y = 0f
    private lateinit var title: String

    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f; color = Color.rgb(33, 33, 33) }
    private val bold = TextPaint(text).apply { typeface = Typeface.DEFAULT_BOLD }
    private val heading = TextPaint(bold).apply { textSize = 14f; color = PRIMARY }
    private val muted = TextPaint(text).apply { textSize = 8f; color = Color.GRAY }
    private val fill = Paint().apply { style = Paint.Style.FILL }
    private val line = Paint().apply { color = Color.rgb(200, 200, 200); strokeWidth = 0.5f }

    fun write(report: AuditReport, target: File): File {
        title = "Звіт енергоаудиту: ${report.building.name}"
        newPage()
        titleBlock(report)
        summary(report)
        val sections = report.options.sections
        if (ReportSection.ENVELOPE in sections) envelope(report)
        if (ReportSection.CONSUMPTION in sections) consumption(report)
        if (ReportSection.MEASURES in sections) measures(report)
        if (ReportSection.PHOTOS in sections && photos.isNotEmpty()) photos()
        if (ReportSection.NOTES in sections && report.options.notes.isNotBlank()) {
            section("Висновки аудитора")
            paragraph(report.options.notes)
        }
        section("Загальний висновок")
        paragraph(report.conclusion)
        document.finishPage(page)
        target.parentFile?.mkdirs()
        target.outputStream().use(document::writeTo)
        document.close()
        return target
    }

    private fun newPage() {
        if (pageNumber > 0) document.finishPage(page)
        pageNumber++
        page = document.startPage(PdfDocument.PageInfo.Builder(WIDTH, HEIGHT, pageNumber).create())
        canvas = page.canvas
        canvas.drawText(title, MARGIN, 28f, muted)
        canvas.drawText("стор. $pageNumber", WIDTH - MARGIN - muted.measureText("стор. $pageNumber"), HEIGHT - 20f, muted)
        canvas.drawLine(MARGIN, 34f, WIDTH - MARGIN, 34f, line)
        y = 52f
    }

    private fun ensureSpace(height: Float) {
        if (y + height > HEIGHT - BOTTOM) newPage()
    }

    private fun titleBlock(report: AuditReport) {
        fill.color = PRIMARY
        canvas.drawRect(MARGIN, y, WIDTH - MARGIN, y + 70f, fill)
        val white = TextPaint(heading).apply { color = Color.WHITE; textSize = 18f }
        canvas.drawText("Звіт енергетичного аудиту", MARGIN + 14f, y + 28f, white)
        canvas.drawText(report.building.name, MARGIN + 14f, y + 52f, TextPaint(white).apply { textSize = 12f; typeface = Typeface.DEFAULT })
        y += 86f
        keyValue("Адреса", report.building.address)
        keyValue("Дата звіту", report.date.toString())
        keyValue("Аудитор", report.options.auditor.ifBlank { report.building.auditorEmail })
    }

    private fun summary(report: AuditReport) {
        section("Основні показники")
        val a = report.assessment
        val badgeColor = when (a.energyClass.ordinal) { 0, 1 -> Color.rgb(46, 125, 50); 2 -> Color.rgb(104, 159, 56); 3 -> Color.rgb(251, 192, 45); 4 -> Color.rgb(245, 124, 0); else -> Color.rgb(211, 47, 47) }
        fill.color = badgeColor
        canvas.drawRoundRect(RectF(WIDTH - MARGIN - 70f, y, WIDTH - MARGIN, y + 46f), 6f, 6f, fill)
        val badge = TextPaint(bold).apply { textSize = 24f; color = Color.WHITE }
        canvas.drawText(a.energyClass.label, WIDTH - MARGIN - 35f - badge.measureText(a.energyClass.label) / 2, y + 32f, badge)
        keyValue("Опалювана площа", "${num(report.building.heatedAreaM2, 0)} м²")
        keyValue("Рік побудови", report.building.yearBuilt.toString())
        keyValue("Коефіцієнт теплопередачі H", "${num(a.metrics.heatTransferWPerK, 0)} Вт/К")
        keyValue("Питомі тепловтрати", "${num(a.metrics.specificHeatLossWPerM2, 1)} Вт/м²")
        keyValue("Річна потреба в теплі", "${num(a.metrics.annualHeatGcal, 1)} Гкал")
        keyValue("Річні витрати на опалення", "${num(a.annualCostUah, 0)} грн")
    }

    private fun envelope(report: AuditReport) {
        section("Огороджувальні конструкції")
        table(
            listOf("Елемент", "Площа, м²", "R, м²·К/Вт", "Втрати, кВт", "Частка"),
            report.envelope.map { listOf(it.kind.title, num(it.areaM2, 0), num(it.resistance, 2), num(it.lossKw, 1), "${num(it.sharePercent, 0)} %") },
            listOf(0.34f, 0.16f, 0.17f, 0.17f, 0.16f)
        )
    }

    private fun consumption(report: AuditReport) {
        section("Споживання тепла за останні 12 місяців, Гкал")
        val items = report.consumption
        val chartHeight = 150f
        ensureSpace(chartHeight + 30f)
        val left = MARGIN + 30f
        val width = WIDTH - MARGIN - left
        val max = items.maxOfOrNull { it.heatGcal }?.takeIf { it > 0 } ?: 1.0
        val slot = width / items.size
        // Сітка з трьома рівнями й підписами шкали.
        for (step in 0..3) {
            val value = max * step / 3
            val gy = y + chartHeight - (value / max * chartHeight).toFloat()
            canvas.drawLine(left, gy, WIDTH - MARGIN, gy, line)
            canvas.drawText(num(value, 0), MARGIN, gy + 3f, muted)
        }
        val peak = items.maxByOrNull { it.heatGcal }
        items.forEachIndexed { index, item ->
            val h = (item.heatGcal / max * chartHeight).toFloat()
            fill.color = if (item == peak) Color.rgb(211, 47, 47) else Color.rgb(102, 187, 106)
            canvas.drawRect(left + index * slot + slot * 0.15f, y + chartHeight - h, left + index * slot + slot * 0.85f, y + chartHeight, fill)
            canvas.drawText(item.label, left + index * slot + slot * 0.1f, y + chartHeight + 12f, muted)
        }
        y += chartHeight + 24f
        paragraph("Разом за рік ${num(items.sumOf { it.heatGcal }, 1)} Гкал на ${num(items.sumOf { it.costUah }, 0)} грн. Пік припадає на ${peak?.label}.")
    }

    private fun measures(report: AuditReport) {
        section("Заходи термомодернізації")
        if (report.measures.isEmpty()) {
            paragraph("Сервер не повернув переліку заходів.")
            return
        }
        table(
            listOf("Захід", "Економія, грн/рік", "Інвестиції, грн", "Окупність, р.", "Клас"),
            report.measures.map {
                listOf(it.measure.title, num(it.annualSavingUah, 0), num(it.investmentUah, 0), it.paybackYears?.let { p -> num(p, 1) } ?: "—", "${it.classBefore.label} → ${it.classAfter.label}")
            },
            listOf(0.32f, 0.18f, 0.18f, 0.16f, 0.16f)
        )
    }

    private fun photos() {
        section("Фотофіксація")
        val cell = (WIDTH - 2 * MARGIN - 10f) / 2
        photos.chunked(2).forEach { row ->
            val height = row.maxOf { cell * it.height / it.width }
            ensureSpace(height + 10f)
            row.forEachIndexed { index, bitmap ->
                val left = MARGIN + index * (cell + 10f)
                canvas.drawBitmap(bitmap, null, RectF(left, y, left + cell, y + cell * bitmap.height / bitmap.width), null)
            }
            y += height + 10f
        }
    }

    private fun section(name: String) {
        ensureSpace(40f)
        y += 8f
        canvas.drawText(name, MARGIN, y + 12f, heading)
        y += 22f
    }

    private fun keyValue(key: String, value: String) {
        ensureSpace(16f)
        canvas.drawText(key, MARGIN, y + 10f, text)
        canvas.drawText(value, MARGIN + 190f, y + 10f, bold)
        y += 16f
    }

    private fun paragraph(value: String) {
        val layout = StaticLayout.Builder.obtain(value, 0, value.length, text, (WIDTH - 2 * MARGIN).toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(2f, 1f)
            .build()
        // Довгий абзац переноситься на наступну сторінку цілими рядками.
        var line = 0
        while (line < layout.lineCount) {
            ensureSpace(14f)
            val available = ((HEIGHT - BOTTOM - y) / 14f).toInt().coerceAtLeast(1)
            val last = minOf(layout.lineCount, line + available)
            canvas.save()
            canvas.translate(MARGIN, y - layout.getLineTop(line))
            canvas.clipRect(0f, layout.getLineTop(line).toFloat(), WIDTH.toFloat(), layout.getLineTop(last).toFloat())
            layout.draw(canvas)
            canvas.restore()
            y += layout.getLineTop(last) - layout.getLineTop(line)
            line = last
            if (line < layout.lineCount) newPage()
        }
        y += 6f
    }

    /** Таблиця із заголовком; рядки з довгим текстом обрізаються до ширини колонки. */
    private fun table(header: List<String>, rows: List<List<String>>, weights: List<Float>) {
        val width = WIDTH - 2 * MARGIN
        val xs = weights.runningFold(MARGIN) { acc, w -> acc + w * width }
        fun row(cells: List<String>, paint: TextPaint, background: Int?) {
            ensureSpace(ROW)
            background?.let { fill.color = it; canvas.drawRect(MARGIN, y, MARGIN + width, y + ROW, fill) }
            cells.forEachIndexed { i, cell ->
                val maxWidth = weights[i] * width - 8f
                val shown = if (paint.measureText(cell) > maxWidth) cell.take(paint.breakText(cell, true, maxWidth - paint.measureText("…"), null)) + "…" else cell
                canvas.drawText(shown, xs[i] + 4f, y + 13f, paint)
            }
            canvas.drawLine(MARGIN, y + ROW, MARGIN + width, y + ROW, line)
            y += ROW
        }
        row(header, TextPaint(bold).apply { color = Color.WHITE }, PRIMARY)
        rows.forEachIndexed { index, cells -> row(cells, text, if (index % 2 == 1) Color.rgb(241, 248, 233) else null) }
        y += 8f
    }

    private fun num(value: Double, digits: Int) = String.format(Locale("uk", "UA"), "%,.${digits}f", value)

    private companion object {
        const val WIDTH = 595
        const val HEIGHT = 842
        const val MARGIN = 40f
        const val BOTTOM = 40f
        const val ROW = 19f
        val PRIMARY = Color.rgb(46, 125, 50)
    }
}
