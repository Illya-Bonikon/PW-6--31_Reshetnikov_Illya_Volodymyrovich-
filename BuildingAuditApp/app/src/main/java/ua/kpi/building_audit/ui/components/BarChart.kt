package ua.kpi.building_audit.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Простий стовпчиковий графік; підписи по осі X показуються вибірково, щоб не накладались. */
@Composable
fun BarChart(
    values: List<Double>,
    labels: List<String>,
    barColor: Color,
    labelColor: Color,
    modifier: Modifier = Modifier,
    height: Dp = 160.dp,
    highlight: (Int) -> Color? = { null }
) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier.fillMaxWidth().height(height)) {
        if (values.isEmpty()) return@Canvas
        val labelHeight = 16.dp.toPx()
        val chartHeight = size.height - labelHeight
        val max = values.max().takeIf { it > 0 } ?: 1.0
        val slot = size.width / values.size
        val labelEvery = (values.size / 6).coerceAtLeast(1)
        values.forEachIndexed { index, value ->
            val h = (value / max * chartHeight).toFloat()
            drawRect(highlight(index) ?: barColor, Offset(index * slot + slot * 0.15f, chartHeight - h), Size(slot * 0.7f, h))
            if (index % labelEvery == 0) {
                drawText(measurer, labels.getOrElse(index) { "" }, Offset(index * slot, chartHeight + 2.dp.toPx()), TextStyle(fontSize = 9.sp, color = labelColor))
            }
        }
    }
}
