package app.okmap

import android.Manifest
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    fun activityExtractsOfflineDataAndKeepsPrimaryControlsSeparated() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var ready = false
            val deadline = SystemClock.elapsedRealtime() + 240_000L
            while (!ready && SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity { activity ->
                    ready = activity.findViewById<View>(R.id.loading).visibility == View.GONE
                }
                if (!ready) SystemClock.sleep(500L)
            }
            assertTrue("First-launch offline data extraction did not finish", ready)

            scenario.onActivity { activity ->
                assertNotNull(activity.findViewById<View>(R.id.mapView))
                assertNotNull(activity.findViewById<View>(R.id.etSearch))
                assertNotNull(activity.findViewById<View>(R.id.btnMenu))
                assertNotNull(activity.findViewById<View>(R.id.btnPoint))
                assertNotNull(activity.findViewById<View>(R.id.btnLocation))
                assertNotNull(activity.findViewById<View>(R.id.btnCompass))
                assertNotNull(activity.findViewById<View>(R.id.btnRec))

                val dock = Rect()
                val rightControls = Rect()
                assertTrue(activity.findViewById<View>(R.id.dock).getGlobalVisibleRect(dock))
                assertTrue(activity.findViewById<View>(R.id.rightControls).getGlobalVisibleRect(rightControls))
                assertFalse("Bottom dock and right-side controls must not overlap", Rect.intersects(dock, rightControls))
            }
        }
    }
}
