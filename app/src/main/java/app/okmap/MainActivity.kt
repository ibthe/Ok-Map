package app.okmap

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.graphics.RectF
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Xml
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.concurrent.thread
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var loading: View
    private lateinit var loadingText: TextView
    private lateinit var tvInfo: TextView
    private lateinit var etSearch: EditText
    private lateinit var lvResults: ListView
    private lateinit var prefs: SharedPreferences
    private lateinit var userDb: UserDb

    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var placeDb: PlaceDb? = null
    private var elevation: Elevation? = null
    private var tilesFile: File? = null

    private val ui = Handler(Looper.getMainLooper())
    private val errors = ArrayList<String>()
    private var searchResults: List<PlaceDb.Place> = emptyList()

    private var rulerOn = false
    private val rulerPts = ArrayList<LatLng>()
    private var shownTrackId = -1L

    private var myLoc: Location? = null
    private var following = false
    private var locActive = false
    private var pendingAfterPerm: (() -> Unit)? = null

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
            val ok = r[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                r[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            val action = pendingAfterPerm
            pendingAfterPerm = null
            if (ok) action?.invoke() else toast(getString(R.string.no_location))
        }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
            if (uri != null) writeGpx(uri)
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) readGpx(uri)
        }

    // ============================================================ lifecycle

    /** "auto" follows the system, "light" / "dark" force the app UI and the map style. */
    private fun themeMode(): String {
        val sp = getSharedPreferences("settings", MODE_PRIVATE)
        val m = sp.getString("theme_mode", null)
        if (m != null) return m
        return if (sp.getBoolean("dark_map", false)) "dark" else "auto"   // migrate the old toggle
    }

    private fun applyThemeMode(m: String) {
        AppCompatDelegate.setDefaultNightMode(
            when (m) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    private fun isNight(): Boolean = when (themeMode()) {
        "dark" -> true
        "light" -> false
        else -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    /** One of 5 colour themes (UI + map), stored as an id; day/night is chosen separately by [themeMode]. */
    /** Extent of the installed map data (MBTiles "bounds" = west,south,east,north), so "whole map" fits any region. */
    private fun dataBounds(): LatLngBounds {
        val t = tilesFile ?: return DEFAULT_BOUNDS
        return try {
            val db = SQLiteDatabase.openDatabase(t.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            val b = db.use { d ->
                d.rawQuery("select value from metadata where name='bounds'", null).use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }
            val v = b?.split(",")?.map { it.trim().toDouble() }
            if (v != null && v.size == 4 && v[2] > v[0] && v[3] > v[1]) LatLngBounds.from(v[3], v[2], v[1], v[0]) else DEFAULT_BOUNDS
        } catch (e: Exception) {
            DEFAULT_BOUNDS
        }
    }

    private fun colorTheme(): String {
        val id = getSharedPreferences("settings", MODE_PRIVATE).getString("color_theme", "forest") ?: "forest"
        return if (COLOR_THEMES.any { it.id == id }) id else "forest"
    }

    private fun colorThemeStyle(): Int = when (colorTheme()) {
        "ocean" -> R.style.Theme_OkMap_Ocean
        "sand" -> R.style.Theme_OkMap_Sand
        "dusk" -> R.style.Theme_OkMap_Dusk
        "graphite" -> R.style.Theme_OkMap_Graphite
        else -> R.style.Theme_OkMap_Forest
    }

    /** Colour of a theme attribute as "#RRGGBB" (for map overlay layers). */
    private fun themeHex(attr: Int): String {
        val tv = android.util.TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return String.format("#%06X", tv.data and 0xFFFFFF)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyThemeMode(themeMode())
        setTheme(colorThemeStyle())
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("settings", MODE_PRIVATE)
        userDb = UserDb(this)
        loading = findViewById(R.id.loading)
        loadingText = findViewById(R.id.loadingText)
        tvInfo = findViewById(R.id.tvInfo)
        etSearch = findViewById(R.id.etSearch)
        lvResults = findViewById(R.id.lvResults)
        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)
        mapView.addOnDidFailLoadingMapListener { msg -> errors.add(msg ?: "?") }

        findViewById<View>(R.id.btnMenu).setOnClickListener { showMenu() }
        findViewById<View>(R.id.btnPoint).setOnClickListener {
            val c = map?.cameraPosition?.target
            if (c != null) addWaypointDialog(c)
        }
        findViewById<View>(R.id.btnLocation).setOnClickListener { withLocationPermission { startLocation(true) } }
        findViewById<View>(R.id.btnFit).setOnClickListener {
            map?.easeCamera(CameraUpdateFactory.newLatLngBounds(dataBounds(), 48), 700)
        }
        tvInfo.setOnClickListener {
            val cb = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("info", tvInfo.text))
            toast("Скопировано")
        }

        val btnClear = findViewById<View>(R.id.btnClear)
        btnClear.setOnClickListener { etSearch.setText(""); etSearch.requestFocus() }
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                btnClear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                runSearch(s?.toString() ?: "")
            }
        })
        lvResults.setOnItemClickListener { _, _, pos, _ ->
            val p = searchResults.getOrNull(pos) ?: return@setOnItemClickListener
            showSearchResult(p)
        }

        prepareData {
            val dir = filesDir
            tilesFile = File(dir, "map.mbtiles")
            File(dir, "places.db").takeIf { it.exists() }?.let {
                placeDb = try { PlaceDb(it.absolutePath) } catch (e: Exception) { errors.add("places: $e"); null }
            }
            File(dir, "relief.mbtiles").takeIf { it.exists() }?.let {
                elevation = try { Elevation(it.absolutePath) } catch (e: Exception) { errors.add("relief: $e"); null }
            }
            mapView.getMapAsync { setupMap(it) }
        }
    }

    override fun onStart() { super.onStart(); mapView.onStart(); if (locActive) startLocation(false) }
    override fun onResume() { super.onResume(); mapView.onResume(); scheduleTick() }
    override fun onPause() { mapView.onPause(); ui.removeCallbacks(tick); super.onPause() }
    override fun onStop() { stopLocationUpdates(); mapView.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onDestroy() { mapView.onDestroy(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    // ============================================================ data (assets -> files)

    private fun prepareData(onReady: () -> Unit) {
        val have = assets.list("")?.toSet() ?: emptySet()
        if ("map.mbtiles" !in have) {
            loading.visibility = View.VISIBLE
            loadingText.text = "В APK нет map.mbtiles — сборка карты не выполнилась"
            return
        }
        val todo = DATA_FILES.filter { it in have }
        for (n in DATA_FILES) if (n !in have) File(filesDir, n).delete()
        val version = BuildConfig.VERSION_CODE
        val ready = todo.all { File(filesDir, it).exists() } && prefs.getInt("data_version", -1) == version
        if (ready) { onReady(); return }

        loading.visibility = View.VISIBLE
        thread(name = "data-copy") {
            try {
                for (name in todo) copyAsset(name)
                prefs.edit().putInt("data_version", version).apply()
                runOnUiThread { loading.visibility = View.GONE; onReady() }
            } catch (e: Exception) {
                runOnUiThread { loadingText.text = "Ошибка: ${e.message}" }
            }
        }
    }

    private fun copyAsset(name: String) {
        val total = try { assets.openFd(name).use { it.length } } catch (e: Exception) { -1L }
        val tmp = File(filesDir, "$name.tmp")
        assets.open(name).use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                var copied = 0L
                var last = -1L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    copied += n
                    val mb = copied shr 20
                    if (mb != last) {
                        last = mb
                        val t = if (total > 0) "$mb / ${total shr 20} МБ" else "$mb МБ"
                        runOnUiThread { loadingText.text = getString(R.string.loading) + "\n$name: $t" }
                    }
                }
            }
        }
        val target = File(filesDir, name)
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) error("rename failed: $name")
    }

    // ============================================================ map & style

    private fun setupMap(m: MapLibreMap) {
        map = m
        m.setMinZoomPreference(4.0)
        m.setMaxZoomPreference(18.0)
        m.moveCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder().target(LatLng(42.2, 43.5)).zoom(6.6).build()
            )
        )
        m.addOnCameraIdleListener { updateInfo() }
        m.addOnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) following = false
        }
        m.addOnMapLongClickListener { ll -> addWaypointDialog(ll); true }
        m.addOnMapClickListener { ll ->
            if (rulerOn) { rulerPts.add(ll); refreshRuler(); true } else false
        }
        loadStyle()
    }

    private fun loadStyle() {
        val m = map ?: return
        val tiles = tilesFile ?: return
        val name = "style_" + colorTheme() + (if (isNight()) "_dark" else "") + ".json"
        val relief = File(filesDir, "relief.mbtiles")
        val obj = JSONObject(assets.open(name).bufferedReader().use { it.readText() })
        if (!relief.exists()) {
            obj.getJSONObject("sources").remove("relief")
            val src = obj.getJSONArray("layers")
            val dst = JSONArray()
            for (i in 0 until src.length()) {
                val l = src.getJSONObject(i)
                if (l.optString("id") != "relief-hillshade") dst.put(l)
            }
            obj.put("layers", dst)
        }
        val json = obj.toString()
            .replace("__MBTILES_URL__", "mbtiles://" + tiles.absolutePath)
            .replace("__RELIEF_URL__", "mbtiles://" + relief.absolutePath)
        m.setStyle(Style.Builder().fromJson(json)) { s ->
            style = s
            addOverlays(s)
            applyLabels(s)
            applyPoiFilter(s)
            applyRelief(s)
            refreshWaypoints()
            refreshTrack()
            refreshRuler()
            refreshMe()
            updateInfo()
        }
    }

    private fun enabledLanguages(): List<Lang> = Lang.values().filter { prefs.getBoolean(it.prefKey, true) }

    private fun applyLabels(s: Style) {
        val langs = enabledLanguages()
        val simple = prefs.getBoolean("labels_simple", false)
        for (layer in s.layers) {
            if (layer is SymbolLayer && layer.id.startsWith("lbl-")) {
                if (simple) {
                    layer.setProperties(
                        PropertyFactory.visibility(Property.VISIBLE),
                        PropertyFactory.textField(Expression.raw("[\"to-string\",[\"get\",\"name\"]]"))
                    )
                } else if (langs.isEmpty()) {
                    layer.setProperties(PropertyFactory.visibility(Property.NONE))
                } else {
                    layer.setProperties(
                        PropertyFactory.visibility(Property.VISIBLE),
                        PropertyFactory.textField(LabelExpr.build(langs, layer.id))
                    )
                }
            }
        }
    }

    private fun applyRelief(s: Style) {
        val on = prefs.getBoolean("relief_on", true)
        s.getLayer("relief-hillshade")?.setProperties(
            PropertyFactory.visibility(if (on) Property.VISIBLE else Property.NONE)
        )
    }

    private fun applyPoiFilter(s: Style) {
        val sel = prefs.getStringSet("poi_cats", emptySet()) ?: emptySet()
        val f: String
        val minZ: Float
        if (sel.isEmpty()) {
            f = "['all',['has','name'],['!',$TRANSPORT_EXPR]]"
            minZ = 15f
        } else {
            val vals = sel.flatMap { POI_CATS[it] ?: emptyList() }.distinct().joinToString(",") { "'$it'" }
            f = "['all',['has','name'],['!',$TRANSPORT_EXPR]," +
                "['any',['match',['get','class'],[$vals],true,false],['match',['get','subclass'],[$vals],true,false]]]"
            minZ = 12f
        }
        val e = Expression.raw(f.replace('\'', '"'))
        s.getLayerAs<CircleLayer>("poi-dot")?.let { it.setFilter(e); it.setMinZoom(minZ) }
        s.getLayerAs<SymbolLayer>("lbl-poi")?.let { it.setFilter(e); it.setMinZoom(minZ) }
    }

    // ============================================================ overlays (points, tracks, ruler, me)

    private fun addOverlays(s: Style) {
        val night = isNight()
        s.addSource(GeoJsonSource("ovl-search"))
        s.addLayer(CircleLayer("ovl-search-c", "ovl-search").withProperties(
            PropertyFactory.circleRadius(9f), PropertyFactory.circleColor(themeHex(R.attr.gCrosshair)),
            PropertyFactory.circleStrokeColor("#ffffff"), PropertyFactory.circleStrokeWidth(3f)))

        s.addSource(GeoJsonSource("ovl-track"))
        s.addLayer(LineLayer("ovl-track-l", "ovl-track").withProperties(
            PropertyFactory.lineColor(if (night) "#7C9CFF" else "#2F4FD8"), PropertyFactory.lineWidth(4f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))

        s.addSource(GeoJsonSource("ovl-ruler"))
        s.addLayer(LineLayer("ovl-ruler-l", "ovl-ruler").withProperties(
            PropertyFactory.lineColor(if (night) "#FFB066" else "#E0701A"), PropertyFactory.lineWidth(3f),
            PropertyFactory.lineDasharray(arrayOf(2f, 1.5f))))
        s.addSource(GeoJsonSource("ovl-ruler-pts"))
        s.addLayer(CircleLayer("ovl-ruler-c", "ovl-ruler-pts").withProperties(
            PropertyFactory.circleRadius(5f), PropertyFactory.circleColor(if (night) "#FFB066" else "#E0701A"),
            PropertyFactory.circleStrokeColor("#ffffff"), PropertyFactory.circleStrokeWidth(2f)))

        s.addSource(GeoJsonSource("ovl-wpt"))
        s.addLayer(CircleLayer("ovl-wpt-c", "ovl-wpt").withProperties(
            PropertyFactory.circleRadius(7f), PropertyFactory.circleColor(if (night) "#FF7FB0" else "#C2185B"),
            PropertyFactory.circleStrokeColor("#ffffff"), PropertyFactory.circleStrokeWidth(2f)))
        s.addLayer(SymbolLayer("ovl-wpt-t", "ovl-wpt").withProperties(
            PropertyFactory.textField(Expression.get("name")),
            PropertyFactory.textFont(arrayOf("NotoSans")),
            PropertyFactory.textSize(13f),
            PropertyFactory.textOffset(arrayOf(0f, 1.1f)),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
            PropertyFactory.textColor(if (night) "#FFC2D9" else "#7A0F43"),
            PropertyFactory.textHaloColor(if (night) "#10141A" else "#FFFFFF"),
            PropertyFactory.textHaloWidth(1.5f)))

        s.addSource(GeoJsonSource("ovl-me"))
        s.addLayer(CircleLayer("ovl-me-c", "ovl-me").withProperties(
            PropertyFactory.circleRadius(8f), PropertyFactory.circleColor(if (night) "#6FA0FF" else "#2563EB"),
            PropertyFactory.circleStrokeColor("#ffffff"), PropertyFactory.circleStrokeWidth(3f)))
    }

    private fun src(id: String): GeoJsonSource? = style?.getSourceAs<GeoJsonSource>(id)

    private fun refreshWaypoints() {
        val w = userDb.waypoints()
        src("ovl-wpt")?.setGeoJson(Geo.pointFc(w.map { LatLng(it.lat, it.lon) }, w.map { it.name }))
    }

    private fun refreshTrack() {
        val id = if (TrackService.running) prefs.getLong("rec_track", 0L) else shownTrackId
        val pts = if (id > 0) userDb.trackLatLng(id) else emptyList()
        src("ovl-track")?.setGeoJson(Geo.lineFc(pts))
    }

    private fun refreshRuler() {
        src("ovl-ruler")?.setGeoJson(Geo.lineFc(rulerPts))
        src("ovl-ruler-pts")?.setGeoJson(Geo.pointFc(rulerPts))
        updateInfo()
    }

    private fun refreshMe() {
        val l = myLoc
        src("ovl-me")?.setGeoJson(Geo.pointFc(if (l != null) listOf(LatLng(l.latitude, l.longitude)) else emptyList()))
    }

    private val tick = object : Runnable {
        override fun run() {
            if (TrackService.running) {
                refreshTrack()
                updateInfo()
                ui.postDelayed(this, 5000)
            }
        }
    }

    private fun scheduleTick() {
        ui.removeCallbacks(tick)
        if (TrackService.running) ui.postDelayed(tick, 1000)
    }

    private fun updateInfo() {
        val m = map ?: return
        val c = m.cameraPosition.target ?: return
        val sb = StringBuilder(Geo.fmtCoord(c.latitude, c.longitude))
        elevation?.at(c.latitude, c.longitude)?.let { sb.append("  ·  ≈ $it м") }
        sb.append("  ·  z").append(String.format(Locale.US, "%.1f", m.cameraPosition.zoom))
        myLoc?.let {
            sb.append("\nGPS: ±").append(it.accuracy.roundToInt()).append(" м")
            if (it.hasAltitude()) sb.append(", высота ").append(it.altitude.roundToInt()).append(" м")
        } ?: run { if (locActive) sb.append("\nGPS: ожидание сигнала…") }
        if (rulerOn) sb.append("\nЛиния: ").append(Geo.fmtDist(Geo.length(rulerPts)))
            .append(" (тап — новая точка)")
        if (TrackService.running) sb.append("\n● REC трек пишется")
        tvInfo.text = sb.toString()
    }

    // ============================================================ search

    private fun runSearch(q: String) {
        if (q.trim().length < 2) {
            lvResults.visibility = View.GONE
            return
        }
        val db = placeDb
        if (db == null) {
            toast("Индекс поиска не найден в APK")
            return
        }
        searchResults = db.search(q)
        val items = searchResults.map { p ->
            val names = listOf(p.ka, p.ru, p.en).filter { it.isNotEmpty() }.distinct().joinToString(" / ")
            val extra = kindLabel(p.kind) + if (p.ele > 0) " · ${p.ele} м" else ""
            names to extra
        }
        lvResults.adapter = ResultsAdapter(items)
        val dp = resources.displayMetrics.density
        lvResults.layoutParams = lvResults.layoutParams.apply {
            height = minOf((items.size * 64 * dp).roundToInt(), (320 * dp).roundToInt())
        }
        lvResults.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    private inner class ResultsAdapter(private val items: List<Pair<String, String>>) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_result, parent, false)
            v.findViewById<TextView>(R.id.resTitle).text = items[position].first
            v.findViewById<TextView>(R.id.resSub).text = items[position].second
            return v
        }
    }

    private fun showSearchResult(p: PlaceDb.Place) {
        val ll = LatLng(p.lat, p.lon)
        src("ovl-search")?.setGeoJson(Geo.pointFc(listOf(ll)))
        val z = when (p.kind) {
            "city" -> 11.5; "town" -> 12.5; "peak" -> 13.0; "aerodrome" -> 13.0
            "village", "hamlet", "suburb" -> 13.5
            else -> 15.5
        }
        map?.easeCamera(CameraUpdateFactory.newLatLngZoom(ll, z), 900)
        lvResults.visibility = View.GONE
        etSearch.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(etSearch.windowToken, 0)
    }

    private fun kindLabel(k: String): String = KIND_RU[k] ?: k

    // ============================================================ location

    private fun hasLocationPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun withLocationPermission(action: () -> Unit) {
        if (hasLocationPermission()) { action(); return }
        pendingAfterPerm = action
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        permLauncher.launch(perms.toTypedArray())
    }

    private val locListener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            val first = myLoc == null
            myLoc = l
            refreshMe()
            if ((first || following) && map != null) {
                val z = maxOf(map!!.cameraPosition.zoom, 14.0)
                map?.easeCamera(CameraUpdateFactory.newLatLngZoom(LatLng(l.latitude, l.longitude), z), 600)
                following = true
            }
            updateInfo()
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    @SuppressLint("MissingPermission")
    private fun startLocation(userRequested: Boolean) {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val gps = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val net = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        if (!gps && !net) {
            MaterialAlertDialogBuilder(this)
                .setMessage("Геолокация выключена. Включите GPS в настройках телефона.")
                .setPositiveButton("Настройки") { _, _ -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                .setNegativeButton("Отмена", null).show()
            return
        }
        locActive = true
        if (userRequested) {
            following = true
            myLoc?.let {
                map?.easeCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude), 15.0), 600)
            }
        }
        var last: Location? = null
        try {
            lm.removeUpdates(locListener)
            if (gps) lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locListener, Looper.getMainLooper())
            if (net) lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 0f, locListener, Looper.getMainLooper())
            if (myLoc == null) {
                last = (if (gps) lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) else null)
                    ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
            }
        } catch (e: SecurityException) {
            toast(getString(R.string.no_location))
        }
        last?.let { locListener.onLocationChanged(it) }
        updateInfo()
    }

    private fun stopLocationUpdates() {
        try { (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(locListener) } catch (e: Exception) {}
    }

    // ============================================================ menu

    private infix fun String.act(a: () -> Unit): Pair<String, () -> Unit> = Pair(this, a)

    private fun showMenu() {
        val langs = enabledLanguages().joinToString("/") { it.name }.ifEmpty { "выкл" }
        val rec = TrackService.running
        val hasRelief = File(filesDir, "relief.mbtiles").exists()
        val themeName = when (themeMode()) { "light" -> "светлая"; "dark" -> "тёмная"; else -> "как в системе" }
        val ct = COLOR_THEMES.first { it.id == colorTheme() }
        // grouped by purpose: map, look, my data, tools, files, service
        val entries: List<Pair<String, () -> Unit>> = listOf(
            "Языки названий ($langs)" act { showLanguageDialog() },
            "Категории POI" act { showPoiDialog() },
            ("Рельеф: " + if (!hasRelief) "нет данных" else if (prefs.getBoolean("relief_on", true)) "вкл" else "выкл") act {
                prefs.edit().putBoolean("relief_on", !prefs.getBoolean("relief_on", true)).apply()
                style?.let { applyRelief(it) }
            },
            "Цветовая тема: ${ct.title}" act { showColorThemeDialog() },
            "Яркость: $themeName" act {
                val next = when (themeMode()) { "auto" -> "light"; "light" -> "dark"; else -> "auto" }
                prefs.edit().putString("theme_mode", next).apply()
                applyThemeMode(next)   // recreates the activity: UI and map style both switch
            },
            "Мои точки" act { showWaypoints() },
            (if (rec) "■ Остановить запись трека" else "● Начать запись трека") act { toggleRecording() },
            "Мои треки" act { showTracks() },
            ("Линейка: " + if (rulerOn) "выключить" else "включить") act { rulerOn = !rulerOn; if (!rulerOn) rulerPts.clear(); refreshRuler() },
            "Перейти к координатам" act { gotoCoordinatesDialog() },
            "Экспорт GPX (точки и треки)" act { exportLauncher.launch("okmap-" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) + ".gpx") },
            "Импорт GPX" act { importLauncher.launch(arrayOf("*/*")) },
            "Справка" act { showInfo() },
            "Диагностика" act { showDiagnostics() },
            ("Простые подписи (отладка): " + if (prefs.getBoolean("labels_simple", false)) "вкл" else "выкл") act {
                prefs.edit().putBoolean("labels_simple", !prefs.getBoolean("labels_simple", false)).apply()
                style?.let { applyLabels(it) }
            }
        )
        MaterialAlertDialogBuilder(this)
            .setItems(entries.map { it.first }.toTypedArray()) { _, which -> entries[which].second() }
            .show()
    }

    /** Five colour themes; the list shows a coloured dot with the theme's accent next to each name. */
    private fun showColorThemeDialog() {
        val cur = COLOR_THEMES.indexOfFirst { it.id == colorTheme() }
        val items = COLOR_THEMES.map {
            val sp = android.text.SpannableStringBuilder("●  ${it.title}\n${it.about}")
            sp.setSpan(android.text.style.ForegroundColorSpan(Color.parseColor(it.swatch)), 0, 1, 0)
            sp.setSpan(android.text.style.RelativeSizeSpan(1.25f), 0, 1, 0)
            sp.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 3, 3 + it.title.length, 0)
            sp.setSpan(android.text.style.RelativeSizeSpan(0.85f), 4 + it.title.length, sp.length, 0)
            sp as CharSequence
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Цветовая тема")
            .setSingleChoiceItems(items, cur) { d, which ->
                prefs.edit().putString("color_theme", COLOR_THEMES[which].id).apply()
                d.dismiss()
                recreate()   // UI theme and map style are applied together
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showLanguageDialog() {
        val all = Lang.values()
        val checked = BooleanArray(all.size) { prefs.getBoolean(all[it].prefKey, true) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_title)
            .setMultiChoiceItems(all.map { it.title }.toTypedArray(), checked) { _, which, isChecked ->
                prefs.edit().putBoolean(all[which].prefKey, isChecked).apply()
                style?.let { applyLabels(it) }
            }
            .setPositiveButton(android.R.string.ok, null).show()
    }

    private fun showPoiDialog() {
        val names = POI_CATS.keys.toList()
        val cur = (prefs.getStringSet("poi_cats", emptySet()) ?: emptySet()).toMutableSet()
        val checked = BooleanArray(names.size) { names[it] in cur }
        MaterialAlertDialogBuilder(this)
            .setTitle("Показывать на карте (ничего не выбрано — все с 15-го масштаба)")
            .setMultiChoiceItems(names.toTypedArray(), checked) { _, which, isChecked ->
                if (isChecked) cur.add(names[which]) else cur.remove(names[which])
                prefs.edit().putStringSet("poi_cats", HashSet(cur)).apply()
                style?.let { applyPoiFilter(it) }
            }
            .setPositiveButton(android.R.string.ok, null).show()
    }

    // ============================================================ waypoints

    private fun addWaypointDialog(ll: LatLng) {
        val et = EditText(this)
        et.setText("Точка ${userDb.waypoints().size + 1}")
        et.setSelectAllOnFocus(true)
        MaterialAlertDialogBuilder(this)
            .setTitle("Сохранить точку\n" + Geo.fmtCoord(ll.latitude, ll.longitude))
            .setView(et)
            .setPositiveButton("Сохранить") { _, _ ->
                userDb.addWaypoint(et.text.toString().ifBlank { "Точка" }, ll.latitude, ll.longitude)
                refreshWaypoints()
            }
            .setNegativeButton("Отмена", null).show()
    }

    private fun showWaypoints() {
        val list = userDb.waypoints()
        if (list.isEmpty()) { toast("Точек нет. Долгое нажатие на карту или кнопка «Точка»"); return }
        val items = list.map { "${it.name}\n${Geo.fmtCoord(it.lat, it.lon)}" }.toTypedArray()
        MaterialAlertDialogBuilder(this).setTitle("Мои точки").setItems(items) { _, i ->
            val w = list[i]
            MaterialAlertDialogBuilder(this).setTitle(w.name)
                .setItems(arrayOf("Перейти", "Удалить")) { _, a ->
                    if (a == 0) flyTo(w.lat, w.lon, 15.0)
                    else { userDb.deleteWaypoint(w.id); refreshWaypoints() }
                }.show()
        }.show()
    }

    private fun flyTo(lat: Double, lon: Double, zoom: Double) {
        following = false
        map?.easeCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), zoom), 800)
    }

    private fun gotoCoordinatesDialog() {
        val et = EditText(this)
        et.hint = "41.7151, 44.8271"
        MaterialAlertDialogBuilder(this).setTitle("Широта, долгота").setView(et)
            .setPositiveButton("Перейти") { _, _ ->
                val ll = Geo.parseCoord(et.text.toString())
                if (ll == null) toast("Не удалось разобрать координаты")
                else {
                    src("ovl-search")?.setGeoJson(Geo.pointFc(listOf(ll)))
                    flyTo(ll.latitude, ll.longitude, 14.0)
                }
            }
            .setNegativeButton("Отмена", null).show()
    }

    // ============================================================ tracks

    private fun toggleRecording() {
        if (TrackService.running) {
            stopService(Intent(this, TrackService::class.java))
            prefs.edit().putLong("rec_track", 0L).apply()
            ui.postDelayed({ refreshTrack(); updateInfo() }, 400)
            toast("Запись остановлена")
            return
        }
        withLocationPermission {
            val id = userDb.newTrack("Трек " + SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date()))
            prefs.edit().putLong("rec_track", id).apply()
            shownTrackId = id
            ContextCompat.startForegroundService(this, Intent(this, TrackService::class.java))
            startLocation(false)
            ui.postDelayed({ scheduleTick(); updateInfo() }, 800)
            toast("Запись трека началась")
        }
    }

    private fun showTracks() {
        val list = userDb.tracks()
        if (list.isEmpty()) { toast("Треков пока нет"); return }
        val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        val items = list.map {
            val len = Geo.length(userDb.trackLatLng(it.id))
            "${it.name}\n${fmt.format(Date(it.start))} · ${it.count} точек · ${Geo.fmtDist(len)}"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this).setTitle("Мои треки").setItems(items) { _, i ->
            val t = list[i]
            MaterialAlertDialogBuilder(this).setTitle(t.name)
                .setItems(arrayOf("Показать на карте", "Скрыть", "Удалить")) { _, a ->
                    when (a) {
                        0 -> { shownTrackId = t.id; refreshTrack(); fitTrack(t.id) }
                        1 -> { shownTrackId = -1L; refreshTrack() }
                        2 -> {
                            if (TrackService.running && prefs.getLong("rec_track", 0L) == t.id) toast("Сначала остановите запись")
                            else { userDb.deleteTrack(t.id); if (shownTrackId == t.id) shownTrackId = -1L; refreshTrack() }
                        }
                    }
                }.show()
        }.show()
    }

    private fun fitTrack(id: Long) {
        val pts = userDb.trackLatLng(id)
        if (pts.size < 2) return
        val b = LatLngBounds.Builder()
        pts.forEach { b.include(it) }
        try { map?.easeCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 80), 800) } catch (e: Exception) {}
    }

    // ============================================================ GPX

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun writeGpx(uri: Uri) {
        try {
            val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            val sb = StringBuilder()
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            sb.append("<gpx version=\"1.1\" creator=\"Ok Map\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
            for (w in userDb.waypoints()) {
                sb.append("<wpt lat=\"${w.lat}\" lon=\"${w.lon}\"><name>${esc(w.name)}</name></wpt>\n")
            }
            for (t in userDb.tracks()) {
                sb.append("<trk><name>${esc(t.name)}</name><trkseg>\n")
                for (p in userDb.trackPoints(t.id)) {
                    sb.append("<trkpt lat=\"${p.lat}\" lon=\"${p.lon}\">")
                    if (!p.ele.isNaN()) sb.append("<ele>${p.ele}</ele>")
                    if (p.ts > 0) sb.append("<time>${iso.format(Date(p.ts))}</time>")
                    sb.append("</trkpt>\n")
                }
                sb.append("</trkseg></trk>\n")
            }
            sb.append("</gpx>\n")
            contentResolver.openOutputStream(uri)?.use { it.write(sb.toString().toByteArray(Charsets.UTF_8)) }
            toast("GPX сохранён")
        } catch (e: Exception) {
            toast("Ошибка экспорта: ${e.message}")
        }
    }

    private fun readGpx(uri: Uri) {
        try {
            val p = Xml.newPullParser()
            var wpts = 0
            var trks = 0
            contentResolver.openInputStream(uri)?.use { stream ->
                p.setInput(stream, null)
                var inWpt = false
                var inTrk = false
                var inPt = false
                var lat = 0.0
                var lon = 0.0
                var ele = Double.NaN
                var name = ""
                var trkName = "Импорт GPX"
                val pts = ArrayList<UserDb.Pt>()
                var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG) {
                        when (p.name) {
                            "wpt" -> { inWpt = true; name = ""; lat = p.getAttributeValue(null, "lat").toDouble(); lon = p.getAttributeValue(null, "lon").toDouble() }
                            "trk" -> { inTrk = true; trkName = "Импорт GPX"; pts.clear() }
                            "trkpt" -> { inPt = true; ele = Double.NaN; lat = p.getAttributeValue(null, "lat").toDouble(); lon = p.getAttributeValue(null, "lon").toDouble() }
                            "name" -> { val t = p.nextText(); if (inWpt) name = t else if (inTrk && !inPt) trkName = t }
                            "ele" -> { val t = p.nextText(); if (inPt) ele = t.toDoubleOrNull() ?: Double.NaN }
                        }
                    } else if (ev == XmlPullParser.END_TAG) {
                        when (p.name) {
                            "wpt" -> { userDb.addWaypoint(name.ifBlank { "Точка" }, lat, lon); inWpt = false; wpts++ }
                            "trkpt" -> { pts.add(UserDb.Pt(lat, lon, ele, 0L)); inPt = false }
                            "trk" -> {
                                if (pts.isNotEmpty()) { val id = userDb.newTrack(trkName); userDb.addPoints(id, pts); trks++ }
                                inTrk = false
                            }
                        }
                    }
                    ev = p.next()
                }
            }
            refreshWaypoints()
            toast("Импортировано: точек $wpts, треков $trks")
        } catch (e: Exception) {
            toast("Ошибка импорта: ${e.message}")
        }
    }

    // ============================================================ phrasebook, info, diagnostics

    private fun showInfo() {
        val msg = "Экстренные службы: 112 (единый номер).\n\n" +
            "Карта, поиск и рельеф работают без интернета. GPS тоже работает без сети.\n\n" +
            "Горные дороги и перевалы (Омало, Ушгули, Абано и др.) зимой могут быть закрыты — на карте это не отражено. " +
            "Перед выездом уточняйте обстановку на месте.\n\n" +
            "Для долгой записи трека разрешите приложению работу в фоне и не закрывайте уведомление.\n\n" +
            "Данные: © участники OpenStreetMap (ODbL), схема OpenMapTiles, Natural Earth, рельеф — AWS Terrain Tiles. " +
            "Данные актуальны на дату сборки APK."
        MaterialAlertDialogBuilder(this).setTitle("Справка").setMessage(msg).setPositiveButton("OK", null).show()
    }

    private fun showDiagnostics() {
        val sb = StringBuilder()
        val t = tilesFile
        if (t != null && t.exists()) {
            sb.append("map.mbtiles: ${t.length() shr 20} МБ\n")
            try {
                val db = SQLiteDatabase.openDatabase(t.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                db.rawQuery("select name,value from metadata where name in ('minzoom','maxzoom','bounds','format')", null).use {
                    while (it.moveToNext()) sb.append("  ${it.getString(0)} = ${it.getString(1)}\n")
                }
                val zs = StringBuilder()
                db.rawQuery("select zoom_level,count(*) from tiles group by 1 order by 1", null).use {
                    while (it.moveToNext()) zs.append("z${it.getInt(0)}:${it.getInt(1)} ")
                }
                sb.append("  тайлы: $zs\n")
                db.close()
            } catch (e: Exception) { sb.append("  ошибка чтения: $e\n") }
        } else sb.append("map.mbtiles: нет\n")
        sb.append("places.db: ").append(placeDb?.count()?.toString() ?: "нет").append(" мест\n")
        sb.append("relief: ").append(if (elevation != null) "есть" else "нет").append("\n")
        try {
            val files = assets.list("glyphs/NotoSans")?.size ?: 0
            sb.append("шрифты (glyphs) в APK: $files файлов\n")
            // a real font has many glyphs with ink in these ranges; a placeholder has almost none
            for ((f, name, need) in listOf(
                Triple("0-255", "латиница", 150), Triple("1024-1279", "кириллица", 200), Triple("4096-4351", "грузинский", 45)
            )) {
                val bytes = try { assets.open("glyphs/NotoSans/$f.pbf").use { it.readBytes() } } catch (e: Exception) { null }
                if (bytes == null) { sb.append("  $name: файла нет ✗\n"); continue }
                val ink = glyphsWithInk(bytes)
                sb.append("  $name ($f): $ink глифов ${if (ink >= need) "✓" else "✗ (заглушка, не шрифт)"}\n")
            }
        } catch (e: Exception) { sb.append("шрифты: ошибка $e\n") }

        val m = map
        val v = mapView
        if (m != null && style != null) {
            sb.append("\nОтрисованные объекты на экране:\n")
            val rect = RectF(0f, 0f, v.width.toFloat(), v.height.toFloat())
            for (id in listOf("water", "landcover-wood", "landuse-residential", "road-primary", "road-minor",
                "road-path", "building", "boundary-2", "boundary-4", "lbl-city", "lbl-town", "lbl-village", "lbl-peak", "lbl-road-major")) {
                val n = try { m.queryRenderedFeatures(rect, id).size } catch (e: Exception) { -1 }
                sb.append("  $id: $n\n")
            }
        }
        if (errors.isNotEmpty()) {
            sb.append("\nОшибки загрузки:\n")
            errors.takeLast(6).forEach { sb.append("  $it\n") }
        }
        MaterialAlertDialogBuilder(this).setTitle("Диагностика").setMessage(sb.toString())
            .setPositiveButton("OK", null).show()
    }

    /** Number of glyphs that have a bitmap in an SDF glyph file (glyphs.proto). */
    private fun glyphsWithInk(data: ByteArray): Int {
        var ink = 0
        try {
            forEachField(data, 0, data.size) { n, wt, _, s, e ->
                if (n == 1 && wt == 2) forEachField(data, s, e) { n2, w2, _, s2, e2 ->
                    if (n2 == 3 && w2 == 2) {
                        var width = 0L
                        var bitmap = false
                        forEachField(data, s2, e2) { n3, w3, v3, s3, e3 ->
                            if (n3 == 3 && w3 == 0) width = v3
                            if (n3 == 2 && w3 == 2 && e3 > s3) bitmap = true
                        }
                        if (width > 0 && bitmap) ink++
                    }
                }
            }
        } catch (e: Exception) { return -1 }
        return ink
    }

    private fun forEachField(b: ByteArray, from: Int, to: Int, cb: (Int, Int, Long, Int, Int) -> Unit) {
        var p = from
        fun vint(): Long {
            var r = 0L
            var shift = 0
            while (p < to) {
                val x = b[p++].toInt() and 0xFF
                r = r or ((x and 0x7F).toLong() shl shift)
                if ((x and 0x80) == 0) break
                shift += 7
            }
            return r
        }
        while (p < to) {
            val key = vint().toInt()
            val num = key ushr 3
            when (key and 7) {
                0 -> cb(num, 0, vint(), 0, 0)
                2 -> { val len = vint().toInt(); cb(num, 2, 0L, p, p + len); p += len }
                1 -> p += 8
                5 -> p += 4
                else -> return
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        private val DATA_FILES = listOf("map.mbtiles", "relief.mbtiles", "places.db")
        // transport POIs have their own always-visible layers (tr-*, lbl-station ...); keep them out of the generic POI layers
        private const val TR_LIST = "'station','halt','subway','tram_stop','bus_station','bus_stop','ferry_terminal','harbor'"
        private const val TRANSPORT_EXPR = "['any',['match',['get','class'],[$TR_LIST],true,false]," +
            "['match',['get','subclass'],[$TR_LIST],true,false]]"
        // used only if the tile file has no "bounds" metadata
        private val DEFAULT_BOUNDS = LatLngBounds.from(43.65, 46.8, 41.0, 40.0)

        private data class ColorTheme(val id: String, val title: String, val about: String, val swatch: String)

        private val COLOR_THEMES = listOf(
            ColorTheme("forest", "Тайга", "спокойный зелёный, как лес", "#2A7553"),
            ColorTheme("ocean", "Лагуна", "прохладная сине-бирюзовая гамма", "#1F6A98"),
            ColorTheme("sand", "Песок", "тёплая «бумажная» гамма для чтения", "#A84D29"),
            ColorTheme("dusk", "Сумерки", "мягкий лавандовый для вечера", "#6450AE"),
            ColorTheme("graphite", "Графит", "нейтральная серая, минимум отвлечения", "#A8531A")
        )

        private val POI_CATS: Map<String, List<String>> = linkedMapOf(
            "АЗС" to listOf("fuel", "charging_station"),
            "Аптеки" to listOf("pharmacy", "chemist"),
            "Больницы и врачи" to listOf("hospital", "clinic", "doctors", "dentist"),
            "Банки и банкоматы" to listOf("bank", "atm"),
            "Магазины" to listOf("shop", "supermarket", "convenience", "grocery", "bakery", "marketplace", "mall", "department_store"),
            "Еда и кафе" to listOf("restaurant", "cafe", "fast_food", "bar", "pub", "food_and_drink", "food_court"),
            "Ночлег" to listOf("lodging", "hotel", "hostel", "guest_house", "motel", "camp_site", "campsite",
                "caravan_site", "alpine_hut", "wilderness_hut", "chalet", "apartments"),
            "Вода и родники" to listOf("drinking_water", "spring", "water_point", "water_tap"),
            "Туалеты" to listOf("toilets"),
            "Парковки" to listOf("parking"),
            "Транспорт" to listOf("bus", "bus_station", "bus_stop", "railway", "station", "halt", "subway", "tram_stop",
                "taxi", "aerodrome", "ferry_terminal", "harbor", "helipad"),
            "Достопримечательности" to listOf("attraction", "viewpoint", "museum", "monument", "castle", "ruins",
                "church", "monastery", "place_of_worship", "waterfall", "memorial", "archaeological_site", "information"),
            "Полиция и экстренные" to listOf("police", "fire_station", "emergency")
        )

        private val KIND_RU: Map<String, String> = mapOf(
            "city" to "город", "town" to "город/посёлок", "village" to "село", "hamlet" to "хутор",
            "suburb" to "район", "quarter" to "квартал", "neighbourhood" to "микрорайон",
            "isolated_dwelling" to "усадьба", "island" to "остров", "state" to "область",
            "province" to "область", "peak" to "вершина", "aerodrome" to "аэропорт", "water" to "водоём",
            "fuel" to "АЗС", "pharmacy" to "аптека", "hospital" to "больница", "restaurant" to "ресторан",
            "cafe" to "кафе", "hotel" to "отель", "guest_house" to "гостевой дом", "hostel" to "хостел",
            "camp_site" to "кемпинг", "bank" to "банк", "atm" to "банкомат", "parking" to "парковка",
            "viewpoint" to "смотровая", "museum" to "музей", "castle" to "замок", "monastery" to "монастырь",
            "place_of_worship" to "храм", "supermarket" to "супермаркет", "drinking_water" to "питьевая вода",
            "spring" to "родник", "toilets" to "туалет", "police" to "полиция",
            "station" to "ж/д вокзал, станция", "halt" to "ж/д платформа", "subway" to "метро",
            "tram_stop" to "трамвайная остановка", "bus_station" to "автовокзал", "bus_stop" to "автобусная остановка",
            "ferry_terminal" to "паромный терминал", "harbor" to "порт", "helipad" to "вертолётная площадка",
            "taxi" to "такси"
        )
    }
}
