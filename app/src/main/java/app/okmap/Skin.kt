package app.okmap

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue

/**
 * Backgrounds of panels and "key" buttons built in code, so that their opacity can be changed by the user (Menu -> Оформление).
 * Only the fill gets transparent: the outline (rim colour) always stays opaque and the key keeps its darker lower edge,
 * which is what keeps everything readable over a busy map.
 */
object Skin {
    private fun attr(ctx: Context, a: Int): Int {
        val tv = TypedValue()
        ctx.theme.resolveAttribute(a, tv, true)
        return tv.data
    }

    private fun dp(ctx: Context, v: Float) = v * ctx.resources.displayMetrics.density
    private fun alpha(c: Int, op: Float) = (c and 0xFFFFFF) or ((op * 255).toInt().coerceIn(0, 255) shl 24)

    fun panel(ctx: Context, radiusDp: Float, opacity: Float): Drawable = GradientDrawable().apply {
        cornerRadius = dp(ctx, radiusDp)
        setColor(alpha(attr(ctx, R.attr.gPanel), opacity))
        setStroke(dp(ctx, 1.5f).toInt(), attr(ctx, R.attr.gRim))
    }

    private fun keyLayers(ctx: Context, oval: Boolean, radiusDp: Float, accent: Boolean, sunk: Boolean, op: Float): Drawable {
        val edge = attr(ctx, if (accent) R.attr.gAccentEdge else R.attr.gRaisedEdge)
        val top = alpha(attr(ctx, if (accent) R.attr.gAccentTop else R.attr.gRaisedTop), if (accent) 1f else op)
        val bottom = alpha(attr(ctx, if (accent) R.attr.gAccentBottom else R.attr.gRaisedBottom), if (accent) 1f else op)
        val e = GradientDrawable().apply {
            shape = if (oval) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
            cornerRadius = dp(ctx, radiusDp)
            setColor(alpha(edge, maxOf(op, 0.6f)))
        }
        val f = GradientDrawable(
            if (sunk) GradientDrawable.Orientation.BOTTOM_TOP else GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(top, bottom)
        ).apply {
            shape = if (oval) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
            cornerRadius = dp(ctx, radiusDp)
            if (!accent) setStroke(dp(ctx, 1.5f).toInt(), attr(ctx, R.attr.gRim))
        }
        val ld = LayerDrawable(arrayOf(e, f))
        if (sunk) ld.setLayerInset(1, 0, dp(ctx, 2f).toInt(), 0, 0) else ld.setLayerInset(1, 0, 0, 0, dp(ctx, 3f).toInt())
        return ld
    }

    /** round or pill key; pressed = sunk */
    fun key(ctx: Context, oval: Boolean, radiusDp: Float, opacity: Float, accent: Boolean = false): Drawable {
        val sl = StateListDrawable()
        sl.addState(intArrayOf(android.R.attr.state_pressed), keyLayers(ctx, oval, radiusDp, accent, true, opacity))
        sl.addState(intArrayOf(), keyLayers(ctx, oval, radiusDp, accent, false, opacity))
        return RippleDrawable(ColorStateList.valueOf(0x26808080), sl, null)
    }

    /** selectable chip: selected = accent colour and sunk, pressed = sunk, otherwise raised */
    fun chip(ctx: Context, opacity: Float): Drawable {
        val sl = StateListDrawable()
        sl.addState(intArrayOf(android.R.attr.state_selected), keyLayers(ctx, false, 28f, true, true, 1f))
        sl.addState(intArrayOf(android.R.attr.state_pressed), keyLayers(ctx, false, 28f, false, true, opacity))
        sl.addState(intArrayOf(), keyLayers(ctx, false, 28f, false, false, opacity))
        return RippleDrawable(ColorStateList.valueOf(0x26808080), sl, null)
    }
}
