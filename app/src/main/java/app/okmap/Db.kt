package app.okmap

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import org.maplibre.android.geometry.LatLng
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/** Waypoints and recorded tracks (private, on-device). */
class UserDb(ctx: Context) : SQLiteOpenHelper(ctx, "user.db", null, 1) {

    data class Wpt(val id: Long, val name: String, val lat: Double, val lon: Double)
    data class Pt(val lat: Double, val lon: Double, val ele: Double, val ts: Long)
    data class Track(val id: Long, val name: String, val start: Long, val count: Int)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("create table waypoints(id integer primary key autoincrement, name text, lat real, lon real, ts integer)")
        db.execSQL("create table tracks(id integer primary key autoincrement, name text, start integer)")
        db.execSQL("create table track_points(id integer primary key autoincrement, track_id integer, ts integer, lat real, lon real, ele real)")
        db.execSQL("create index tp_idx on track_points(track_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}

    fun addWaypoint(name: String, lat: Double, lon: Double): Long {
        val v = ContentValues()
        v.put("name", name); v.put("lat", lat); v.put("lon", lon); v.put("ts", System.currentTimeMillis())
        return writableDatabase.insert("waypoints", null, v)
    }

    fun waypoints(): List<Wpt> {
        val out = ArrayList<Wpt>()
        readableDatabase.rawQuery("select id,name,lat,lon from waypoints order by id", null).use { c ->
            while (c.moveToNext()) out.add(Wpt(c.getLong(0), c.getString(1) ?: "", c.getDouble(2), c.getDouble(3)))
        }
        return out
    }

    fun deleteWaypoint(id: Long) {
        writableDatabase.delete("waypoints", "id=?", arrayOf(id.toString()))
    }

    fun newTrack(name: String): Long {
        val v = ContentValues()
        v.put("name", name); v.put("start", System.currentTimeMillis())
        return writableDatabase.insert("tracks", null, v)
    }

    fun addPoint(trackId: Long, lat: Double, lon: Double, ele: Double, ts: Long) {
        val v = ContentValues()
        v.put("track_id", trackId); v.put("ts", ts); v.put("lat", lat); v.put("lon", lon)
        if (!ele.isNaN()) v.put("ele", ele)
        writableDatabase.insert("track_points", null, v)
    }

    fun addPoints(trackId: Long, pts: List<Pt>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (p in pts) {
                val v = ContentValues()
                v.put("track_id", trackId); v.put("ts", p.ts); v.put("lat", p.lat); v.put("lon", p.lon)
                if (!p.ele.isNaN()) v.put("ele", p.ele)
                db.insert("track_points", null, v)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun tracks(): List<Track> {
        val out = ArrayList<Track>()
        readableDatabase.rawQuery(
            "select t.id, t.name, t.start, (select count(*) from track_points p where p.track_id=t.id) from tracks t order by t.id desc",
            null
        ).use { c ->
            while (c.moveToNext()) out.add(Track(c.getLong(0), c.getString(1) ?: "", c.getLong(2), c.getInt(3)))
        }
        return out
    }

    fun trackPoints(id: Long): List<Pt> {
        val out = ArrayList<Pt>()
        readableDatabase.rawQuery(
            "select lat,lon,ele,ts from track_points where track_id=? order by id", arrayOf(id.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(Pt(c.getDouble(0), c.getDouble(1), if (c.isNull(2)) Double.NaN else c.getDouble(2), c.getLong(3)))
            }
        }
        return out
    }

    fun trackLatLng(id: Long): List<LatLng> = trackPoints(id).map { LatLng(it.lat, it.lon) }

    fun deleteTrack(id: Long) {
        val db = writableDatabase
        db.delete("track_points", "track_id=?", arrayOf(id.toString()))
        db.delete("tracks", "id=?", arrayOf(id.toString()))
    }
}

/** Offline search index built at CI time (places.db). */
class PlaceDb(path: String) {
    data class Place(val ka: String, val ru: String, val en: String, val kind: String,
                     val lat: Double, val lon: Double, val ele: Int)

    private val db = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)
    private val lock = Any()

    fun count(): Int = synchronized(lock) {
        db.rawQuery("select count(*) from places", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    fun search(query: String, limit: Int = 40): List<Place> = synchronized(lock) {
        val q = query.trim().lowercase().replace("%", "").replace("_", "")
        if (q.isEmpty()) return@synchronized emptyList()
        val out = ArrayList<Place>()
        db.rawQuery(
            "select ka,ru,en,kind,lat,lon,ifnull(ele,0) from places where s like ? " +
                "order by case when s like ? or s like ? then 0 else 1 end, prio, length(s) limit $limit",
            arrayOf("%$q%", "$q%", "%\n$q%")
        ).use { c ->
            while (c.moveToNext()) {
                out.add(Place(c.getString(0) ?: "", c.getString(1) ?: "", c.getString(2) ?: "",
                    c.getString(3) ?: "", c.getDouble(4), c.getDouble(5), c.getInt(6)))
            }
        }
        out
    }

    fun close() = synchronized(lock) { db.close() }

/** Reads ground elevation from the Terrarium tiles bundled in relief.mbtiles. */
class Elevation(path: String, private val z: Int = 11) {
    private val db = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)
    private var cacheKey = ""
    private var cacheBmp: Bitmap? = null

    fun at(lat: Double, lon: Double): Int? {
        if (lat < -85 || lat > 85) return null
        val n = 1 shl z
        val xf = (lon + 180.0) / 360.0 * n
        val r = Math.toRadians(lat)
        val yf = (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * n
        val x = floor(xf).toInt()
        val y = floor(yf).toInt()
        if (x < 0 || y < 0 || x >= n || y >= n) return null
        val key = "$x/$y"
        var bmp: Bitmap? = if (key == cacheKey) cacheBmp else null
        if (bmp == null) {
            val row = n - 1 - y
            var blob: ByteArray? = null
            db.rawQuery(
                "select tile_data from tiles where zoom_level=? and tile_column=? and tile_row=?",
                arrayOf(z.toString(), x.toString(), row.toString())
            ).use { c -> if (c.moveToFirst()) blob = c.getBlob(0) }
            val b = blob ?: return null
            bmp = BitmapFactory.decodeByteArray(b, 0, b.size)
            cacheKey = key
            cacheBmp = bmp
        }
        val bm = bmp ?: return null
        val px = ((xf - x) * bm.width).toInt().coerceIn(0, bm.width - 1)
        val py = ((yf - y) * bm.height).toInt().coerceIn(0, bm.height - 1)
        val p = bm.getPixel(px, py)
        val rr = (p shr 16) and 0xFF
        val gg = (p shr 8) and 0xFF
        val bb = p and 0xFF
        return Math.round((rr * 256 + gg + bb / 256.0) - 32768.0).toInt()
    }

    fun close() {
        db.close()
        cacheBmp?.recycle()
        cacheBmp = null
        cacheKey = ""
    }
}
