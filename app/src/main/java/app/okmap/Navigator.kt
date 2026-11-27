package app.okmap

import android.content.Intent
import android.content.SharedPreferences
import android.location.Location
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Route planning and turn-by-turn guidance on top of [Graph]: route preview, follow camera, spoken prompts (system TTS,
 * works offline), automatic rerouting. All UI views live in activity_main.xml.
 */
class Navigator(
    private val act: AppCompatActivity,
    private val graphFile: () -> File?,
    private val map: () -> MapLibreMap?,
    private val style: () -> Style?,
    private val prefs: SharedPreferences,
    private val isNight: () -> Boolean,
    private val onModeChanged: () -> Unit
) {
    private enum class State { IDLE, PREVIEW, NAV }

    private var state = State.IDLE
    private var graph: Graph? = null
    private var route: Route? = null
    private var destLat = 0.0
    private var destLon = 0.0
    private var destTitle = ""
    private var fromLat = 0.0
    private var fromLon = 0.0
    private var connStart = 0.0      // metres from the start point to the road
    private var connEnd = 0.0        // metres from the road to the destination
    private var progressIdx = 0
    private var along = 0.0
    private var stepIdx = 1
    private var announced = IntArray(0)
    private var offCount = 0
    private var lastReroute = 0L
    private var busy = false
    private var follow = true
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null
    @Volatile private var disposed = false

    val active: Boolean get() = state != State.IDLE
    val navigating: Boolean get() = state == State.NAV

    private val navTop: View = act.findViewById(R.id.navTop)
    private val navArrow: TextView = act.findViewById(R.id.navArrow)
    private val navDist: TextView = act.findViewById(R.id.navDist)
    private val navText: TextView = act.findViewById(R.id.navText)
    private val navBottom: View = act.findViewById(R.id.navBottom)
    private val navStat: TextView = act.findViewById(R.id.navStat)
    private val navSub: TextView = act.findViewById(R.id.navSub)
    private val btnCancel: TextView = act.findViewById(R.id.btnNavCancel)
    private val btnGo: TextView = act.findViewById(R.id.btnNavGo)

    init {
        navArrow.isSelected = true      // accent "pressed-in" look of the arrow tile (bg_chip, selected state)
        btnCancel.setOnClickListener { stop() }
        btnGo.setOnClickListener { start() }
    }

    // ------------------------------------------------------------------ map layers
    fun addLayers(s: Style) {
        val night = isNight()
        addConnectorLayers(s)
        s.addSource(GeoJsonSource("ovl-route"))
        s.addLayer(LineLayer("ovl-route-casing", "ovl-route").withProperties(
            PropertyFactory.lineColor(if (night) "#0B1220" else "#FFFFFF"), PropertyFactory.lineWidth(10f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
        s.addLayer(LineLayer("ovl-route-l", "ovl-route").withProperties(
            PropertyFactory.lineColor(if (night) "#5AB0FF" else "#1769E0"), PropertyFactory.lineWidth(6f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
    }

    private fun addConnectorLayers(s: Style) {
        val night = isNight()
        for (id in listOf("ovl-route-c1", "ovl-route-c2")) {
            s.addSource(GeoJsonSource(id))
            s.addLayer(LineLayer("$id-l", id).withProperties(
                PropertyFactory.lineColor(if (night) "#5AB0FF" else "#1769E0"), PropertyFactory.lineWidth(4f),
                PropertyFactory.lineDasharray(arrayOf(1.2f, 1.6f)), PropertyFactory.lineCap(Property.LINE_CAP_ROUND)))
        }
    }

    /** Called after the style was (re)loaded, e.g. on a theme change while a route is shown. */
    fun restoreRoute() {
        val r = route ?: return
        showRouteOnMap(r)
    }

    private fun showRouteOnMap(r: Route) {
        val pts = ArrayList<LatLng>(r.lat.size)
        for (i in r.lat.indices) pts.add(LatLng(r.lat[i], r.lon[i]))
        style()?.getSourceAs<GeoJsonSource>("ovl-route")?.setGeoJson(Geo.lineFc(pts))
        style()?.getSourceAs<GeoJsonSource>("ovl-search")?.setGeoJson(Geo.pointFc(listOf(LatLng(destLat, destLon))))
        val last = r.lat.size - 1
        // dashed "off-road" connectors: start point -> road, road -> destination
        style()?.getSourceAs<GeoJsonSource>("ovl-route-c1")?.setGeoJson(Geo.lineFc(
            if (connStart > 15) listOf(LatLng(fromLat, fromLon), LatLng(r.lat[0], r.lon[0])) else emptyList()))
        style()?.getSourceAs<GeoJsonSource>("ovl-route-c2")?.setGeoJson(Geo.lineFc(
            if (connEnd > 15) listOf(LatLng(r.lat[last], r.lon[last]), LatLng(destLat, destLon)) else emptyList()))
    }

    // ------------------------------------------------------------------ planning
    fun planTo(lat: Double, lon: Double, title: String, fromLat: Double, fromLon: Double, silent: Boolean = false) {
        if (disposed) return
        val f = graphFile()
        if (f == null) {
            toast("Нет данных дорог: в приложении не найден graph.bin")
            return
        }
        if (busy) return
        busy = true
        if (!silent) toast("Строю маршрут…")
        thread(name = "route") {
            var result: Route? = null
            var alternative: Route? = null
            var error: String? = null
            var sd = 0.0
            var ed = 0.0
            try {
                val g = graph ?: Graph(f).also { graph = it }
                val avoid = prefs.getBoolean("nav_avoid_unpaved", true)
                // both points are moved to the nearest road, however far it is
                val s = g.snap(fromLat, fromLon)
                val e = g.snap(lat, lon)
                if (s == null || e == null) error = "В данных нет дорог"
                else {
                    sd = s.dist; ed = e.dist
                    val legs = g.route(s, e, avoid)
                    if (legs == null) error = "Маршрут не найден: дороги в этих местах не связаны"
                    else {
                        result = g.buildRoute(legs, avoid)
                        if (!silent) {
                            val alternativeLegs = g.routeAlternative(s, e, avoid, legs)
                            if (alternativeLegs != null) alternative = g.buildRoute(alternativeLegs, avoid)
                        }
                    }
                }
            } catch (ex: Exception) {
                error = "Ошибка маршрута: ${ex.message}"
            } catch (oom: OutOfMemoryError) {
                error = "Не хватило памяти для маршрута"
            }
            act.runOnUiThread {
                busy = false
                if (disposed || act.isFinishing || act.isDestroyed) return@runOnUiThread
                if (silent && !navigating) return@runOnUiThread        // navigation was ended while rerouting
                val r = result
                if (error != null || r == null) { toast(error ?: "Маршрут не найден"); return@runOnUiThread }
                val accept: (Route) -> Unit = { selected ->
                    destLat = lat; destLon = lon; destTitle = title
                    this.fromLat = fromLat; this.fromLon = fromLon
                    connStart = sd; connEnd = ed
                    applyRoute(selected, silent)
                }
                when {
                    silent -> accept(r)
                    sd > 200_000.0 -> farFromMap(sd, lat, lon, title)
                    maxOf(sd, ed) > 30_000.0 -> MaterialAlertDialogBuilder(act)
                        .setTitle("Дорога далеко")
                        .setMessage(("Ближайшая дорога: ${if (sd > 30_000.0) "к месту старта" else "к точке назначения"} " +
                            "в ${Geo.fmtDist(maxOf(sd, ed))}. Построить маршрут до неё и дальше по прямой?"))
                        .setPositiveButton("Построить") { _, _ -> accept(r) }
                        .setNegativeButton("Отмена", null).show()
                    else -> {
                        val alt = alternative
                        if (alt != null) chooseRoute(r, alt, accept) else accept(r)
                    }
                }
            }
        }
    }

    private fun chooseRoute(primary: Route, alternative: Route, onSelected: (Route) -> Unit) {
        fun label(title: String, route: Route): String {
            val minutes = ((route.seconds / 60.0) + 0.5).toInt().coerceAtLeast(1)
            return "$title · ${Geo.fmtDist(route.distance)} · $minutes мин"
        }
        MaterialAlertDialogBuilder(act)
            .setTitle("Выберите маршрут")
            .setItems(arrayOf(label("Основной", primary), label("Альтернативный", alternative))) { _, index ->
                onSelected(if (index == 0) primary else alternative)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /** The user is far outside the map (e.g. testing at home): offer the crosshair as an explicit start. */
    private fun farFromMap(dist: Double, lat: Double, lon: Double, title: String) {
        val c = map()?.cameraPosition?.target
        val b = MaterialAlertDialogBuilder(act)
            .setTitle("Вы за пределами карты")
            .setMessage("Ваше местоположение в ${Geo.fmtDist(dist)} от ближайшей дороги этой карты. " +
                "Можно построить маршрут от перекрестия (центра экрана).")
            .setNegativeButton("Отмена", null)
        if (c != null) b.setPositiveButton("От перекрестия") { _, _ -> planTo(lat, lon, title, c.latitude, c.longitude) }
        b.show()
    }

    private fun applyRoute(r: Route, rerouted: Boolean) {
        route = r
        progressIdx = 0
        along = 0.0
        stepIdx = 1
        announced = IntArray(r.steps.size)
        offCount = 0
        showRouteOnMap(r)
        if (rerouted && navigating) {
            updateGuidance(null)
            return
        }
        state = State.PREVIEW
        val b = LatLngBounds.Builder()
        for (i in r.lat.indices step max(1, r.lat.size / 400)) b.include(LatLng(r.lat[i], r.lon[i]))
        b.include(LatLng(r.lat[r.lat.size - 1], r.lon[r.lon.size - 1]))
        val d = act.resources.displayMetrics.density
        map()?.easeCamera(CameraUpdateFactory.newLatLngBounds(b.build(), (32 * d).toInt(), (96 * d).toInt(), (32 * d).toInt(), (200 * d).toInt()), 800)
        showPanel(preview = true)
        onModeChanged()
    }

    // ------------------------------------------------------------------ navigation
    private fun start() {
        val r = route ?: return
        state = State.NAV
        follow = true
        act.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        try { ContextCompat.startForegroundService(act, Intent(act, NavService::class.java)) } catch (e: Exception) {}
        if (prefs.getBoolean("nav_voice", true)) initTts()
        val h = act.resources.displayMetrics.heightPixels
        map()?.setPadding(0, (h * 0.35).toInt(), 0, 0)
        showPanel(preview = false)
        updateGuidance(null)
        speak(r.steps.getOrNull(1)?.let { "Маршрут построен. " + describe(r, it) } ?: "Маршрут построен")
        onModeChanged()
    }

    fun stop() {
        val wasNav = navigating
        state = State.IDLE
        route = null
        act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        act.stopService(Intent(act, NavService::class.java))
        tts?.stop(); tts?.shutdown(); tts = null; ttsReady = false
        style()?.getSourceAs<GeoJsonSource>("ovl-route")?.setGeoJson(Geo.lineFc(emptyList()))
        style()?.getSourceAs<GeoJsonSource>("ovl-search")?.setGeoJson(Geo.pointFc(emptyList()))
        style()?.getSourceAs<GeoJsonSource>("ovl-route-c1")?.setGeoJson(Geo.lineFc(emptyList()))
        style()?.getSourceAs<GeoJsonSource>("ovl-route-c2")?.setGeoJson(Geo.lineFc(emptyList()))
        navTop.visibility = View.GONE
        navBottom.visibility = View.GONE
        map()?.let { m ->
            m.setPadding(0, 0, 0, 0)
            if (wasNav) {
                val c = m.cameraPosition.target
                if (c != null) m.easeCamera(CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder().target(c).zoom(m.cameraPosition.zoom).bearing(0.0).tilt(0.0).build()), 600)
            }
        }
        onModeChanged()
    }

    fun onUserGesture() { if (navigating) follow = false }

    fun recenter(loc: Location?) {
        follow = true
        if (loc != null) followCamera(loc)
    }

    /** Called for every GPS fix while [navigating]. */
    fun onLocation(l: Location) {
        val r = route ?: return
        if (!navigating) return
        val n = r.lat.size
        // nearest point of the route in a window around the last known position
        val from = max(0, progressIdx - 400)
        val to = min(n - 2, progressIdx + 400)
        val k = cos(Math.toRadians(l.latitude))
        var bestD = Double.MAX_VALUE
        var bestJ = progressIdx
        var bestAlong = along
        for (j in from..to) {
            val ax = (r.lon[j] - l.longitude) * k
            val ay = r.lat[j] - l.latitude
            val dx = (r.lon[j + 1] - r.lon[j]) * k
            val dy = r.lat[j + 1] - r.lat[j]
            val l2 = dx * dx + dy * dy
            val t = if (l2 == 0.0) 0.0 else max(0.0, min(1.0, -(ax * dx + ay * dy) / l2))
            val d = hypot(ax + t * dx, ay + t * dy) * 111320.0
            if (d < bestD) {
                bestD = d
                bestJ = j
                bestAlong = r.cum[j] + t * (r.cum[j + 1] - r.cum[j])
            }
        }
        val tolerance = max(35.0, l.accuracy * 1.5)
        val onRoute = bestD <= tolerance
        if (onRoute) {
            offCount = 0
            // Permit real reverse travel along the route; otherwise progress can stay near
            // the destination forever after a U-turn and falsely announce arrival.
            var movingAgainstRoute = false
            if (l.hasBearing() && l.hasSpeed() && l.speed > 3f && bestJ < n - 1) {
                val routeBearing = FloatArray(3)
                Location.distanceBetween(r.lat[bestJ], r.lon[bestJ],
                    r.lat[bestJ + 1], r.lon[bestJ + 1], routeBearing)
                val delta = kotlin.math.abs((l.bearing - routeBearing[0] + 540f) % 360f - 180f)
                movingAgainstRoute = delta > 115f
            }
            if (bestAlong >= along - 20.0) {
                progressIdx = bestJ
                along = max(along, bestAlong)
            } else if (movingAgainstRoute) {
                progressIdx = bestJ
                along = bestAlong
                // Maneuvers passed earlier are ahead again after reversing; allow them to be announced anew.
                for (i in announced.indices) {
                    if (r.cum[r.steps[i].pointIdx] > along + 50.0) announced[i] = 0
                }
            }
        } else {
            offCount++
            val now = System.currentTimeMillis()
            if (offCount >= 3 && now - lastReroute > 8000 && !busy) {
                lastReroute = now
                offCount = 0
                speak("Перестраиваю маршрут")
                planTo(destLat, destLon, destTitle, l.latitude, l.longitude, silent = true)
            }
        }
        updateGuidance(l)
        if (onRoute && r.distance - bestAlong < 25.0) {
            if (connEnd > 50.0) {
                val message = "Маршрут по дорогам завершён. До точки назначения ещё ${Geo.fmtDist(connEnd)} по прямой"
                speak(message)
                toast(message)
            } else {
                speak("Вы прибыли в пункт назначения")
                toast("Вы прибыли")
            }
            stop()
            return
        }
        if (follow) followCamera(l)
    }

    private fun followCamera(l: Location) {
        val m = map() ?: return
        val v = if (l.hasSpeed()) l.speed else 0f
        val zoom = when {
            v < 8f -> 17.0
            v < 17f -> 16.2
            v < 25f -> 15.5
            else -> 14.9
        }
        val bearing = if (l.hasBearing() && v > 1.5f) l.bearing.toDouble() else m.cameraPosition.bearing
        m.easeCamera(CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder().target(LatLng(l.latitude, l.longitude)).zoom(zoom).bearing(bearing).tilt(40.0).build()), 800)
    }

    // ------------------------------------------------------------------ guidance UI + voice
    private fun showPanel(preview: Boolean) {
        navBottom.visibility = View.VISIBLE
        navTop.visibility = if (preview) View.GONE else View.VISIBLE
        btnGo.visibility = if (preview) View.VISIBLE else View.GONE
        btnCancel.text = if (preview) "Отмена" else "Завершить"
        val r = route ?: return
        updateStats(r.distance - along, r.remainingSeconds(along))
        if (preview) navSub.text = previewSub()
    }

    private fun previewSub(): String {
        val note = StringBuilder(destTitle)
        if (connStart > 50) note.append("  ·  от вас до дороги ").append(Geo.fmtDist(connStart))
        if (connEnd > 50) note.append("  ·  от дороги до точки ").append(Geo.fmtDist(connEnd))
        return note.toString()
    }

    private fun updateStats(rest: Double, seconds: Double) {
        val eta = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(System.currentTimeMillis() + (seconds * 1000).toLong()))
        navStat.text = fmtDuration(seconds) + "  ·  " + Geo.fmtDist(rest)
        if (navigating) navSub.text = "Прибытие в $eta"
        else navSub.text = previewSub() + "  ·  прибытие в $eta"
    }

    private fun updateGuidance(l: Location?) {
        val r = route ?: return
        val rest = max(0.0, r.distance - along)
        updateStats(rest, r.remainingSeconds(along))
        while (stepIdx > 1 && r.cum[r.steps[stepIdx - 1].pointIdx] > along + 8.0) stepIdx--
        while (stepIdx < r.steps.size - 1 && r.cum[r.steps[stepIdx].pointIdx] <= along + 8.0) stepIdx++
        val st = r.steps[min(stepIdx, r.steps.size - 1)]
        val dist = max(0.0, r.cum[st.pointIdx] - along)
        navArrow.text = arrow(st.type)
        navDist.text = Geo.fmtDist(if (dist < 1000) (dist / 10).roundToInt() * 10.0 else dist)
        navText.text = st.text
        if (l == null) return
        val speed = if (l.hasSpeed()) l.speed.toDouble() else 0.0
        val far = min(1000.0, max(350.0, speed * 30.0))
        val near = if (speed > 14) 220.0 else 120.0
        val i = min(stepIdx, r.steps.size - 1)
        if (st.type == Maneuver.ARRIVE) return
        if (dist <= near && announced[i] and 2 == 0) {
            announced[i] = announced[i] or 3
            speak(if (dist > 60) "Через ${spokenDist(dist)}, ${lower(st.text)}" else st.text)
        } else if (dist <= far && announced[i] and 1 == 0 && announced[i] and 2 == 0) {
            announced[i] = announced[i] or 1
            speak("Через ${spokenDist(dist)}, ${lower(st.text)}")
        }
    }

    private fun describe(r: Route, st: Step) = "Через ${spokenDist(r.cum[st.pointIdx])}, ${lower(st.text)}"

    private fun lower(t: String) = if (t.isEmpty()) t else t.substring(0, 1).lowercase() + t.substring(1)

    private fun arrow(type: Int) = when (type) {
        Maneuver.LEFT -> "↰"
        Maneuver.RIGHT -> "↱"
        Maneuver.SLIGHT_LEFT -> "↖"
        Maneuver.SLIGHT_RIGHT -> "↗"
        Maneuver.SHARP_LEFT -> "↙"
        Maneuver.SHARP_RIGHT -> "↘"
        Maneuver.UTURN -> "↶"
        Maneuver.ROUNDABOUT -> "⟳"
        Maneuver.ARRIVE -> "⚑"
        else -> "↑"
    }

    private fun spokenDist(m: Double): String {
        if (m < 1000) return "${max(50, (m / 50).roundToInt() * 50)} метров"
        val km = (m / 100).roundToInt() / 10.0
        if (km == km.toInt().toDouble()) {
            val n = km.toInt()
            val w = if (n % 10 == 1 && n % 100 != 11) "километр" else if (n % 10 in 2..4 && n % 100 !in 12..14) "километра" else "километров"
            return "$n $w"
        }
        return String.format(Locale("ru"), "%.1f километра", km)
    }

    private fun fmtDuration(sec: Double): String {
        val m = max(1, (sec / 60).roundToInt())
        return if (m < 60) "$m мин" else "${m / 60} ч ${m % 60} мин"
    }

    private fun initTts() {
        if (disposed || tts != null) return
        tts = TextToSpeech(act) { status ->
            if (disposed || act.isFinishing || act.isDestroyed) {
                tts?.shutdown(); tts = null
            } else if (status == TextToSpeech.SUCCESS) {
                val res = tts?.setLanguage(Locale("ru", "RU"))
                ttsReady = res != null && res >= TextToSpeech.LANG_AVAILABLE
                tts?.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                if (!ttsReady) act.runOnUiThread { toast("Русский голос не установлен: подсказки только на экране") }
                else pendingSpeech?.let { tts?.speak(it, TextToSpeech.QUEUE_FLUSH, null, "nav") }
                pendingSpeech = null
            }
        }
    }

    private fun speak(text: String) {
        if (disposed || !prefs.getBoolean("nav_voice", true)) return
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav") else pendingSpeech = text
    }

    fun shutdown() {
        if (disposed) return
        disposed = true
        if (active) stop() // the Activity owns route progress; never leave an orphaned foreground navigation service
        tts?.stop(); tts?.shutdown(); tts = null
        ttsReady = false
        pendingSpeech = null
    }

    private fun toast(s: String) = Toast.makeText(act, s, Toast.LENGTH_SHORT).show()
}
