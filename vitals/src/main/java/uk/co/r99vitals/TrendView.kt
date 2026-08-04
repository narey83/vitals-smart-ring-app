package uk.co.r99vitals

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * A trend line, drawn rather than charted.
 *
 * No axes, no grid, no legend: at this density the shape is the information, and the numbers are
 * already stated above it. A charting library would bring a dependency and a house style that
 * fights the rest of the app.
 */
class TrendView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var values: List<Int> = emptyList()
    private var accent = 0xFFFF4D6D.toInt()

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val area = Path()

    fun show(readings: List<Int>, colour: Int) {
        // More points than pixels is just noise; keep the most recent.
        values = if (readings.size > 60) readings.takeLast(60) else readings
        accent = colour
        line.color = colour
        dot.color = colour
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.size < 2) return

        val padding = 10f
        val w = width.toFloat()
        val h = height.toFloat() - padding * 2
        val lowest = values.min()
        val highest = values.max()
        // A flat run should read as flat, not as a jagged line amplified by autoscaling.
        val span = (highest - lowest).coerceAtLeast(6)
        val stepX = w / (values.size - 1)

        fun x(i: Int) = i * stepX
        fun y(v: Int) = padding + h - ((v - lowest).toFloat() / span) * h

        path.reset()
        path.moveTo(x(0), y(values[0]))
        // Smooth the corners so a run of readings reads as a trend, not a sawtooth.
        for (i in 1 until values.size) {
            val midX = (x(i - 1) + x(i)) / 2
            path.cubicTo(midX, y(values[i - 1]), midX, y(values[i]), x(i), y(values[i]))
        }

        area.set(path)
        area.lineTo(x(values.size - 1), height.toFloat())
        area.lineTo(x(0), height.toFloat())
        area.close()
        fill.shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            (accent and 0x00FFFFFF) or 0x50000000, accent and 0x00FFFFFF,
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(area, fill)
        canvas.drawPath(path, line)
        // The latest reading gets a dot: it is the one being reported above.
        canvas.drawCircle(x(values.size - 1), y(values.last()), 7f, dot)
    }
}
