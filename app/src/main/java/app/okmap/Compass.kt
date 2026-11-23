package app.okmap

import android.content.Context
import android.content.SharedPreferences
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Compass mode: shows where a chosen target is relative to the way the phone points.
 * Targets: true north, the Qibla (direction to the Kaaba for prayer) or any place chosen on the map / in search.
 * A dashed line on the map shows the same direction from the user's position.
 */
class Compass(
    private val act: AppCompatActivity,
    private val map: () -> MapLibreMap?,
    private val style: () -> Style?,
    private val prefs: SharedPreferences,
    private val position: () -> Location?,
    private val requestLocation: () -> Unit,
    private val isNight: () -> Boolean,
    private val onModeChanged: () -> Unit
) : SensorEventListener {

    enum class Kind { NORTH, QIBLA, PLACE }

    var isOpen = false
        private set
    private var kind = Kind.NORTH
    private var placeLat = Double.NaN
    private var placeLon = Double.NaN
    private var placeName = ""

    private val sm = act.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val gravity = FloatArray(3)
    private val geomag = FloatArray(3)
    private var haveAcc = false
    private var haveMag = false
    private val rm = FloatArray(9)
    private val rm2 = FloatArray(9)
    private val ori = FloatArray(3)

    private var heading = 0f                 // smoothed, degrees from true (or magnetic) north
    private var first = true
    private var declination = 0f
    private var accuracy = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
    private var wasAligned = false
    private var lastMapRotate = 0L
    private var posLat = Double.NaN
    private var posLon = Double.NaN
    private var approx = false
    private var registered = false

    private val panel: View = act.findViewById(R.id.compassPanel)
    private val dial: CompassView = act.findViewById(R.id.compassView)
    private val title: TextView = act.findViewById(R.id.cTitle)
    private val info: TextView = act.findViewById(R.id.cInfo)
    private val guide: TextView = act.findViewById(R.id.cGuide)
    private val warn: TextView = act.findViewById(R.id.cWarn)
    private val chipNorth: TextView = act.findViewById(R.id.chipNorth)
    private val chipQibla: TextView = act.findViewById(R.id.chipQibla)
    private val chipPlace: TextView = act.findViewById(R.id.chipPlace)
    private val btnMap: TextView = act.findViewById(R.id.btnCompassMap)

    init {
        act.findViewById<View>(R.id.btnCompassClose).setOnClickListener { close() }
        chipNorth.setOnClickListener { select(Kind.NORTH) }
        chipQibla.setOnClickListener { select(Kind.QIBLA) }
        chipPlace.setOnClickListener {
            if (placeLat.isNaN()) Toast.makeText(act, "Найдите место в поиске или нажмите на карту и выберите «Компас»", Toast.LENGTH_LONG).show()
            else select(Kind.PLACE)
        }
        btnMap.setOnClickListener {
            prefs.edit().putBoolean("compass_map", !prefs.getBoolean("compass_map", false)).apply()
            if (!prefs.getBoolean("compass_map", false)) resetMapRotation()
            refreshUi()
        }
    }

    // ------------------------------------------------------------------ open / close
    fun open(k: Kind, lat: Double = Double.NaN, lon: Double = Double.NaN, name: String = "") {
        if (k == Kind.PLACE && !lat.isNaN()) { placeLat = lat; placeLon = lon; placeName = name }
        kind = if (k == Kind.PLACE && placeLat.isNaN()) Kind.NORTH else k
        prefs.edit().putString("compass_kind", kind.name).apply()
        if (rotSensor == null && (accSensor == null || magSensor == null)) {
            Toast.makeText(act, "В телефоне нет датчиков компаса", Toast.LENGTH_LONG).show()
            return
        }
        isOpen = true
        first = true
        panel.visibility = View.VISIBLE
        updatePosition(position())
        if (posLat.isNaN() || approx) requestLocation()
        register()
        refreshUi()
        drawMapLine()
        onModeChanged()
    }

    /** Re-opens the last used target (button on the map). */
    fun openLast() {
        val k = try { Kind.valueOf(prefs.getString("compass_kind", "NORTH") ?: "NORTH") } catch (e: Exception) { Kind.NORTH }
        open(if (k == Kind.PLACE && placeLat.isNaN()) Kind.NORTH else k)
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        panel.visibility = View.GONE
        unregister()
        clearMapLine()
        resetMapRotation()
        onModeChanged()
    }

    fun pause() { unregister() }
    fun resume() { if (isOpen) register() }

    private fun select(k: Kind) {
        kind = k
        prefs.edit().putString("compass_kind", k.name).apply()
        wasAligned = false
        refreshUi()
        drawMapLine()
    }

    // ------------------------------------------------------------------ sensors
    private fun register() {
        if (registered) return
        if (rotSensor != null) sm.registerListener(this, rotSensor, SensorManager.SENSOR_DELAY_UI)
        else {
            sm.registerListener(this, accSensor, SensorManager.SENSOR_DELAY_UI)
            sm.registerListener(this, magSensor, SensorManager.SENSOR_DELAY_UI)
        }
        registered = true
    }

    private fun unregister() {
        if (!registered) return
        sm.unregisterListener(this)
        registered = false
    }

    override fun onAccuracyChanged(sensor: Sensor?, acc: Int) {
        accuracy = acc
        refreshUi()
    }

    @Suppress("DEPRECATION")
    override fun onSensorChanged(e: SensorEvent) {
        val ok: Boolean
        when (e.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> { SensorManager.getRotationMatrixFromVector(rm, e.values); ok = true }
            Sensor.TYPE_ACCELEROMETER -> {
                for (i in 0..2) gravity[i] = if (haveAcc) gravity[i] + 0.2f * (e.values[i] - gravity[i]) else e.values[i]
                haveAcc = true
                ok = haveMag && SensorManager.getRotationMatrix(rm, null, gravity, geomag)
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                for (i in 0..2) geomag[i] = if (haveMag) geomag[i] + 0.2f * (e.values[i] - geomag[i]) else e.values[i]
                haveMag = true
                ok = haveAcc && SensorManager.getRotationMatrix(rm, null, gravity, geomag)
            }
            else -> return
        }
        if (!ok) return
        // flat on the hand: the top edge of the phone is the pointer; held upright: the back camera direction is
        SensorManager.getOrientation(rm, ori)
        val pitch = abs(Math.toDegrees(ori[1].toDouble()))
        val azimuth: Double
        if (pitch > 50.0) {
            SensorManager.remapCoordinateSystem(rm, SensorManager.AXIS_X, SensorManager.AXIS_MINUS_Z, rm2)
            SensorManager.getOrientation(rm2, ori)
            azimuth = Math.toDegrees(ori[0].toDouble())
        } else {
            val rot = act.windowManager.defaultDisplay.rotation
            when (rot) {
                1 -> SensorManager.remapCoordinateSystem(rm, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, rm2)
                2 -> SensorManager.remapCoordinateSystem(rm, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, rm2)
                3 -> SensorManager.remapCoordinateSystem(rm, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, rm2)
                else -> System.arraycopy(rm, 0, rm2, 0, 9)
            }
            SensorManager.getOrientation(rm2, ori)
            azimuth = Math.toDegrees(ori[0].toDouble())
        }
        val h = (azimuth + declination + 720.0) % 360.0
        if (first) { heading = h.toFloat(); first = false }
        else {
            val d = ((h - heading + 540.0) % 360.0 - 180.0).toFloat()
            heading = (heading + 0.18f * d + 360f) % 360f
        }
        refreshUi()
        rotateMapIfNeeded()
    }

    // ------------------------------------------------------------------ position / maths
    fun onLocation(l: Location) {
        if (!isOpen) return
        updatePosition(l)
        refreshUi()
        drawMapLine()
    }

    private fun updatePosition(l: Location?) {
        if (l != null) {
            val moved = posLat.isNaN() || approx || abs(l.latitude - posLat) > 0.2 || abs(l.longitude - posLon) > 0.2
            posLat = l.latitude; posLon = l.longitude; approx = false
            if (moved) declination = GeomagneticField(l.latitude.toFloat(), l.longitude.toFloat(), l.altitude.toFloat(),
                System.currentTimeMillis()).declination
        } else if (posLat.isNaN()) {
            val c = map()?.cameraPosition?.target       // no GPS yet: the centre of the map stands in, clearly marked
            if (c != null) {
                posLat = c.latitude; posLon = c.longitude; approx = true
                declination = GeomagneticField(c.latitude.toFloat(), c.longitude.toFloat(), 0f, System.currentTimeMillis()).declination
            }
        }
    }

    private fun targetPoint(): Pair<Double, Double>? = when (kind) {
        Kind.NORTH -> null
        Kind.QIBLA -> Pair(KAABA_LAT, KAABA_LON)
        Kind.PLACE -> if (placeLat.isNaN()) null else Pair(placeLat, placeLon)
    }

    private fun targetBearing(): Double? {
        if (kind == Kind.NORTH) return 0.0
        val t = targetPoint() ?: return null
        if (posLat.isNaN()) return null
        return bearing(posLat, posLon, t.first, t.second)
    }

    private fun refreshUi() {
        if (!isOpen) return
        dial.heading = heading
        val b = targetBearing()
        dial.target = b?.toFloat() ?: Float.NaN
        val diff = if (b == null) 0.0 else ((b - heading + 540.0) % 360.0) - 180.0
        val aligned = b != null && abs(diff) <= 3.0
        dial.aligned = aligned
        if (aligned && !wasAligned) buzz()
        wasAligned = aligned

        chipNorth.isSelected = kind == Kind.NORTH
        chipQibla.isSelected = kind == Kind.QIBLA
        chipPlace.isSelected = kind == Kind.PLACE
        chipPlace.text = if (placeLat.isNaN()) "Место" else placeName.ifBlank { "Место" }.take(14)
        btnMap.isSelected = prefs.getBoolean("compass_map", false)
        btnMap.text = if (btnMap.isSelected) "Карта по ходу: вкл" else "Карта по ходу"

        when (kind) {
            Kind.NORTH -> { title.text = "Север"; info.text = "Истинный север (поправка на склонение ${fmtSigned(declination.toDouble())}°)" }
            Kind.QIBLA -> {
                title.text = "Кибла (направление на Мекку)"
                val t = targetPoint()
                info.text = if (b == null || t == null) "Ждём местоположение…" else
                    "Азимут ${b.roundToInt()}° ${dirName(b)} · до Каабы ${Geo.fmtDist(Graph.haversine(posLat, posLon, t.first, t.second))}"
            }
            Kind.PLACE -> {
                title.text = placeName.ifBlank { "Выбранное место" }
                val t = targetPoint()
                info.text = if (b == null || t == null) "Ждём местоположение…" else
                    "Азимут ${b.roundToInt()}° ${dirName(b)} · ${Geo.fmtDist(Graph.haversine(posLat, posLon, t.first, t.second))}"
            }
        }
        guide.text = when {
            b == null -> ""
            aligned -> "✓ Вы смотрите точно в нужную сторону"
            diff > 0 -> "Повернитесь вправо на ${abs(diff).roundToInt()}°"
            else -> "Повернитесь влево на ${abs(diff).roundToInt()}°"
        }
        val needCal = accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW
        warn.text = when {
            needCal -> "Компас неточен: покрутите телефон «восьмёркой»; отойдите от металла и магнитов"
            approx && kind != Kind.NORTH -> "GPS не найден: направление считается от центра карты"
            else -> ""
        }
        warn.visibility = if (warn.text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun buzz() {
        try {
            val v = act.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE))
            else @Suppress("DEPRECATION") v.vibrate(45)
        } catch (e: Exception) { }
    }

    // ------------------------------------------------------------------ map: direction line + rotate map with heading
    fun addLayers(s: Style) {
        val night = isNight()
        s.addSource(GeoJsonSource("ovl-compass"))
        s.addSource(GeoJsonSource("ovl-compass-pt"))
        s.addLayer(LineLayer("ovl-compass-l", "ovl-compass").withProperties(
            PropertyFactory.lineColor(if (night) "#FFC94D" else "#C77700"), PropertyFactory.lineWidth(3.5f),
            PropertyFactory.lineDasharray(arrayOf(2f, 1.6f)), PropertyFactory.lineCap(Property.LINE_CAP_ROUND)))
        s.addLayer(CircleLayer("ovl-compass-c", "ovl-compass-pt").withProperties(
            PropertyFactory.circleRadius(7f), PropertyFactory.circleColor(if (night) "#FFC94D" else "#C77700"),
            PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(2.5f)))
        if (isOpen) drawMapLine()
    }

    private fun drawMapLine() {
        val s = style() ?: return
        if (!isOpen || posLat.isNaN()) return
        val pts = ArrayList<LatLng>()
        val tp = targetPoint()
        if (kind == Kind.NORTH) {
            for (i in 0..20) pts.add(LatLng(posLat + i * 0.25, posLon))        // ~550 km due north
        } else if (tp != null) {
            val d = Graph.haversine(posLat, posLon, tp.first, tp.second)
            val n = 80
            for (i in 0..n) pts.add(greatCircle(posLat, posLon, tp.first, tp.second, i / n.toDouble()))
            if (d < 1.0) pts.clear()
        }
        s.getSourceAs<GeoJsonSource>("ovl-compass")?.setGeoJson(Geo.lineFc(pts))
        s.getSourceAs<GeoJsonSource>("ovl-compass-pt")?.setGeoJson(
            Geo.pointFc(if (tp != null) listOf(LatLng(tp.first, tp.second)) else emptyList()))
    }

    private fun clearMapLine() {
        val s = style() ?: return
        s.getSourceAs<GeoJsonSource>("ovl-compass")?.setGeoJson(Geo.lineFc(emptyList()))
        s.getSourceAs<GeoJsonSource>("ovl-compass-pt")?.setGeoJson(Geo.pointFc(emptyList()))
    }

    private fun rotateMapIfNeeded() {
        if (!prefs.getBoolean("compass_map", false)) return
        val now = System.currentTimeMillis()
        if (now - lastMapRotate < 120) return
        val m = map() ?: return
        if (abs(((heading - m.cameraPosition.bearing + 540.0) % 360.0) - 180.0) < 2.0) return
        lastMapRotate = now
        m.moveCamera(CameraUpdateFactory.bearingTo(heading.toDouble()))
    }

    private fun resetMapRotation() {
        map()?.easeCamera(CameraUpdateFactory.bearingTo(0.0), 400)
    }

    companion object {
        const val KAABA_LAT = 21.422487
        const val KAABA_LON = 39.826206

        fun bearing(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
            val p1 = Math.toRadians(la1)
            val p2 = Math.toRadians(la2)
            val dl = Math.toRadians(lo2 - lo1)
            val y = sin(dl) * cos(p2)
            val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
            return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
        }

        /** point at fraction [f] (0..1) of the great circle between two points */
        fun greatCircle(la1: Double, lo1: Double, la2: Double, lo2: Double, f: Double): LatLng {
            val p1 = Math.toRadians(la1); val l1 = Math.toRadians(lo1)
            val p2 = Math.toRadians(la2); val l2 = Math.toRadians(lo2)
            val d = 2 * Math.asin(Math.sqrt(sin((p2 - p1) / 2) * sin((p2 - p1) / 2) +
                cos(p1) * cos(p2) * sin((l2 - l1) / 2) * sin((l2 - l1) / 2)))
            if (d < 1e-9) return LatLng(la1, lo1)
            val a = sin((1 - f) * d) / sin(d)
            val b = sin(f * d) / sin(d)
            val x = a * cos(p1) * cos(l1) + b * cos(p2) * cos(l2)
            val y = a * cos(p1) * sin(l1) + b * cos(p2) * sin(l2)
            val z = a * sin(p1) + b * sin(p2)
            return LatLng(Math.toDegrees(atan2(z, Math.sqrt(x * x + y * y))), Math.toDegrees(atan2(y, x)))
        }

        private val DIRS = arrayOf("С", "СВ", "В", "ЮВ", "Ю", "ЮЗ", "З", "СЗ")
        fun dirName(deg: Double): String = DIRS[(((deg % 360 + 360) % 360 + 22.5) / 45).toInt() % 8]

        private fun fmtSigned(v: Double) = (if (v >= 0) "+" else "−") + String.format(java.util.Locale.US, "%.1f", abs(v))
    }
}
