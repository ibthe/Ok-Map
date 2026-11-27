package app.okmap

import android.Manifest
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.maplibre.android.MapLibre
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineAppSmokeTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun userDatabaseTransactionsAreAtomicAndTrackReadsCanStream() {
        context.deleteDatabase("user.db")
        val db = UserDb(context)
        try {
            try {
                db.transaction {
                    db.addWaypoint("rollback", 41.7, 44.8, "Temporary")
                    val track = db.newTrack("rollback track")
                    db.addPoints(track, listOf(
                        UserDb.Pt(41.7, 44.8, Double.NaN, 1L),
                        UserDb.Pt(41.8, 44.9, 120.0, 2L)
                    ))
                    throw IllegalStateException("force rollback")
                }
                throw AssertionError("Expected the transaction to roll back")
            } catch (expected: IllegalStateException) {
                assertEquals("force rollback", expected.message)
            }

            assertTrue(db.waypoints().isEmpty())
            assertTrue(db.tracks().isEmpty())
            assertFalse(db.folders().contains("Temporary"))

            val trackId = db.transaction {
                db.addWaypoint("saved", 41.7, 44.8, "Travel")
                val id = db.newTrack("committed track")
                db.addPoints(id, listOf(
                    UserDb.Pt(41.7, 44.8, Double.NaN, 10L),
                    UserDb.Pt(41.8, 44.9, 120.0, 20L)
                ))
                id
            }

            assertEquals("saved", db.waypoints().single().name)
            assertEquals(2, db.trackPoints(trackId).size)
            val streamed = ArrayList<UserDb.Pt>()
            db.forEachTrackPoint(trackId) { streamed.add(it) }
            assertEquals(db.trackPoints(trackId), streamed)
        } finally {
            db.close()
        }
    }

    @Test
    fun versionOneDatabaseUpgradePreservesSavedWaypoints() {
        context.deleteDatabase("user.db")
        val file = context.getDatabasePath("user.db")
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { legacy ->
            legacy.execSQL("CREATE TABLE waypoints(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, lat REAL, lon REAL, ts INTEGER)")
            legacy.execSQL("CREATE TABLE tracks(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, start INTEGER)")
            legacy.execSQL("CREATE TABLE track_points(id INTEGER PRIMARY KEY AUTOINCREMENT, track_id INTEGER, ts INTEGER, lat REAL, lon REAL, ele REAL)")
            legacy.execSQL("CREATE INDEX tp_idx ON track_points(track_id)")
            legacy.execSQL("INSERT INTO waypoints(name,lat,lon,ts) VALUES('legacy place',41.7,44.8,123)")
            legacy.version = 1
        }

        val db = UserDb(context)
        try {
            val migrated = db.waypoints().single()
            assertEquals("legacy place", migrated.name)
            assertEquals(UserDb.DEFAULT_FOLDER, migrated.folder)
            assertTrue(db.folders().contains(UserDb.DEFAULT_FOLDER))
        } finally {
            db.close()
        }
    }

    @Test
    fun installedManifestDoesNotRequestInternetPermission() {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val requested = info.requestedPermissions.orEmpty()
        assertFalse("Ok Map must remain fully offline", requested.contains(Manifest.permission.INTERNET))
    }

    @Test
    fun packagedOfflineDataAndMainLayoutAreValidAndNonOverlapping() {
        val assets = context.assets.list("")?.toSet() ?: emptySet()
        assertTrue("Offline vector map is missing", assets.contains("map.mbtiles"))
        assertTrue("Offline road graph is missing", assets.contains("graph.bin"))
        assertTrue("Offline places index is missing", assets.contains("places.db"))

        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try {
                // MapLibre and Android View construction/measurement must stay on the UI thread.
                MapLibre.getInstance(context)
                val root = LayoutInflater.from(context).inflate(R.layout.activity_main, null, false)
                val ids = listOf(
                    R.id.mapView, R.id.etSearch, R.id.btnMenu, R.id.btnPoint,
                    R.id.btnLocation, R.id.btnCompass, R.id.btnRec, R.id.dock, R.id.rightControls
                )
                ids.forEach { id -> assertTrue("Missing layout view id $id", root.findViewById<View>(id) != null) }

                val width = context.resources.displayMetrics.widthPixels
                val height = context.resources.displayMetrics.heightPixels
                root.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
                )
                root.layout(0, 0, width, height)
                val dockView = root.findViewById<View>(R.id.dock)
                val rightView = root.findViewById<View>(R.id.rightControls)
                val dock = Rect(dockView.left, dockView.top, dockView.right, dockView.bottom)
                val right = Rect(rightView.left, rightView.top, rightView.right, rightView.bottom)
                assertFalse("Bottom dock and right-side controls must not overlap", Rect.intersects(dock, right))
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        // Rethrow the original Android/MapLibre assertion so CI reports its exact cause.
        failure.get()?.let { throw it }
    }
}
