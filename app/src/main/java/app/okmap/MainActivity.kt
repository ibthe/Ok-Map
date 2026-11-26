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
import android.widget.LinearLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.view.Gravity
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
import org.maplibre.android.style.layers.FillExtrusionLayer
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
import java.util.concurrent.Executors
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
    private val searchWorker = Executors.newSingleThreadExecutor()
    private var searchToken = 0
    private var searchDebounce: Runnable? = null
    private var searchIndexWarned = false
    private var searchOverlayVisible = false

    private var rulerOn = false
    private val rulerPts = ArrayList<LatLng>()
    private var shownTrackId = -1L

    private var myLoc: Location? = null
    private lateinit var nav: Navigator
    private lateinit var compass: Compass
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

    /** "light" (day), "dim" (evening: between day and night) or "dark" (night). Old values ("auto") become day. */
    private fun themeMode(): String {
        val m = getSharedPreferences("settings", MODE_PRIVATE).getString("theme_mode", "light")
        return if (m == "dim" || m == "dark") m else "light"
    }

    private fun applyThemeMode(m: String) {
        AppCompatDelegate.setDefaultNightMode(if (m == "dark") AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
    }

    private fun isNight(): Boolean = themeMode() != "light"     // evening and night use the dark overlay colours

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

    private fun colorThemeStyle(): Int {
        val dim = themeMode() == "dim"
        return when (colorTheme()) {
            "ocean" -> if (dim) R.style.Theme_OkMap_Ocean_Dim else R.style.Theme_OkMap_Ocean
            "sand" -> if (dim) R.style.Theme_OkMap_Sand_Dim else R.style.Theme_OkMap_Sand
            "dusk" -> if (dim) R.style.Theme_OkMap_Dusk_Dim else R.style.Theme_OkMap_Dusk
            "graphite" -> if (dim) R.style.Theme_OkMap_Graphite_Dim else R.style.Theme_OkMap_Graphite
            "sunset" -> if (dim) R.style.Theme_OkMap_Sunset_Dim else R.style.Theme_OkMap_Sunset
            "mint" -> if (dim) R.style.Theme_OkMap_Mint_Dim else R.style.Theme_OkMap_Mint
            "contrast" -> if (dim) R.style.Theme_OkMap_Contrast_Dim else R.style.Theme_OkMap_Contrast
            else -> if (dim) R.style.Theme_OkMap_Forest_Dim else R.style.Theme_OkMap_Forest
        }
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

        nav = Navigator(this, { File(filesDir, "graph.bin").takeIf { it.exists() } }, { map }, { style }, prefs,
            { isNight() }, { onNavModeChanged() })
        compass = Compass(this, { map }, { style }, prefs, { myLoc?.takeIf { isFreshLocation(it) } }, { withLocationPermission { startLocation(false) } },
            { isNight() }, { onNavModeChanged() })
        findViewById<View>(R.id.btnCompass).setOnClickListener { if (compass.isOpen) compass.close() else compass.openLast() }
        onBackPressedDispatcher.addCallback(this, backToStopNav)
        findViewById<View>(R.id.btnRulerUndo).setOnClickListener { if (rulerPts.isNotEmpty()) rulerPts.removeAt(rulerPts.size - 1); refreshRuler() }
        findViewById<View>(R.id.btnRulerMode).setOnClickListener { rulerOn = !rulerOn; refreshRuler() }
        findViewById<View>(R.id.btnRulerDelete).setOnClickListener { rulerOn = false; rulerPts.clear(); refreshRuler() }
        findViewById<View>(R.id.btnMenu).setOnClickListener { showMenu() }
        findViewById<View>(R.id.btnRec).setOnClickListener { toggleRecording() }
        applyOpacity()
        updateRecButton()
        if (prefs.getBoolean("reopen_menu", false)) {     // a theme / brightness change recreated the screen: show the menu again
            prefs.edit().putBoolean("reopen_menu", false).apply()
            ui.postDelayed({ if (!isFinishing && !isDestroyed) showMenu() }, 300)
        }
        findViewById<View>(R.id.btnPoint).setOnClickListener {
            val c = map?.cameraPosition?.target
            if (c != null) addWaypointDialog(c)
        }
        findViewById<View>(R.id.btnLocation).setOnClickListener {
            if (nav.navigating) nav.recenter(myLoc?.takeIf { isFreshLocation(it) }) else withLocationPermission { startLocation(true) }
        }
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
            mapView.getMapAsync { if (!isFinishing && !isDestroyed) setupMap(it) }
        }
    }

    override fun onStart() { super.onStart(); mapView.onStart(); compass.resume(); if (locActive) startLocation(false) }
    override fun onResume() { super.onResume(); mapView.onResume(); scheduleTick() }
    override fun onPause() { mapView.onPause(); ui.removeCallbacks(tick); super.onPause() }
    override fun onStop() { compass.pause(); if (!nav.navigating) stopLocationUpdates(); mapView.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onDestroy() {
        ui.removeCallbacks(tick)
        pendingRouteTimeout?.let { ui.removeCallbacks(it) }
        pendingRouteTimeout = null
        pendingRoute = null
        stopLocationUpdates()
        locActive = false
        searchToken++
        searchDebounce?.let { ui.removeCallbacks(it) }
        searchWorker.shutdownNow()
        compass.close()
        nav.shutdown()
        try { placeDb?.close() } catch (_: Exception) {}
        try { elevation?.close() } catch (_: Exception) {}
        if (::userDb.isInitialized) userDb.close()
        mapView.onDestroy()
        super.onDestroy()
    }
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

        // The build embeds per-file hashes. Code-only APK updates no longer copy hundreds of MB again.
        val hashes = try {
            assets.open("data.version").bufferedReader().useLines { lines ->
                lines.mapNotNull { line ->
                    val split = line.indexOf('=')
                    if (split <= 0) null else line.substring(0, split) to line.substring(split + 1)
                }.toMap()
            }
        } catch (_: Exception) { emptyMap() }
        val useHashes = hashes.isNotEmpty() && todo.all { hashes[it] != null }
        val version = BuildConfig.VERSION_CODE
        val toCopy = if (useHashes) {
            todo.filter { name ->
                !File(filesDir, name).exists() || prefs.getString("asset_hash_$name", null) != hashes[name]
            }
        } else {
            if (todo.all { File(filesDir, it).exists() } && prefs.getInt("data_version", -1) == version) emptyList()
            else todo
        }
        if (toCopy.isEmpty()) { onReady(); return }

        loading.visibility = View.VISIBLE
        thread(name = "data-copy") {
            try {
                for (name in toCopy) copyAsset(name)
                val editor = prefs.edit()
                if (useHashes) toCopy.forEach { editor.putString("asset_hash_$it", hashes[it]) }
                else editor.putInt("data_version", version)
                editor.apply()
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    loading.visibility = View.GONE
                    onReady()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) loadingText.text = "Ошибка: ${e.message}"
                }
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
                        runOnUiThread { if (!isFinishing && !isDestroyed) loadingText.text = getString(R.string.loading) + "\n$name: $t" }
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
        m.uiSettings.isLogoEnabled = false            // MapLibre logo and the "i" attribution button are hidden;
        m.uiSettings.isAttributionEnabled = false     // the OpenStreetMap credit is in Menu -> Справка
        m.setMinZoomPreference(4.0)
        m.setMaxZoomPreference(18.0)
        m.moveCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder().target(LatLng(42.2, 43.5)).zoom(6.6).build()
            )
        )
        m.addOnCameraIdleListener { updateInfo() }
        m.addOnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) { following = false; nav.onUserGesture() }
        }
        m.addOnMapLongClickListener { ll -> showPlaceSheet(ll, null); true }
        m.addOnMapClickListener { ll ->
            if (rulerOn) {
                // tap on an existing point removes it, tap elsewhere adds a new one
                val hit = nearestRulerPoint(ll)
                if (hit >= 0) rulerPts.removeAt(hit) else rulerPts.add(ll)
                refreshRuler(); true
            } else false
        }
        loadStyle()
    }

    private fun mbtilesMinZoom(f: File): Int = try {
        val db = SQLiteDatabase.openDatabase(f.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        db.use { d -> d.rawQuery("select value from metadata where name='minzoom'", null).use { c -> if (c.moveToFirst()) c.getString(0).toInt() else 0 } }
    } catch (e: Exception) { 0 }

    private fun mapType(): String = prefs.getString("map_type", "scheme") ?: "scheme"

    /** "Высоты": contour lines (if contours.mbtiles exists) with labels on every 5th line, and all peaks / spot heights switched on. */
    private fun addElevationMode(style: JSONObject, layers: JSONArray) {
        val night = isNight()
        for (i in 0 until layers.length()) {
            val l = layers.getJSONObject(i)
            if (l.optString("id") == "peak-dot" || l.optString("id") == "lbl-peak") l.optJSONObject("layout")?.remove("visibility")
        }
        val file = File(filesDir, "contours.mbtiles")
        if (!file.exists()) return
        style.getJSONObject("sources").put("contours", JSONObject()
            .put("type", "vector").put("url", "mbtiles://" + file.absolutePath).put("minzoom", 9).put("maxzoom", 12))
        var font: JSONArray? = null
        var at = layers.length()
        for (i in 0 until layers.length()) {
            val l = layers.getJSONObject(i)
            if (font == null && l.optString("id") == "lbl-peak") font = l.optJSONObject("layout")?.optJSONArray("text-font")
            if (at == layers.length() && (l.optString("id").startsWith("tunnel") || l.optString("id").startsWith("road"))) at = i
        }
        val minor = if (night) "#d3a574" else "#a8693a"
        val major = if (night) "#e8bd88" else "#7d4a22"
        fun width(a: Double, b: Double) = JSONArray("[\"interpolate\",[\"linear\"],[\"zoom\"],9,$a,14,$b]")
        val defs = listOf(
            JSONObject().put("id", "contour-minor").put("type", "line").put("source", "contours").put("source-layer", "contour")
                .put("filter", JSONArray("[\"==\",[\"get\",\"idx\"],0]")).put("minzoom", 10)
                .put("layout", JSONObject().put("line-join", "round"))
                .put("paint", JSONObject().put("line-color", minor).put("line-opacity", 0.55).put("line-width", width(0.35, 0.9))),
            JSONObject().put("id", "contour-major").put("type", "line").put("source", "contours").put("source-layer", "contour")
                .put("filter", JSONArray("[\"==\",[\"get\",\"idx\"],1]"))
                .put("layout", JSONObject().put("line-join", "round"))
                .put("paint", JSONObject().put("line-color", major).put("line-opacity", 0.85).put("line-width", width(0.7, 1.7))),
            JSONObject().put("id", "contour-label").put("type", "symbol").put("source", "contours").put("source-layer", "contour")
                .put("filter", JSONArray("[\"==\",[\"get\",\"idx\"],1]")).put("minzoom", 11)
                .put("layout", JSONObject().put("symbol-placement", "line").put("symbol-spacing", 380)
                    .put("text-field", JSONArray("[\"to-string\",[\"get\",\"ele\"]]")).put("text-size", 10.5)
                    .put("text-max-angle", 30).apply { if (font != null) put("text-font", font) })
                .put("paint", JSONObject().put("text-color", major).put("text-halo-color", if (night) "rgba(16,19,24,0.5)" else "rgba(255,255,255,0.5)")
                    .put("text-halo-width", 1.0).put("text-halo-blur", 1.0))
        )
        // rebuild the array with the contour layers inserted below roads
        val copy = ArrayList<JSONObject>()
        for (i in 0 until layers.length()) copy.add(layers.getJSONObject(i))
        while (layers.length() > 0) layers.remove(0)
        for ((i, l) in copy.withIndex()) {
            if (i == at) defs.forEach { layers.put(it) }
            layers.put(l)
        }
        if (at >= copy.size) defs.forEach { layers.put(it) }
    }

    private fun loadStyle() {
        val m = map ?: return
        val tiles = tilesFile ?: return
        val mode = themeMode()
        val sat = mapType() == "sat"
        val topo = mapType() == "topo"
        val name = if (sat) "style_sat" + (if (mode == "dark") "_dark" else "") + ".json"
        else "style_" + colorTheme() + (when (mode) { "dark" -> "_dark"; "dim" -> "_dim"; else -> "" }) + ".json"
        val relief = File(filesDir, "relief.mbtiles")
        val imagery = File(filesDir, "sat.mbtiles")
        val obj = JSONObject(assets.open(name).bufferedReader().use { it.readText() })
        val src = obj.getJSONArray("layers")
        val dst = JSONArray()
        val hasImagery = sat && imagery.exists()
        val imageryMinZoom = if (hasImagery) mbtilesMinZoom(imagery) else 0
        for (i in 0 until src.length()) {
            val l = src.getJSONObject(i)
            val id = l.optString("id")
            if (id == "relief-hillshade" && !relief.exists()) continue
            if (id == "sat-imagery" && !hasImagery) continue
            if (id == "sat-imagery") l.put("minzoom", imageryMinZoom)
            // with real imagery the flat land / water fills would cover it: they stay only below the imagery's first zoom
            if (hasImagery && (l.optString("type") == "fill" || l.optString("type") == "background"))
                l.put("maxzoom", minOf(l.optDouble("maxzoom", 24.0), imageryMinZoom.toDouble()))
            dst.put(l)
        }
        if (topo) addElevationMode(obj, dst)
        obj.put("layers", dst)
        if (!relief.exists()) obj.getJSONObject("sources").remove("relief")
        if (!hasImagery) obj.getJSONObject("sources").remove("sat")
        val json = obj.toString()
            .replace("__MBTILES_URL__", "mbtiles://" + tiles.absolutePath)
            .replace("__RELIEF_URL__", "mbtiles://" + relief.absolutePath)
            .replace("__SAT_URL__", "mbtiles://" + imagery.absolutePath)
        val builder = Style.Builder().fromJson(json)
        for ((id, bmp) in MapIcons.all(this)) builder.withImage(id, bmp)
        m.setStyle(builder) { s ->
            if (!isFinishing && !isDestroyed) {
                style = s
                apply3dBuildings(s)
                if (prefs.getBoolean("map_3d", false) && !nav.active) set3dCamera(true)
                addOverlays(s)
                applyLabels(s)
                applyPoiFilter(s)
                applyTransport(s)
                applyRelief(s)
                refreshWaypoints()
                refreshTrack()
                refreshRuler()
                refreshMe()
                updateInfo()
            }
        }
    }

    private fun enabledLanguages(): List<Lang> = Lang.values().filter { prefs.getBoolean(it.prefKey, true) }

    private fun applyLabels(s: Style) {
        val langs = enabledLanguages()
        val simple = prefs.getBoolean("labels_simple", false)
        for (layer in s.layers) {
            if (layer is SymbolLayer && layer.id.startsWith("lbl-")) {
                // spot heights and summits belong to the elevation map type only
                val vis = if (layer.id == "lbl-peak" && mapType() != "topo") Property.NONE else Property.VISIBLE
                if (simple) {
                    layer.setProperties(
                        PropertyFactory.visibility(vis),
                        PropertyFactory.textField(Expression.raw("[\"to-string\",[\"get\",\"name\"]]"))
                    )
                } else if (langs.isEmpty()) {
                    layer.setProperties(PropertyFactory.visibility(Property.NONE))
                } else {
                    layer.setProperties(
                        PropertyFactory.visibility(vis),
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

    /** Adds offline 3D building extrusions from the OpenMapTiles building layer. */
    private fun apply3dBuildings(s: Style) {
        val id = "building-3d"
        if (!prefs.getBoolean("map_3d", false)) {
            if (s.getLayer(id) != null) s.removeLayer(id)
            return
        }
        if (s.getLayer(id) != null || s.getSource("openmaptiles") == null) return

        val layer = FillExtrusionLayer(id, "openmaptiles").apply {
            sourceLayer = "building"
            minZoom = 15f
            setFilter(Expression.all(
                Expression.has("render_height"),
                Expression.has("render_min_height")
            ))
            setProperties(
                PropertyFactory.fillExtrusionColor(
                    Color.parseColor(if (isNight()) "#687482" else "#C8BDAE")
                ),
                PropertyFactory.fillExtrusionHeight(Expression.get("render_height")),
                PropertyFactory.fillExtrusionBase(Expression.get("render_min_height")),
                PropertyFactory.fillExtrusionOpacity(0.94f),
                PropertyFactory.fillExtrusionVerticalGradient(true)
            )
        }
        // Keep the thin outline above the extrusion so roof/building edges remain legible.
        if (s.getLayer("building") != null) s.addLayerAbove(layer, "building")
        else s.addLayer(layer)
    }

    private fun set3dCamera(enabled: Boolean, zoomToBuildings: Boolean = false) {
        val m = map ?: return
        val current = m.cameraPosition
        val target = current.target ?: return
        val camera = CameraPosition.Builder()
            .target(target)
            .zoom(if (enabled && zoomToBuildings) maxOf(current.zoom, 15.5) else current.zoom)
            .bearing(current.bearing)
            .tilt(if (enabled) 55.0 else 0.0)
            .build()
        m.easeCamera(CameraUpdateFactory.newCameraPosition(camera), 750)
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
        nav.addLayers(s)
        compass.addLayers(s)
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
        nav.restoreRoute()
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
        updateRulerBar()
        updateInfo()
    }

    /** index of the ruler point within ~28 dp of the tap, or -1 */
    private fun nearestRulerPoint(ll: LatLng): Int {
        val pr = map?.projection ?: return -1
        val t = pr.toScreenLocation(ll)
        var best = -1
        var bestD = dp(28).toDouble()
        for ((i, p) in rulerPts.withIndex()) {
            val sp = pr.toScreenLocation(p)
            val d = Math.hypot((sp.x - t.x).toDouble(), (sp.y - t.y).toDouble())
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    /** Ruler bar: shown while there is a ruler. Editing: undo / done / delete. Finished: edit / delete. */
    private fun updateRulerBar() {
        val bar = findViewById<View>(R.id.rulerBar)
        bar.visibility = if (!searchOverlayVisible && (rulerOn || rulerPts.isNotEmpty())) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.rulerText).text =
            if (rulerPts.size < 2) "Линейка: нажимайте на карту" else "Линейка: " + Geo.fmtDist(Geo.length(rulerPts))
        findViewById<View>(R.id.btnRulerUndo).visibility = if (rulerOn && rulerPts.isNotEmpty()) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.btnRulerMode).text = if (rulerOn) "Готово" else "Изменить"
    }

    private fun refreshMe() {
        val l = myLoc?.takeIf { isFreshLocation(it) }
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
        updateRecButton()
        val m = map ?: return
        val c = m.cameraPosition.target ?: return
        val sb = StringBuilder(Geo.fmtCoord(c.latitude, c.longitude))
        elevation?.at(c.latitude, c.longitude)?.let { sb.append("  ·  ≈ $it м") }
        sb.append("  ·  z").append(String.format(Locale.US, "%.1f", m.cameraPosition.zoom))
        myLoc?.let {
            val source = when {
                !isFreshLocation(it) -> "Последняя позиция"
                it.provider == LocationManager.GPS_PROVIDER -> "GPS"
                else -> "Геолокация"
            }
            sb.append("\n").append(source).append(": ±").append(it.accuracy.roundToInt()).append(" м")
            if (it.hasAltitude()) sb.append(", высота ").append(it.altitude.roundToInt()).append(" м")
        } ?: run { if (locActive) sb.append("\nГеолокация: ожидание сигнала…") }
        if (rulerPts.size >= 2) sb.append("\nЛиния: ").append(Geo.fmtDist(Geo.length(rulerPts)))
            .append(" (тап — новая точка)")
        if (TrackService.running) sb.append("\n● REC трек пишется")
        tvInfo.text = sb.toString()
    }

    // ============================================================ search

    /** Temporarily clears map-only overlays while the full-width search list is open. */
    private fun setSearchOverlay(visible: Boolean) {
        searchOverlayVisible = visible
        findViewById<View>(R.id.tvInfo).visibility = if (visible) View.GONE else View.VISIBLE
        if (visible) findViewById<View>(R.id.rulerBar).visibility = View.GONE else updateRulerBar()
        findViewById<View>(R.id.rightControls).visibility =
            if (visible || compass.isOpen || nav.active) View.GONE else View.VISIBLE
    }

    private fun runSearch(q: String) {
        val query = q.trim()
        val token = ++searchToken
        searchDebounce?.let { ui.removeCallbacks(it) }
        searchDebounce = null
        if (query.length < 2) {
            searchResults = emptyList()
            lvResults.visibility = View.GONE
            setSearchOverlay(false)
            return
        }
        val db = placeDb
        if (db == null) {
            lvResults.visibility = View.GONE
            setSearchOverlay(false)
            if (!searchIndexWarned) {
                toast("Индекс поиска не найден в APK")
                searchIndexWarned = true
            }
            return
        }

        // LIKE substring search scans the offline index; keep that work off the UI thread.
        lvResults.visibility = View.GONE
        setSearchOverlay(false)
        val task = Runnable {
            searchDebounce = null
            searchWorker.execute {
                var found: List<PlaceDb.Place>? = null
                var failure: String? = null
                try { found = db.search(query) } catch (e: Exception) { failure = e.message ?: e.javaClass.simpleName }
                runOnUiThread {
                    if (token != searchToken || isFinishing || isDestroyed ||
                        etSearch.text.toString().trim() != query) return@runOnUiThread
                    if (failure != null) {
                        errors.add("search: $failure")
                        setSearchOverlay(false)
                        if (!searchIndexWarned) {
                            toast("Ошибка поиска: $failure")
                            searchIndexWarned = true
                        }
                        return@runOnUiThread
                    }
                    searchResults = found ?: emptyList()
                    val items = searchResults.map { p ->
                        val names = listOf(p.ka, p.ru, p.en).filter { it.isNotEmpty() }.distinct().joinToString(" / ")
                        val extra = kindLabel(p.kind) + if (p.ele > 0) " · ${p.ele} м" else ""
                        names to extra
                    }
                    lvResults.adapter = ResultsAdapter(items)
                    val density = resources.displayMetrics.density
                    val screenCap = (resources.displayMetrics.heightPixels - dp(240)).coerceAtLeast(dp(64))
                    lvResults.layoutParams = lvResults.layoutParams.apply {
                        height = minOf((items.size * 64 * density).roundToInt(), dp(320), screenCap)
                    }
                    lvResults.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
                    setSearchOverlay(items.isNotEmpty())
                }
            }
        }
        searchDebounce = task
        ui.postDelayed(task, 160)
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
        setSearchOverlay(false)
        etSearch.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(etSearch.windowToken, 0)
        showPlaceSheet(ll, p.ru.ifBlank { p.en.ifBlank { p.ka } })
    }

    private fun kindLabel(k: String): String = KIND_RU[k] ?: k

    // ============================================================ location

    private fun isFreshLocation(location: Location?): Boolean =
        location != null && System.currentTimeMillis() - location.time in 0..30_000L

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
            if (isFinishing || isDestroyed) return
            val previous = myLoc
            if (previous != null && (l.time < previous.time ||
                    (l.time == previous.time && l.accuracy >= previous.accuracy))) return
            val first = previous == null
            myLoc = l
            refreshMe()
            if (isFreshLocation(l)) compass.onLocation(l)
            if (isFreshLocation(l)) pendingRoute?.let { p ->
                pendingRoute = null
                pendingRouteTimeout?.let { ui.removeCallbacks(it) }
                pendingRouteTimeout = null
                nav.planTo(p.first.latitude, p.first.longitude, p.second, l.latitude, l.longitude)
            }
            if (nav.navigating) { if (isFreshLocation(l)) nav.onLocation(l); updateInfo(); return }
            if ((first || following) && isFreshLocation(l) && map != null) {
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
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        locActive = false
        if (!fine && !coarse) {
            toast(getString(R.string.no_location))
            return
        }

        // Approximate-only permission cannot use GPS_PROVIDER. Keep NETWORK_PROVIDER as a valid fallback.
        val gps = fine && runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
        val net = (fine || coarse) && runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
        if (!gps && !net) {
            MaterialAlertDialogBuilder(this)
                .setMessage("Геолокация выключена. Включите GPS или сетевое определение местоположения в настройках телефона.")
                .setPositiveButton("Настройки") { _, _ -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                .setNegativeButton("Отмена", null).show()
            return
        }
        if (userRequested) {
            following = true
            myLoc?.takeIf { isFreshLocation(it) }?.let {
                map?.easeCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude), 15.0), 600)
            }
        }

        try { lm.removeUpdates(locListener) } catch (_: Exception) {}
        var registered = false
        if (gps) {
            try {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locListener, Looper.getMainLooper())
                registered = true
            } catch (_: Exception) {}
        }
        if (net) {
            try {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 0f, locListener, Looper.getMainLooper())
                registered = true
            } catch (_: Exception) {}
        }
        if (!registered) {
            toast(getString(R.string.no_location))
            return
        }
        locActive = true

        if (myLoc == null) {
            fun lastKnown(provider: String): Location? = try { lm.getLastKnownLocation(provider) } catch (_: Exception) { null }
            val last = (if (gps) lastKnown(LocationManager.GPS_PROVIDER) else null)
                ?: (if (net) lastKnown(LocationManager.NETWORK_PROVIDER) else null)
                ?: lastKnown(LocationManager.PASSIVE_PROVIDER)
            last?.let { locListener.onLocationChanged(it) }
        }
        updateInfo()
    }

    private fun stopLocationUpdates() {
        try { (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(locListener) } catch (e: Exception) {}
    }

    // ============================================================ menu

    // ------------------------------------------------------------------ menu (bottom sheet with 5 focused tabs)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun themeColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun label(text: String, size: Float, attr: Int, medium: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(themeColor(attr))
        if (medium) typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(themeColor(R.attr.gStroke))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
    }

    /** Bottom sheets: a clearly visible top edge (outline + shadow), a dimmed map behind and a drag handle. */
    private fun styleSheet(sheet: com.google.android.material.bottomsheet.BottomSheetDialog) {
        sheet.window?.setDimAmount(0.5f)
        val v = sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        val r = dp(28).toFloat()
        v.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(themeColor(R.attr.gDialog))
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            setStroke(dp(2), themeColor(R.attr.gRim))
        }
        v.elevation = dp(24).toFloat()
    }

    private fun dragHandle() = View(this).apply {
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(3).toFloat(); setColor(themeColor(R.attr.gHint))
        }
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(14); topMargin = dp(2) }
    }

    /** One row: title (+ optional grey hint) on the left, current value and a chevron on the right. */
    private fun menuRow(title: String, value: String? = null, sub: String? = null, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(4), dp(8), dp(4), dp(8))
            val tv = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
            setOnClickListener { onClick() }
        }
        row.addView(titleBlock(title, sub, true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (value != null) row.addView(label(value, 14f, R.attr.gTextSecondary).apply { setPadding(dp(8), 0, dp(4), 0) })
        row.addView(label("›", 22f, R.attr.gHint).apply { setPadding(dp(8), 0, dp(4), 0) })
        return row
    }

    private fun titleBlock(title: String, sub: String?, enabled: Boolean): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(label(title, 16f, if (enabled) R.attr.gTextPrimary else R.attr.gHint))
        if (sub != null) col.addView(label(sub, 13f, R.attr.gTextSecondary).apply { setPadding(0, dp(2), 0, 0) })
        return col
    }

    private fun menuSwitch(title: String, checked: Boolean, enabled: Boolean = true, sub: String? = null, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        row.addView(titleBlock(title, sub, enabled), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val sw = com.google.android.material.materialswitch.MaterialSwitch(this).apply {
            isChecked = checked
            isEnabled = enabled
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }
        row.addView(sw)
        row.setOnClickListener { if (enabled) sw.toggle() }
        return row
    }

    /** Scheme / Satellite / Elevation. Satellite uses offline orthophoto tiles when bundled; Elevation = contour lines + all spot heights. */
    private fun mapTypeSwitch(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(12)) }
        val g = com.google.android.material.button.MaterialButtonToggleGroup(this).apply { isSingleSelection = true; isSelectionRequired = true }
        val types = listOf("scheme" to "Схема", "sat" to "Спутник", "topo" to "Высоты")
        val ids = types.map { View.generateViewId() }
        types.forEachIndexed { i, t ->
            g.addView(com.google.android.material.button.MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = ids[i]; text = t.second; textSize = 14f; isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
            })
        }
        g.check(ids[types.indexOfFirst { it.first == mapType() }.coerceAtLeast(0)])
        g.addOnButtonCheckedListener { _, id, checked ->
            val t = types[ids.indexOf(id)].first
            if (checked && t != mapType()) {
                prefs.edit().putString("map_type", t).apply()
                loadStyle()
                if (t == "sat" && !File(filesDir, "sat.mbtiles").exists() && !prefs.getBoolean("sat_hint", false)) {
                    prefs.edit().putBoolean("sat_hint", true).apply()
                    toast("Спутниковых снимков в приложении нет: показан спутниковый вид из данных карты и рельефа")
                }
                if (t == "topo" && !File(filesDir, "contours.mbtiles").exists())
                    toast("Данных линий высоты в этой сборке нет: показаны только отметки высот")
            }
        }
        box.addView(g, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return box
    }

    private fun note(text: String) = label(text, 13f, R.attr.gHint).apply { setPadding(dp(4), dp(12), dp(4), dp(4)) }

    /** Raised "key" button (same look as the on-map buttons). */
    private fun keyButton(text: String, primary: Boolean, height: Int = 56, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        gravity = Gravity.CENTER
        setTextAppearance(R.style.TextAppearance_G_Label)
        textSize = 16f
        setTextColor(themeColor(if (primary) R.attr.gOnAccent else R.attr.gTextPrimary))
        background = ContextCompat.getDrawable(this@MainActivity, if (primary) R.drawable.bg_btn_primary else R.drawable.bg_fab_pill)
        elevation = dp(3).toFloat()
        isClickable = true
        isFocusable = true
        minimumHeight = dp(height)
        setPadding(dp(12), 0, dp(12), dp(3))
        stateListAnimator = android.animation.AnimatorInflater.loadStateListAnimator(this@MainActivity, R.animator.press)
        setOnClickListener { onClick() }
    }

    // menu categories: one line-icon style, the name of the open page is shown as a heading
    private val MENU_TABS = listOf(
        R.drawable.ic_tab_map to "Карта", R.drawable.ic_tab_nav to "Навигация", R.drawable.ic_tab_places to "Мои места",
        R.drawable.ic_tab_theme to "Оформление", R.drawable.ic_tab_info to "Справка")

    private fun showMenu() {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val hasRelief = File(filesDir, "relief.mbtiles").exists()

        fun add(v: View, last: Boolean = false) { content.addView(v); if (!last) content.addView(divider()) }
        fun act(a: () -> Unit): () -> Unit = { sheet.dismiss(); a() }

        fun page(i: Int) {
            content.removeAllViews()
            prefs.edit().putInt("menu_tab", i).apply()
            when (i) {
                0 -> {   // MAP: what is drawn
                    val langs = enabledLanguages().joinToString(" ") { it.name }
                    add(mapTypeSwitch())
                    add(menuSwitch("3D-здания", prefs.getBoolean("map_3d", false), true,
                        "Объёмные здания по данным OSM; масштаб 15+") { on ->
                        prefs.edit().putBoolean("map_3d", on).apply()
                        style?.let { apply3dBuildings(it) }
                        set3dCamera(on, zoomToBuildings = on)
                    })
                    add(menuRow("Языки названий", langs, null, act { showLanguageDialog() }))
                    add(menuSwitch("Рельеф", prefs.getBoolean("relief_on", true) && hasRelief, hasRelief,
                        if (hasRelief) "Тени гор на карте" else "Нет данных рельефа") { on ->
                        prefs.edit().putBoolean("relief_on", on).apply(); style?.let { applyRelief(it) }
                    })
                    add(menuRow("Линейка", if (rulerPts.isEmpty()) null else Geo.fmtDist(Geo.length(rulerPts)),
                        "Измерить расстояние по точкам на карте", act { rulerOn = true; refreshRuler() }))
                    add(menuRow("Перейти к координатам", null, null, act { gotoCoordinatesDialog() }), true)
                    content.addView(note("АЗС, еда, вокзалы, остановки и другие места включаются кнопками над картой."))
                }
                1 -> {   // NAVIGATION: routes and driving preferences
                    add(menuRow("Маршрут к центру карты", null, "От вашего местоположения до выбранной точки", act {
                        map?.cameraPosition?.target?.let { startRoute(it, "Точка на карте") }
                    }))
                    add(menuSwitch("Голосовые подсказки", prefs.getBoolean("nav_voice", true), true,
                        "Телефон проговаривает повороты") { on -> prefs.edit().putBoolean("nav_voice", on).apply() })
                    add(menuSwitch("Объезжать грунтовки", prefs.getBoolean("nav_avoid_unpaved", true), true,
                        "Ехать по асфальту, если есть выбор") { on -> prefs.edit().putBoolean("nav_avoid_unpaved", on).apply() }, true)
                    content.addView(note("Чтобы проложить маршрут к месту: найдите его в поиске или нажмите на карту и выберите «Маршрут»."))
                }
                2 -> {   // MY PLACES: points, tracks, files; recording is available from the persistent map dock
                    add(menuRow("Мои точки", "${userDb.waypoints().size}", null, act { showWaypoints() }))
                    add(menuRow("Мои треки", "${userDb.tracks().size}", "Записанные пути", act { showTracks() }))
                    add(menuRow("Экспорт GPX", null, "Сохранить точки и треки в файл", act {
                        exportLauncher.launch("okmap-" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) + ".gpx")
                    }))
                    add(menuRow("Импорт GPX", null, "Загрузить точки и треки из файла", act { importLauncher.launch(arrayOf("*/*")) }), true)
                }
                3 -> appearancePage(content, sheet)
                else -> {  // HELP
                    add(menuRow("Справка", null, null, act { showInfo() }))
                    add(menuRow("Диагностика", null, "Размер данных и слои карты", act { showDiagnostics() }))
                    add(menuSwitch("Подписи одним языком", prefs.getBoolean("labels_simple", false), true,
                        "Включайте, только если пропали названия на карте") { on ->
                        prefs.edit().putBoolean("labels_simple", on).apply(); style?.let { applyLabels(it) }
                    }, true)
                    content.addView(note("Данные карты © участники OpenStreetMap (лицензия ODbL). Ортофото (режим «Спутник») © Национальное агентство публичного реестра Грузии. Ok Map работает без интернета."))
                }
            }
        }

        // tab bar: icons only (same size and stroke), the active tab is "pressed in" and tinted with the accent
        val heading = label("", 22f, R.attr.gTextPrimary, true).apply { setPadding(dp(4), 0, 0, dp(4)) }
        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        root.clipChildren = false
        val tabs = ArrayList<View>()
        fun selectTab(i: Int) { tabs.forEachIndexed { k, v -> v.isSelected = k == i }; heading.text = MENU_TABS[i].second; page(i) }
        MENU_TABS.forEachIndexed { i, (icon, name) ->
            val tab = ImageView(this).apply {
                setImageResource(icon)
                imageTintList = resources.getColorStateList(R.color.chip_text, theme)
                scaleType = ImageView.ScaleType.CENTER
                background = Skin.chip(this@MainActivity, 1f)
                stateListAnimator = android.animation.AnimatorInflater.loadStateListAnimator(this@MainActivity, R.animator.press)
                isClickable = true
                isFocusable = true
                contentDescription = name
                setOnClickListener { selectTab(i) }
            }
            tabs.add(tab)
            tabRow.addView(tab, LinearLayout.LayoutParams(0, dp(52), 1f).apply { if (i < MENU_TABS.size - 1) marginEnd = dp(8) })
        }
        root.addView(dragHandle())
        root.addView(tabRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(14) })
        root.addView(heading)
        // Keep every menu item reachable on compact screens; a long page scrolls inside the sheet
        // instead of extending underneath the system navigation area.
        val menuScroll = object : android.widget.ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val cap = minOf(dp(440), (resources.displayMetrics.heightPixels - dp(200)).coerceAtLeast(dp(96)))
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
            }
        }.apply {
            isFillViewport = true
            clipToPadding = false
            addView(content, android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        root.addView(menuScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        sheet.setContentView(root)
        styleSheet(sheet)
        sheet.behavior.skipCollapsed = true
        sheet.setOnShowListener { sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED }
        selectTab(prefs.getInt("menu_tab", 0).coerceIn(0, MENU_TABS.size - 1))
        sheet.show()
    }

    private fun uiOpacity(): Float = (uiOpacityPercent() / 100f)

    /** 35..100 in steps of 5 (a Slider crashes if its value is not valueFrom + n * stepSize) */
    private fun uiOpacityPercent(): Int {
        val pct = Math.round(prefs.getFloat("ui_opacity", 0.90f) * 100f / 5f) * 5
        return pct.coerceIn(35, 100)
    }

    /** Panel transparency: only the fill fades, the outline and the key edges stay, so panels stay visible over the map. */
    private fun opacityControl(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(20), 0, 0) }
        val title = label("Прозрачность панелей: ${uiOpacityPercent()}%", 14f, R.attr.gTextSecondary, true).apply { setPadding(dp(4), 0, 0, 0) }
        val sl = com.google.android.material.slider.Slider(this).apply {
            valueFrom = 35f; valueTo = 100f; stepSize = 5f; value = uiOpacityPercent().toFloat()
            addOnChangeListener { _, v, fromUser ->
                title.text = "Прозрачность панелей: ${v.roundToInt()}%"
                if (fromUser) { prefs.edit().putFloat("ui_opacity", v / 100f).apply(); applyOpacity() }   // live preview behind the sheet
            }
        }
        box.addView(title)
        box.addView(sl)
        return box
    }

    /** Re-draws panels and keys with the chosen fill opacity (outline stays opaque; text gets a soft glow when panels are see-through). */
    private fun applyOpacity() {
        val a = uiOpacity()
        fun panel(id: Int, r: Float) { findViewById<View>(id)?.background = Skin.panel(this, r, a) }
        fun round(id: Int, r: Float) { findViewById<View>(id)?.let { it.background = Skin.panel(this, r, a); Skin.shape(it, false, r, a) } }
        round(R.id.searchBar, 28f); round(R.id.rulerBar, 20f); round(R.id.navTop, 20f); round(R.id.navBottom, 20f)
        round(R.id.compassPanel, 20f); round(R.id.lvResults, 20f); round(R.id.tvInfo, 12f)
        for (id in listOf(R.id.btnFit, R.id.btnLocation, R.id.btnCompass, R.id.btnMenu, R.id.btnRec))
            findViewById<View>(id)?.let { it.background = Skin.key(this, true, 28f, a); Skin.shape(it, true, 28f, a) }
        for (id in listOf(R.id.btnNavCancel, R.id.btnCompassClose, R.id.btnRulerUndo, R.id.btnRulerDelete))
            findViewById<View>(id)?.let { it.background = Skin.key(this, false, 28f, a); Skin.shape(it, false, 28f, a) }
        for (id in listOf(R.id.btnCompassMap, R.id.chipNorth, R.id.chipQibla, R.id.chipPlace))
            findViewById<View>(id)?.let { it.background = Skin.chip(this, a); Skin.shape(it, false, 28f, a) }
        for (id in listOf(R.id.btnPoint, R.id.btnNavGo, R.id.btnRulerMode))
            findViewById<View>(id)?.let { it.background = Skin.key(this, false, 28f, 1f, accent = true); Skin.shape(it, false, 28f, 1f) }
        val glow = a < 0.8f
        val halo = themeColor(R.attr.gSurface)
        for (id in listOf(R.id.tvInfo, R.id.rulerText, R.id.navDist, R.id.navText, R.id.navStat, R.id.navSub, R.id.cTitle, R.id.cInfo, R.id.cGuide, R.id.cCal))
            findViewById<TextView>(id)?.setShadowLayer(if (glow) dp(4).toFloat() else 0f, 0f, 0f, halo)
        buildChips()
    }

    /** Appearance: brightness as a 3-way switch, colour themes as round swatches. Changing either keeps the menu open on this tab. */
    private fun appearancePage(content: LinearLayout, sheet: com.google.android.material.bottomsheet.BottomSheetDialog) {
        content.addView(label("Яркость", 14f, R.attr.gTextSecondary, true).apply { setPadding(dp(4), dp(8), 0, dp(8)) })
        val modes = listOf("light" to "День", "dim" to "Вечер", "dark" to "Ночь")
        val g = com.google.android.material.button.MaterialButtonToggleGroup(this).apply { isSingleSelection = true; isSelectionRequired = true }
        val mids = modes.map { View.generateViewId() }
        modes.forEachIndexed { i, m ->
            g.addView(com.google.android.material.button.MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = mids[i]; text = m.second; textSize = 13f; isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
            })
        }
        g.check(mids[modes.indexOfFirst { it.first == themeMode() }.coerceAtLeast(0)])
        g.addOnButtonCheckedListener { _, id, checked ->
            val m = modes[mids.indexOf(id)].first
            if (checked && m != themeMode()) {
                prefs.edit().putString("theme_mode", m).putBoolean("reopen_menu", true).apply()
                sheet.dismiss(); applyThemeMode(m)      // recreates the screen, the menu opens again on this tab
            }
        }
        content.addView(g, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val cur = COLOR_THEMES.first { it.id == colorTheme() }
        content.addView(opacityControl())
        content.addView(label("Цветовая тема: ${cur.title}", 14f, R.attr.gTextSecondary, true).apply { setPadding(dp(4), dp(24), 0, dp(12)) })
        for (group in COLOR_THEMES.chunked(4)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
            row.setPadding(0, 0, 0, dp(12))
            for (ct in group) {
                val selected = ct.id == cur.id
                val dot = View(this).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(Color.parseColor(if (isNight()) ct.swatchNight else ct.swatch))
                        setStroke(dp(if (selected) 3 else 1), themeColor(if (selected) R.attr.gTextPrimary else R.attr.gStroke))
                    }
                    elevation = dp(3).toFloat()
                    contentDescription = ct.title
                    stateListAnimator = android.animation.AnimatorInflater.loadStateListAnimator(this@MainActivity, R.animator.press)
                    setOnClickListener {
                        if (!selected) {
                            prefs.edit().putString("color_theme", ct.id).putBoolean("reopen_menu", true).apply()
                            sheet.dismiss(); recreate()   // the menu opens again on this tab, so the next theme is one tap away
                        }
                    }
                }
                val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; clipChildren = false }
                cell.addView(dot, LinearLayout.LayoutParams(dp(48), dp(48)))
                cell.addView(label(ct.title, 12f, if (selected) R.attr.gTextPrimary else R.attr.gTextSecondary, selected)
                    .apply { setPadding(0, dp(8), 0, 0) })
                row.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            content.addView(row)
        }
        content.addView(label(cur.about.replaceFirstChar { it.uppercase() }, 14f, R.attr.gHint).apply { setPadding(dp(4), dp(8), 0, 0) })
    }

    // ------------------------------------------------------------------ quick filters on the map screen (places + transport)
    private fun buildChips() {
        val row = findViewById<LinearLayout>(R.id.chipRow)
        row.removeAllViews()
        val sel = prefs.getStringSet("poi_cats", emptySet()) ?: emptySet()
        val tr = trOn()
        for ((emoji, short, key) in QUICK_CHIPS) {
            val on = if (key.startsWith("tr:")) key in tr else key in sel
            val chip = TextView(this).apply {
                text = "$emoji  $short"
                textSize = 14f
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setTextColor(themeColor(if (on) R.attr.gOnAccent else R.attr.gTextPrimary))
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(44)
                setPadding(dp(14), 0, dp(14), dp(3))
                background = Skin.chip(this@MainActivity, uiOpacity())
                Skin.shape(this, false, 28f, uiOpacity())
                isSelected = on
                elevation = dp(3).toFloat()
                stateListAnimator = android.animation.AnimatorInflater.loadStateListAnimator(this@MainActivity, R.animator.press)
                setOnClickListener {
                    if (key.startsWith("tr:")) {
                        val cur = trOn().toMutableSet()
                        if (!cur.add(key)) cur.remove(key)
                        prefs.edit().putStringSet("tr_on", HashSet(cur)).apply()
                        style?.let { applyTransport(it) }
                        if (key == "tr:stop" && key in cur && (map?.cameraPosition?.zoom ?: 20.0) < 14.0)
                            toast("Остановки видны при приближении карты")
                    } else {
                        val cur = (prefs.getStringSet("poi_cats", emptySet()) ?: emptySet()).toMutableSet()
                        if (!cur.add(key)) cur.remove(key)
                        prefs.edit().putStringSet("poi_cats", HashSet(cur)).apply()
                        style?.let { applyPoiFilter(it) }
                    }
                    buildChips()
                }
            }
            row.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(47)).apply { marginEnd = dp(8) })
        }
    }

    private fun trOn(): Set<String> = prefs.getStringSet("tr_on", null) ?: setOf("tr:station", "tr:air")

    /** Transport layers follow the chips: stations (+ bus stations, ferries), stops, airports. */
    private fun applyTransport(s: Style) {
        val groups = mapOf(
            "tr:station" to listOf("tr-station-dot", "lbl-station", "tr-bus-dot", "lbl-busstation", "tr-ferry-dot", "lbl-ferry"),
            "tr:stop" to listOf("tr-stop-dot", "lbl-stop"),
            "tr:air" to listOf("tr-aerodrome-dot", "lbl-aerodrome")
        )
        val on = trOn()
        for ((key, ids) in groups) {
            val v = if (key in on) Property.VISIBLE else Property.NONE
            for (id in ids) s.getLayer(id)?.setProperties(PropertyFactory.visibility(v))
        }
    }

    private fun updateRecButton() {
        val b = findViewById<ImageButton>(R.id.btnRec)
        val rec = TrackService.running
        b.imageTintList = android.content.res.ColorStateList.valueOf(themeColor(if (rec) R.attr.gCrosshair else R.attr.gIcon))
        b.contentDescription = if (rec) "Остановить запись трека" else "Начать запись трека"
    }

    // ------------------------------------------------------------------ routes
    private val backToStopNav = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { if (compass.isOpen) compass.close() else nav.stop() }
    }

    /** Hides the normal map controls while a route is previewed / driven. */
    private fun onNavModeChanged() {
        val navOn = nav.active
        if (navOn && searchOverlayVisible) {
            searchToken++
            searchDebounce?.let { ui.removeCallbacks(it) }
            searchDebounce = null
            searchResults = emptyList()
            lvResults.visibility = View.GONE
            setSearchOverlay(false)
        }
        if (navOn && compass.isOpen) { compass.close(); return }     // closing calls this function again
        val cmp = compass.isOpen
        backToStopNav.isEnabled = navOn || cmp
        // Search and map-only actions must not sit underneath the compass / navigation panels.
        findViewById<View>(R.id.topColumn).visibility = if (navOn || cmp) View.GONE else View.VISIBLE
        findViewById<View>(R.id.dock).visibility = if (navOn || cmp) View.GONE else View.VISIBLE
        findViewById<View>(R.id.btnFit).visibility = if (navOn) View.GONE else View.VISIBLE
        findViewById<View>(R.id.btnCompass).visibility = if (navOn) View.GONE else View.VISIBLE
        val rc = findViewById<View>(R.id.rightControls)
        rc.visibility = if (cmp || searchOverlayVisible) View.GONE else View.VISIBLE
        (rc.layoutParams as android.widget.FrameLayout.LayoutParams).bottomMargin = dp(if (navOn) 112 else 88)
        rc.requestLayout()
        if (nav.navigating && !locActive) withLocationPermission { startLocation(false) }
        if (!navOn && prefs.getBoolean("map_3d", false) && !isFinishing && !isDestroyed) set3dCamera(true)
        updateInfo()
    }

    private var pendingRoute: Pair<LatLng, String>? = null
    private var pendingRouteTimeout: Runnable? = null

    /** Route from the user's own GPS position. Without a fresh fix the app turns GPS on and waits for it. */
    private fun startRoute(to: LatLng, title: String) {
        val me = myLoc
        pendingRouteTimeout?.let { ui.removeCallbacks(it) }
        pendingRouteTimeout = null
        if (me != null && isFreshLocation(me)) {
            pendingRoute = null
            nav.planTo(to.latitude, to.longitude, title, me.latitude, me.longitude)
            return
        }
        pendingRoute = Pair(to, title)
        Toast.makeText(this, "Определяю ваше местоположение…", Toast.LENGTH_SHORT).show()
        withLocationPermission { startLocation(false) }
        val timeout = Runnable {
            pendingRouteTimeout = null
            val p = pendingRoute ?: return@Runnable
            pendingRoute = null
            val m = myLoc?.takeIf { isFreshLocation(it) }
            if (m != null) nav.planTo(p.first.latitude, p.first.longitude, p.second, m.latitude, m.longitude)
            else if (!isFinishing && !isDestroyed) Toast.makeText(this, "Свежая геопозиция не получена. Включите GPS и выйдите на открытое место", Toast.LENGTH_LONG).show()
        }
        pendingRouteTimeout = timeout
        ui.postDelayed(timeout, 20_000)
    }

    /** Bottom card for a place (search result or long press): where it is, how far, route / compass / save / copy. */
    private fun showPlaceSheet(ll: LatLng, title: String?) {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(24))
        }
        val name = title ?: "Точка на карте"
        root.addView(label(name, 22f, R.attr.gTextPrimary, true))
        root.addView(label(Geo.fmtCoord(ll.latitude, ll.longitude), 14f, R.attr.gTextSecondary)
            .apply { setPadding(0, dp(4), 0, 0); typeface = android.graphics.Typeface.MONOSPACE })
        val me = myLoc
        if (me != null) {
            val d = Graph.haversine(me.latitude, me.longitude, ll.latitude, ll.longitude)
            val b = Compass.bearing(me.latitude, me.longitude, ll.latitude, ll.longitude)
            root.addView(label("От вас: " + Geo.fmtDist(d) + " на " + Compass.dirName(b) + ", азимут ${b.roundToInt()}°", 14f, R.attr.gTextSecondary)
                .apply { setPadding(0, dp(4), 0, 0) })
        }
        root.addView(keyButton("Построить маршрут", true) { sheet.dismiss(); startRoute(ll, name) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(20) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val items = listOf(
            "Компас" to { sheet.dismiss(); compass.open(Compass.Kind.PLACE, ll.latitude, ll.longitude, name) },
            "Сохранить" to { sheet.dismiss(); addWaypointDialog(ll) },
            "Копировать" to {
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("coords", "%.6f, %.6f".format(Locale.US, ll.latitude, ll.longitude)))
                toast("Координаты скопированы")
            }
        )
        for ((i, item) in items.withIndex()) {
            row.addView(keyButton(item.first, false, 48) { item.second() }.apply { textSize = 14f },
                LinearLayout.LayoutParams(0, dp(48), 1f).apply { if (i < items.size - 1) marginEnd = dp(8) })
        }
        root.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        root.addView(dragHandle(), 0)
        sheet.setContentView(root)
        styleSheet(sheet)
        sheet.window?.setDimAmount(0.3f)
        sheet.show()
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
            ui.postDelayed({ if (!isFinishing && !isDestroyed) { refreshTrack(); updateInfo() } }, 400)
            toast("Запись остановлена")
            return
        }
        withLocationPermission {
            startLocation(false)
            if (!locActive) return@withLocationPermission
            val id = userDb.newTrack("Трек " + SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date()))
            prefs.edit().putLong("rec_track", id).apply()
            shownTrackId = id
            ContextCompat.startForegroundService(this, Intent(this, TrackService::class.java))
            ui.postDelayed({ if (!isFinishing && !isDestroyed) { scheduleTick(); updateInfo() } }, 800)
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

    /** Parse GPX timestamps with an explicit UTC default; preserve offsets when the file provides one. */
    private fun parseGpxTime(value: String): Long {
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
        )
        for (pattern in patterns) {
            try {
                val fmt = SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = false
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                fmt.parse(value.trim())?.let { return it.time }
            } catch (_: Exception) {}
        }
        return 0L
    }

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
                var ts = 0L
                var name = ""
                var trkName = "Импорт GPX"
                val pts = ArrayList<UserDb.Pt>()
                val segments = ArrayList<List<UserDb.Pt>>()
                var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG) {
                        when (p.name) {
                            "wpt" -> { inWpt = true; name = ""; lat = p.getAttributeValue(null, "lat").toDouble(); lon = p.getAttributeValue(null, "lon").toDouble() }
                            "trk" -> { inTrk = true; trkName = "Импорт GPX"; pts.clear(); segments.clear() }
                            "trkseg" -> pts.clear()
                            "trkpt" -> { inPt = true; ele = Double.NaN; ts = 0L; lat = p.getAttributeValue(null, "lat").toDouble(); lon = p.getAttributeValue(null, "lon").toDouble() }
                            "name" -> { val t = p.nextText(); if (inWpt) name = t else if (inTrk && !inPt) trkName = t }
                            "ele" -> { val t = p.nextText(); if (inPt) ele = t.toDoubleOrNull() ?: Double.NaN }
                            "time" -> { val t = p.nextText(); if (inPt) ts = parseGpxTime(t) }
                        }
                    } else if (ev == XmlPullParser.END_TAG) {
                        when (p.name) {
                            "wpt" -> { userDb.addWaypoint(name.ifBlank { "Точка" }, lat, lon); inWpt = false; wpts++ }
                            "trkpt" -> { pts.add(UserDb.Pt(lat, lon, ele, ts)); inPt = false }
                            "trkseg" -> { if (pts.isNotEmpty()) segments.add(pts.toList()); pts.clear() }
                            "trk" -> {
                                if (pts.isNotEmpty()) segments.add(pts.toList()) // GPX files may omit trkseg
                                segments.forEachIndexed { i, segment ->
                                    val suffix = if (segments.size > 1) " · часть ${i + 1}" else ""
                                    val id = userDb.newTrack(trkName + suffix)
                                    userDb.addPoints(id, segment)
                                    trks++
                                }
                                pts.clear(); segments.clear(); inTrk = false
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
            "Карта, поиск, спутниковая подложка и рельеф работают без интернета, если соответствующие данные включены в сборку APK. GPS тоже работает без сети.\n\n" +
            "3D-здания: Меню → Карта → 3D-здания. Объём зависит от высотных тегов OpenStreetMap и виден на масштабе 15+. Это не 3D-рельеф.\n\n" +
            "Горные дороги и перевалы (Омало, Ушгули, Абано и др.) зимой могут быть закрыты — на карте это не отражено. " +
            "Перед выездом уточняйте обстановку на месте.\n\n" +
            "Для долгой записи трека разрешите приложению работу в фоне и не закрывайте уведомление.\n\n" +
            "Данные: © участники OpenStreetMap (ODbL), OpenMapTiles, Natural Earth; рельеф — AWS Terrain Tiles; " +
            "ортофотоснимки — © National Agency of Public Registry of Georgia (NAPR). Данные актуальны на дату сборки APK."
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
        val satFile = File(filesDir, "sat.mbtiles")
        sb.append("спутниковые снимки: ").append(
            if (satFile.exists()) "${satFile.length() shr 20} МБ, zoom от ${mbtilesMinZoom(satFile)}"
            else "нет (показывается стилизованная подложка)"
        ).append("\n")
        sb.append("3D-здания: ").append(if (prefs.getBoolean("map_3d", false)) "включены" else "выключены").append("\n")
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
                "road-path", "building", "building-3d", "boundary-2", "boundary-4", "lbl-city", "lbl-town", "lbl-village", "lbl-peak", "lbl-road-major")) {
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
        private val DATA_FILES = listOf("map.mbtiles", "relief.mbtiles", "places.db", "graph.bin", "sat.mbtiles", "contours.mbtiles")
        // transport POIs have their own always-visible layers (tr-*, lbl-station ...); keep them out of the generic POI layers
        private const val TR_LIST = "'station','halt','subway','tram_stop','bus_station','bus_stop','ferry_terminal','harbor'"
        private const val TRANSPORT_EXPR = "['any',['match',['get','class'],[$TR_LIST],true,false]," +
            "['match',['get','subclass'],[$TR_LIST],true,false]]"
        // used only if the tile file has no "bounds" metadata
        private val DEFAULT_BOUNDS = LatLngBounds.from(43.65, 46.8, 41.0, 40.0)

        private data class ColorTheme(val id: String, val title: String, val about: String, val swatch: String, val swatchNight: String)

        private val COLOR_THEMES = listOf(
            ColorTheme("forest", "Тайга", "спокойный зелёный, как лес", "#2A7553", "#7FD1A8"),
            ColorTheme("ocean", "Лагуна", "прохладная сине-бирюзовая гамма", "#1F6A98", "#7CC0EA"),
            ColorTheme("sand", "Песок", "тёплая «бумажная» гамма для чтения", "#A84D29", "#E8996F"),
            ColorTheme("dusk", "Сумерки", "мягкий лавандовый для вечера", "#6450AE", "#B5A6F0"),
            ColorTheme("graphite", "Графит", "строгая чёрно-белая гамма без цвета", "#25272B", "#E4E6E9"),
            ColorTheme("sunset", "Закат", "нежный розово-коралловый", "#B23A5E", "#F29BB5"),
            ColorTheme("mint", "Мята", "свежая бирюзово-мятная", "#0A7C7F", "#6FD6D2"),
            ColorTheme("contrast", "Контраст", "максимум читаемости на солнце", "#0B3FBF", "#FFD400")
        )

        // quick filters on the map screen: emoji, short label, category key in POI_CATS (ordered by use on the road)
        private val QUICK_CHIPS = listOf(
            Triple("⛽", "АЗС", "АЗС"), Triple("🍽", "Еда", "Еда и кафе"), Triple("🅿", "Парковки", "Парковки"),
            Triple("🚉", "Вокзалы", "tr:station"), Triple("🚏", "Остановки", "tr:stop"), Triple("✈", "Аэропорты", "tr:air"),
            Triple("🏧", "Банкоматы", "Банки и банкоматы"), Triple("🛒", "Магазины", "Магазины"),
            Triple("💊", "Аптеки", "Аптеки"), Triple("🏥", "Больницы", "Больницы и врачи"),
            Triple("🏛", "Места", "Достопримечательности"), Triple("🚨", "Экстренные", "Полиция и экстренные"),
            Triple("🛏", "Ночлег", "Ночлег"), Triple("🚻", "Туалеты", "Туалеты"), Triple("💧", "Вода", "Вода и родники")
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
