package app.okmap

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Compass dial: the ring turns so that "С" always points to true north; the marker on the ring is the target,
 * the fixed triangle at the top is where the phone points. When both meet the target is straight ahead.
 */
class CompassView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var heading = 0f
        set(v) { field = v; invalidate() }
    var target = Float.NaN
        set(v) { field = v; invalidate() }
    var aligned = false
        set(v) { field = v; invalidate() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private fun col(attr: Int): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - dp(16f)
        val text = col(R.attr.gTextPrimary)
        val text2 = col(R.attr.gTextSecondary)
        val accent = col(R.attr.gAccent)
        val north = col(R.attr.gCrosshair)

        // raised disc: rim = thickness, face = lit surface
        p.style = Paint.Style.FILL
        p.color = col(R.attr.gRaisedEdge)
        c.drawCircle(cx, cy + dp(4f), r + dp(4f), p)
        p.color = col(R.attr.gRaisedTop)
        c.drawCircle(cx, cy, r + dp(4f), p)
        p.color = col(R.attr.gDialog)
        c.drawCircle(cx, cy, r - dp(6f), p)

        // dial (turns against the heading)
        c.save()
        c.rotate(-heading, cx, cy)
        p.style = Paint.Style.STROKE
        for (deg in 0 until 360 step 5) {
            val len = when { deg % 30 == 0 -> dp(14f); deg % 10 == 0 -> dp(9f); else -> dp(5f) }
            p.strokeWidth = if (deg % 30 == 0) dp(2f) else dp(1f)
            p.color = if (deg == 0) north else text2
            c.save()
            c.rotate(deg.toFloat(), cx, cy)
            c.drawLine(cx, cy - r + dp(8f), cx, cy - r + dp(8f) + len, p)
            c.restore()
        }
        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        p.textSize = sp(20f)
        val letters = arrayOf("С", "В", "Ю", "З")
        for (i in 0 until 4) {
            p.color = if (i == 0) north else text
            c.save()
            c.rotate(i * 90f, cx, cy)
            c.drawText(letters[i], cx, cy - r + dp(48f), p)
            c.restore()
        }
        if (!target.isNaN()) {
            c.save()
            c.rotate(target, cx, cy)
            p.style = Paint.Style.STROKE
            p.strokeWidth = dp(2f)
            p.color = accent
            p.alpha = 90
            c.drawLine(cx, cy, cx, cy - r + dp(70f), p)
            p.alpha = 255
            p.style = Paint.Style.FILL
            val mr = if (aligned) dp(15f) else dp(12f)
            c.drawCircle(cx, cy - r + dp(70f), mr, p)
            p.color = col(R.attr.gOnAccent)
            c.drawCircle(cx, cy - r + dp(70f), mr * 0.4f, p)
            c.restore()
        }
        c.restore()

        // ring highlight when the phone points at the target
        if (aligned) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = dp(4f)
            p.color = accent
            c.drawCircle(cx, cy, r - dp(2f), p)
        }

        // fixed pointer: the direction the phone is facing
        p.style = Paint.Style.FILL
        p.color = if (aligned) accent else text
        path.reset()
        path.moveTo(cx, cy - r + dp(2f))
        path.lineTo(cx - dp(11f), cy - r - dp(14f))
        path.lineTo(cx + dp(11f), cy - r - dp(14f))
        path.close()
        c.drawPath(path, p)

        // centre read-out
        p.color = text
        p.textSize = sp(34f)
        p.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val h = ((heading.roundToInt() % 360) + 360) % 360
        c.drawText("$h°", cx, cy + dp(6f), p)
        p.textSize = sp(16f)
        p.color = text2
        c.drawText(Compass.dirName(heading.toDouble()), cx, cy + dp(32f), p)
    }

    override fun onMeasure(w: Int, h: Int) {
        val size = min(MeasureSpec.getSize(w).takeIf { it > 0 } ?: dp(240f).toInt(), dp(264f).toInt())
        setMeasuredDimension(size, size)
    }
}
