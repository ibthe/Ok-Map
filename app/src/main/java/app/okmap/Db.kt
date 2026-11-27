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
class UserDb(ctx: Context) : SQLiteOpenHelper(ctx, "user.db", null, 2) {

    companion object {
        const val DEFAULT_FOLDER = "Избранное"
    }

    data class Wpt(val id: Long, val name: String, val lat: Double, val lon: Double, val folder: String)
    data class Pt(val lat: Double, val lon: Double, val ele: Double, val ts: Long)
    data class Track(val id: Long, val name: String, val start: Long, val count: Int)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("create table folders(name text primary key)")
        db.execSQL("insert into folders(name) values('$DEFAULT_FOLDER')")
        db.execSQL("create table waypoints(id integer primary key autoincrement, name text, lat real, lon real, ts integer, folder text not null default '$DEFAULT_FOLDER')")
        db.execSQL("create index waypoints_folder_idx on waypoints(folder)")
        db.execSQL("create table tracks(id integer primary key autoincrement, name text, start integer)")
        db.execSQL("create table track_points(id integer primary key autoincrement, track_id integer, ts integer, lat real, lon real, ele real)")
        db.execSQL("create index tp_idx on track_points(track_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
        if (o < 2) {
            db.execSQL("create table if not exists folders(name text primary key)")
            db.execSQL("insert or ignore into folders(name) values('$DEFAULT_FOLDER')")
            db.execSQL("alter table waypoints add column folder text not null default '$DEFAULT_FOLDER'")
            db.execSQL("create index if not exists waypoints_folder_idx on waypoints(folder)")
        }
    }

