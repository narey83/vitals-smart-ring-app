package uk.co.r99vitals

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The blood pressure categories, as the NHS and NICE state them.
 *
 * [sys] and [dia] are the numbers a reading has to reach to be in the band, and a reading is
 * placed by whichever of its two halves lands higher: 130/95 is stage one high blood pressure
 * on the diastolic alone. Low is the exception and is judged on either half falling short,
 * because 85/70 is a low reading however comfortable the diastolic looks.
 */
enum class BpBand(
    val label: String,
    val sys: Int,
    val dia: Int,
    val colour: Color,
    val advice: String,
    /** Worth interrupting for, rather than worth knowing. */
    val alert: Boolean = false
) {
    Low("Low", 0, 0, Color(0xFF4AA3FF), "Below the usual range. Worth mentioning if you feel faint or dizzy."),
    Ideal("Ideal", 90, 60, Color(0xFF2DE59B), "In the healthy range."),
    Raised("Slightly raised", 120, 80, Color(0xFFFFC24B), "Above ideal but not high. Watch it over the coming weeks."),
    HighOne("High — stage 1", 140, 90, Color(0xFFFF8A3D), "High blood pressure. See a GP if repeat readings stay here.", alert = true),
    HighTwo("High — stage 2", 160, 100, Color(0xFFFF4D4D), "Clearly high. Arrange a GP appointment.", alert = true),
    Crisis("Very high", 180, 120, Color(0xFFD5006C), "Seek urgent medical advice, especially with chest pain, breathlessness or blurred vision.", alert = true);

    companion object {
        fun of(systolic: Int, diastolic: Int): BpBand =
            if (systolic < Ideal.sys || diastolic < Ideal.dia) Low
            else entries.last { systolic >= it.sys || diastolic >= it.dia }
    }
}

/** The scale the chart is drawn against, wide enough to hold every band and a little air. */
private const val FLOOR = 40f
private const val CEILING = 200f

/**
 * A day of readings against the standard bands.
 *
 * The point of a fixed scale is that the same reading looks the same every day: a trend line
 * that rescales itself to whatever it was handed makes a calm afternoon look alarming. The
 * shading is the systolic ladder — the two halves have different thresholds and drawing both
 * sets would be graph paper — while the verdict beneath uses both numbers.
 */
@Composable
fun PressureChart(
    systolic: List<Int>,
    diastolic: List<Int>,
    positions: List<Float>,
    modifier: Modifier
) {
    val text = Ink.muted
    val accent = Ink.pressure
    Canvas(modifier) {
        val gutter = SCALE_GUTTER.toPx()
        val plot = size.width - gutter
        fun y(v: Float) = size.height * (1f - (v.coerceIn(FLOOR, CEILING) - FLOOR) / (CEILING - FLOOR))

        val label = android.graphics.Paint().apply {
            color = text.toArgb()
            textSize = 24f
            isAntiAlias = true
        }
        // Each band is shaded from its own threshold up to where the next one takes over.
        val bands = BpBand.entries
        bands.forEachIndexed { i, band ->
            val top = if (i == bands.lastIndex) CEILING else bands[i + 1].sys.toFloat()
            drawRect(
                color = band.colour.copy(alpha = 0.14f),
                topLeft = Offset(0f, y(top)),
                size = Size(plot, y(band.sys.toFloat().coerceAtLeast(FLOOR)) - y(top))
            )
            if (band != BpBand.Low) {
                drawLine(band.colour.copy(alpha = 0.5f), Offset(0f, y(band.sys.toFloat())), Offset(plot, y(band.sys.toFloat())), strokeWidth = 2f)
                drawContext.canvas.nativeCanvas.drawText(
                    band.sys.toString(), plot + 8f, y(band.sys.toFloat()) + 8f, label
                )
            }
        }

        if (systolic.size < 2) return@Canvas
        val timed = positions.size == systolic.size
        fun x(i: Int) = if (timed) positions[i] * plot else i * (plot / (systolic.lastIndex))

        fun draw(values: List<Int>, colour: Color, width: Float) {
            val path = Path().apply {
                moveTo(x(0), y(values[0].toFloat()))
                for (i in 1 until values.size) {
                    val midX = (x(i - 1) + x(i)) / 2
                    val midY = (y(values[i - 1].toFloat()) + y(values[i].toFloat())) / 2
                    quadraticTo(x(i - 1), y(values[i - 1].toFloat()), midX, midY)
                }
                lineTo(x(values.lastIndex), y(values.last().toFloat()))
            }
            drawPath(path, colour, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawCircle(colour, radius = 8f, center = Offset(x(values.lastIndex), y(values.last().toFloat())))
        }
        draw(systolic, accent, 6f)
        if (diastolic.size == systolic.size) draw(diastolic, accent.copy(alpha = 0.45f), 4f)
    }
}

/** Which line is which, and which numbers the shading belongs to. */
@Composable
fun PressureKey() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(Ink.pressure)
        Text("Systolic", color = Ink.muted, fontSize = 11.sp)
        Spacer(Modifier.width(12.dp))
        Dot(Ink.pressure.copy(alpha = 0.45f))
        Text("Diastolic", color = Ink.muted, fontSize = 11.sp)
        Spacer(Modifier.width(12.dp))
        Text("Bands: systolic", color = Ink.muted.copy(alpha = 0.7f), fontSize = 11.sp)
    }
}

@Composable
private fun Dot(colour: Color) {
    androidx.compose.foundation.layout.Box(
        Modifier.size(8.dp).clip(CircleShape).background(colour)
    )
    Spacer(Modifier.width(5.dp))
}

/**
 * What the latest reading means, in the band's own colour.
 *
 * A number on a chart is not advice, and a reading in the high bands is the whole reason
 * anyone opens this page, so it says what it is and what to do about it.
 */
@Composable
fun PressureVerdict(systolic: Int, diastolic: Int) {
    val band = BpBand.of(systolic, diastolic)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(band.colour.copy(alpha = if (band.alert) 0.20f else 0.12f))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(band.colour)
            Text(
                band.label.uppercase(), color = band.colour, fontSize = 12.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp
            )
            Spacer(Modifier.width(8.dp))
            Text("$systolic/$diastolic", color = Ink.text, fontSize = 13.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(band.advice, color = Ink.text, fontSize = 13.sp)
    }
}
