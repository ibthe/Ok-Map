package app.okmap

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.max
import kotlin.math.roundToInt

data class ElevationSample(val distanceMeters: Double, val elevationMeters: Double)

data class ElevationSummary(
    val distanceMeters: Double,
    val minElevationMeters: Double,
    val maxElevationMeters: Double,
    val ascentMeters: Double,
    val descentMeters: Double
)

object ElevationProfile {
    /** Ignore small GPS altitude noise when summing ascent/descent. */
    fun summarize(samples: List<ElevationSample>): ElevationSummary? {
        val valid = samples.filter {
            it.distanceMeters.isFinite() && it.elevationMeters.isFinite()
        }.sortedBy { it.distanceMeters }
        if (valid.size < 2) return null
        var ascent = 0.0
        var descent = 0.0
        for (i in 1 until valid.size) {
            val delta = valid[i].elevationMeters - valid[i - 1].elevationMeters
            if (delta > 3.0) ascent += delta
            else if (delta < -3.0) descent -= delta
        }
        return ElevationSummary(
            (valid.last().distanceMeters - valid.first().distanceMeters).coerceAtLeast(0.0),
            valid.minOf { it.elevationMeters },
            valid.maxOf { it.elevationMeters },
            ascent,
            descent
        )
    }
}

/** Lightweight offline elevation chart for a recorded GPX track. */
class ElevationProfileView(
    context: Context,
    private val samples: List<ElevationSample>,
    accentColor: Int,
    textColor: Int,
    gridColor: Int
) : View(context) {
    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(38, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = gridColor
        strokeWidth = dp(1f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textSize = dp(10f)
    }

    init {
        contentDescription = "График профиля высот"
        minimumHeight = dp(180f).roundToInt()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val valid = samples.filter { it.distanceMeters.isFinite() && it.elevationMeters.isFinite() }
            .sortedBy { it.distanceMeters }
        if (valid.size < 2) return
        val left = dp(48f)
        val right = width - dp(10f)
        val top = dp(12f)
        val bottom = height - dp(28f)
        if (right <= left || bottom <= top) return

        val minElevation = valid.minOf { it.elevationMeters }
        val maxElevation = valid.maxOf { it.elevationMeters }
        val actualRange = maxElevation - minElevation
        val elevationRange = max(10.0, actualRange)
        val chartMin = minElevation - (elevationRange - actualRange) / 2.0
        val chartMax = chartMin + elevationRange
        val distanceRange = max(1.0, valid.last().distanceMeters - valid.first().distanceMeters)
        for (i in 0..4) {
            val fraction = i / 4.0
            val y = top + ((bottom - top) * fraction).toFloat()
            canvas.drawLine(left, y, right, y, gridPaint)
            val value = (chartMax - elevationRange * fraction).roundToInt()
            canvas.drawText("$value м", dp(2f), y + dp(3f), textPaint)
        }

        val line = Path()
        valid.forEachIndexed { index, sample ->
            val x = left + ((sample.distanceMeters - valid.first().distanceMeters) / distanceRange * (right - left)).toFloat()
            val y = bottom - ((sample.elevationMeters - chartMin) / elevationRange * (bottom - top)).toFloat()
            if (index == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }
        val fill = Path(line).apply {
            lineTo(right, bottom)
            lineTo(left, bottom)
            close()
        }
        canvas.drawPath(fill, fillPaint)
        canvas.drawPath(line, linePaint)
        canvas.drawText("0 м", left, height - dp(7f), textPaint)
        val distanceLabel = Geo.fmtDist(distanceRange)
        canvas.drawText(distanceLabel, right - textPaint.measureText(distanceLabel), height - dp(7f), textPaint)
    }
}
