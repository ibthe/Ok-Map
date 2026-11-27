package app.okmap

import org.maplibre.android.geometry.LatLng
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point
import java.util.Locale
import kotlin.math.roundToInt

object Geo {
    fun fmtDist(m: Double): String =
        if (m < 1000) "${m.roundToInt()} м" else String.format(Locale.US, "%.2f км", m / 1000.0)

    fun fmtCoord(lat: Double, lon: Double): String = String.format(Locale.US, "%.5f, %.5f", lat, lon)

    fun length(pts: List<LatLng>): Double {
        var d = 0.0
        for (i in 1 until pts.size) d += pts[i - 1].distanceTo(pts[i])
        return d
    }

    fun pointFc(pts: List<LatLng>, names: List<String>? = null): FeatureCollection {
        val fs = ArrayList<Feature>()
        for (i in pts.indices) {
            val f = Feature.fromGeometry(Point.fromLngLat(pts[i].longitude, pts[i].latitude))
            if (names != null) f.addStringProperty("name", names[i])
            fs.add(f)
        }
        return FeatureCollection.fromFeatures(fs)
    }

    fun lineFc(pts: List<LatLng>): FeatureCollection {
        val fs = ArrayList<Feature>()
        if (pts.size >= 2) {
            fs.add(Feature.fromGeometry(LineString.fromLngLats(pts.map { Point.fromLngLat(it.longitude, it.latitude) })))
        }
        return FeatureCollection.fromFeatures(fs)
    }

    /** Draw disconnected track segments as a MultiLineString, never as a false connecting line. */
    fun multiLineFc(segments: List<List<LatLng>>): FeatureCollection {
        val lines = segments.filter { it.size >= 2 }.map { segment ->
            segment.map { Point.fromLngLat(it.longitude, it.latitude) }
        }
        val features = if (lines.isEmpty()) emptyList() else listOf(
            Feature.fromGeometry(MultiLineString.fromLngLats(lines))
        )
        return FeatureCollection.fromFeatures(features)
    }

    /** Accepts "41.7151, 44.8271", "41.7151 44.8271", "41.7151;44.8271". */
    fun parseCoord(text: String): LatLng? {
        val nums = Regex("-?\\d+(?:[.,]\\d+)?").findAll(text.replace(';', ' '))
            .map { it.value.replace(',', '.').toDoubleOrNull() }.toList()
        if (nums.size < 2 || nums[0] == null || nums[1] == null) return null
        val lat = nums[0]!!
        val lon = nums[1]!!
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) return null
        return LatLng(lat, lon)
    }
}
