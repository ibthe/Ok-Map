package app.okmap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Badge icons for transport objects on the map (plane, train, bus, bus stop, ferry): a coloured disc with a white ring
 * and a thin dark outline (readable on light, dark and satellite maps) and a white pictogram.
 * They are drawn here and registered with the style under the ids used by tools/gen_style.py.
 */
object MapIcons {
    private const val WHITE = 0xFFFFFFFF.toInt()

    fun all(ctx: Context): Map<String, Bitmap> {
        val d = ctx.resources.displayMetrics.density
        val size = (30 * d).toInt()
        return mapOf(
            "ic-plane" to badge(size, d, 0xFF6B4FBB.toInt()) { c, p, u, _ -> plane(c, p, u) },
            "ic-train" to badge(size, d, 0xFF1E6FD9.toInt()) { c, p, u, bg -> train(c, p, u, bg) },
            "ic-bus" to badge(size, d, 0xFFD9791F.toInt()) { c, p, u, bg -> bus(c, p, u, bg) },
            "ic-stop" to badge((size * 0.8f).toInt(), d, 0xFFD9791F.toInt()) { c, p, u, bg -> stop(c, p, u, bg) },
            "ic-ferry" to badge(size, d, 0xFF1C9A8C.toInt()) { c, p, u, bg -> ferry(c, p, u, bg) }
        )
    }

    private fun badge(size: Int, density: Float, color: Int, glyph: (Canvas, Paint, Float, Int) -> Unit): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bmp.density = (160 * density).toInt()            // 1 bitmap pixel = 1 device pixel, icon-size 1.0 = 30 dp
        val c = Canvas(bmp)
        val u = size / 24f                               // glyphs are drawn on a 24-unit grid
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0x73000000; c.drawCircle(12 * u, 12 * u, 11.9f * u, p)
        p.color = WHITE; c.drawCircle(12 * u, 12 * u, 11f * u, p)
        p.color = color; c.drawCircle(12 * u, 12 * u, 9.4f * u, p)
        p.color = WHITE
        glyph(c, p, u, color)
        return bmp
    }

    private fun poly(c: Canvas, p: Paint, u: Float, vararg xy: Float) {
        val path = Path()
        path.moveTo(xy[0] * u, xy[1] * u)
        var i = 2
        while (i < xy.size) { path.lineTo(xy[i] * u, xy[i + 1] * u); i += 2 }
        path.close()
        c.drawPath(path, p)
    }

    private fun rect(c: Canvas, p: Paint, u: Float, l: Float, t: Float, r: Float, b: Float, rad: Float = 0f) {
        c.drawRoundRect(RectF(l * u, t * u, r * u, b * u), rad * u, rad * u, p)
    }

    private fun plane(c: Canvas, p: Paint, u: Float) =
        poly(c, p, u, 12f, 5f, 13.2f, 9.5f, 19f, 13f, 19f, 14.6f, 13.2f, 12.8f, 12.8f, 16.5f, 15f, 18f, 15f, 19f,
            12f, 18.3f, 9f, 19f, 9f, 18f, 11.2f, 16.5f, 10.8f, 12.8f, 5f, 14.6f, 5f, 13f, 10.8f, 9.5f)

    private fun train(c: Canvas, p: Paint, u: Float, bg: Int) {
        rect(c, p, u, 7.5f, 5.5f, 16.5f, 16.5f, 2.6f)
        val w = p.color
        p.color = bg
        rect(c, p, u, 9f, 7.4f, 15f, 12f, 0.8f)
        c.drawCircle(10.2f * u, 14.4f * u, 0.95f * u, p)
        c.drawCircle(13.8f * u, 14.4f * u, 0.95f * u, p)
        p.color = w
        rect(c, p, u, 8.7f, 17f, 10.2f, 18.6f, 0.4f)
        rect(c, p, u, 13.8f, 17f, 15.3f, 18.6f, 0.4f)
    }

    private fun bus(c: Canvas, p: Paint, u: Float, bg: Int) {
        rect(c, p, u, 6.5f, 6f, 17.5f, 16.8f, 2.2f)
        val w = p.color
        p.color = bg
        rect(c, p, u, 8f, 7.6f, 16f, 11.4f, 0.8f)
        p.color = w
        c.drawCircle(9f * u, 17.8f * u, 1.4f * u, p)
        c.drawCircle(15f * u, 17.8f * u, 1.4f * u, p)
    }

    private fun stop(c: Canvas, p: Paint, u: Float, bg: Int) {
        rect(c, p, u, 7.6f, 5.6f, 16.4f, 13f, 1.6f)
        rect(c, p, u, 11.2f, 13f, 12.8f, 19f)
        val w = p.color
        p.color = bg
        rect(c, p, u, 9.4f, 7.3f, 14.6f, 10.4f, 0.8f)
        p.color = w
    }

    private fun ferry(c: Canvas, p: Paint, u: Float, bg: Int) {
        poly(c, p, u, 5.2f, 13.2f, 18.8f, 13.2f, 16.6f, 17.8f, 7.4f, 17.8f)
        rect(c, p, u, 8.4f, 9f, 15.6f, 13.2f, 0.6f)
        rect(c, p, u, 11f, 6f, 13f, 9f)
        val w = p.color
        p.color = bg
        rect(c, p, u, 9.6f, 10.2f, 11.2f, 11.8f)
        rect(c, p, u, 12.8f, 10.2f, 14.4f, 11.8f)
        p.color = w
    }
}