    fun folders(): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery(
            "select name from folders order by case when name=? then 0 else 1 end, name collate nocase",
            arrayOf(DEFAULT_FOLDER)
        ).use { c -> while (c.moveToNext()) out.add(c.getString(0)) }
        if (out.isEmpty()) out.add(DEFAULT_FOLDER)
        return out
    }

    fun waypointCount(folder: String): Int =
        readableDatabase.rawQuery("select count(*) from waypoints where folder=?", arrayOf(folder))
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun createFolder(name: String): Boolean {
        val normalized = name.trim().take(40)
        if (normalized.isBlank() || normalized == DEFAULT_FOLDER) return false
        val values = ContentValues().apply { put("name", normalized) }
        return writableDatabase.insertWithOnConflict("folders", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    fun ensureFolder(name: String): String {
        val normalized = name.trim().take(40).ifBlank { DEFAULT_FOLDER }
        val values = ContentValues().apply { put("name", normalized) }
        writableDatabase.insertWithOnConflict("folders", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        return normalized
    }

    fun renameFolder(oldName: String, newName: String): Boolean {
        val old = oldName.trim()
        val new = newName.trim().take(40)
        if (old == DEFAULT_FOLDER || new.isBlank() || new == DEFAULT_FOLDER) return false
        if (old == new) return true
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val folderValues = ContentValues().apply { put("name", new) }
            val changed = db.update("folders", folderValues, "name=?", arrayOf(old))
            if (changed == 0) false else {
                val waypointValues = ContentValues().apply { put("folder", new) }
                db.update("waypoints", waypointValues, "folder=?", arrayOf(old))
                db.setTransactionSuccessful()
                true
            }
        } catch (_: Exception) {
            false
        } finally {
            db.endTransaction()
        }
    }

    fun deleteFolder(name: String): Boolean {
        val folder = name.trim()
        if (folder == DEFAULT_FOLDER) return false
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val values = ContentValues().apply { put("folder", DEFAULT_FOLDER) }
            db.update("waypoints", values, "folder=?", arrayOf(folder))
            val deleted = db.delete("folders", "name=?", arrayOf(folder)) > 0
            db.setTransactionSuccessful()
            deleted
        } finally {
            db.endTransaction()
        }
    }

    fun moveWaypoint(id: Long, folder: String): Boolean {
        val target = ensureFolder(folder)
        val values = ContentValues().apply { put("folder", target) }
        return writableDatabase.update("waypoints", values, "id=?", arrayOf(id.toString())) > 0
    }

    fun addWaypoint(name: String, lat: Double, lon: Double, folder: String = DEFAULT_FOLDER): Long {
        val v = ContentValues()
        v.put("name", name); v.put("lat", lat); v.put("lon", lon); v.put("ts", System.currentTimeMillis())
        v.put("folder", ensureFolder(folder))
        return writableDatabase.insert("waypoints", null, v)
    }

    fun waypoints(folder: String? = null): List<Wpt> {
        val out = ArrayList<Wpt>()
        val sql = if (folder == null)
            "select id,name,lat,lon,folder from waypoints order by id"
        else
            "select id,name,lat,lon,folder from waypoints where folder=? order by id"
        readableDatabase.rawQuery(sql, if (folder == null) null else arrayOf(folder)).use { c ->
            while (c.moveToNext()) out.add(Wpt(
                c.getLong(0), c.getString(1) ?: "", c.getDouble(2), c.getDouble(3),
                c.getString(4) ?: DEFAULT_FOLDER
            ))
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

    /** Stream track points in database order without materializing a potentially huge track in memory. */
    fun forEachTrackPoint(id: Long, action: (Pt) -> Unit) {
        readableDatabase.rawQuery(
            "select lat,lon,ele,ts from track_points where track_id=? order by id", arrayOf(id.toString())
        ).use { c ->
            while (c.moveToNext()) {
                action(Pt(c.getDouble(0), c.getDouble(1), if (c.isNull(2)) Double.NaN else c.getDouble(2), c.getLong(3)))
            }
        }
    }

    /** Downsample large tracks for map rendering while preserving the first and last points. */
    fun trackLatLng(id: Long, maxPoints: Int = 8000): List<LatLng> {
        val db = readableDatabase
        val total = db.rawQuery("select count(*) from track_points where track_id=?", arrayOf(id.toString()))
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }
        if (total == 0) return emptyList()
        val limit = maxPoints.coerceAtLeast(2)
        val step = ((total + limit - 1) / limit).coerceAtLeast(1)
        val out = ArrayList<LatLng>(minOf(total, limit + 1))
        var index = 0
        db.rawQuery(
            "select lat,lon from track_points where track_id=? order by id",
            arrayOf(id.toString())
        ).use { c ->
            while (c.moveToNext()) {
                if (index % step == 0 || index == total - 1) {
                    out.add(LatLng(c.getDouble(0), c.getDouble(1)))
                }
                index++
            }
        }
        return out
    }

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
}

/** Reads ground elevation from the Terrarium tiles bundled in relief.mbtiles. */
class Elevation(path: String, private val z: Int = 11) {
    private val db = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)
    // Small LRU cache avoids decoding the same DEM tiles repeatedly while drawing a profile.
    private val tileCache = LinkedHashMap<String, Bitmap>(8, 0.75f, true)

    @Synchronized
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
        var bmp = tileCache[key]
        if (bmp == null) {
            val row = n - 1 - y
            var blob: ByteArray? = null
            db.rawQuery(
                "select tile_data from tiles where zoom_level=? and tile_column=? and tile_row=?",
                arrayOf(z.toString(), x.toString(), row.toString())
            ).use { c -> if (c.moveToFirst()) blob = c.getBlob(0) }
            val b = blob ?: return null
            bmp = BitmapFactory.decodeByteArray(b, 0, b.size) ?: return null
            tileCache[key] = bmp
            if (tileCache.size > 8) {
                val oldest = tileCache.entries.iterator()
                if (oldest.hasNext()) {
                    val entry = oldest.next()
                    oldest.remove()
                    if (!entry.value.isRecycled) entry.value.recycle()
                }
            }
        }
        val bm = bmp
        val px = ((xf - x) * bm.width).toInt().coerceIn(0, bm.width - 1)
        val py = ((yf - y) * bm.height).toInt().coerceIn(0, bm.height - 1)
        val p = bm.getPixel(px, py)
        val rr = (p shr 16) and 0xFF
        val gg = (p shr 8) and 0xFF
        val bb = p and 0xFF
        return Math.round((rr * 256 + gg + bb / 256.0) - 32768.0).toInt()
    }

    @Synchronized
    fun close() {
        db.close()
        tileCache.values.forEach { if (!it.isRecycled) it.recycle() }
        tileCache.clear()
    }
}
