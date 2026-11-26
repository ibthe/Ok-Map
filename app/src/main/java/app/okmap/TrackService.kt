package app.okmap

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/** Foreground service that records a GPS track into the database while the screen is off. */
class TrackService : Service() {

    companion object {
        @Volatile
        var running = false
        private const val CHANNEL = "track"
    }

    private var db: UserDb? = null
    private var lm: LocationManager? = null
    private var trackId = 0L

    private val listener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            if (trackId > 0) {
                db?.addPoint(trackId, l.latitude, l.longitude, if (l.hasAltitude()) l.altitude else Double.NaN, l.time)
            }
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (db == null) db = UserDb(this)
        trackId = getSharedPreferences("settings", MODE_PRIVATE).getLong("rec_track", 0L)
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "Запись трека", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Запись GPS-трека")
            .setContentText("Ok Map записывает маршрут")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        // startForeground must be called before any stopSelf(), otherwise Android kills the app
        try {
            ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: Exception) {  // e.g. location permission was revoked
            stopSelf()
            return START_NOT_STICKY
        }
        if (trackId <= 0) {
            stopSelf()
            return START_NOT_STICKY
        }

        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        lm = manager
        val fine = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val coarse = checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val gps = fine && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val net = (fine || coarse) && manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        if (!gps && !net) {
            stopSelf()
            return START_NOT_STICKY
        }

        try { manager.removeUpdates(listener) } catch (_: SecurityException) {}
        running = false
        var registered = false
        if (gps) {
            try {
                manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000L, 5f, listener, Looper.getMainLooper())
                registered = true
            } catch (_: SecurityException) {}
        }
        if (net) {
            try {
                manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 5f, listener, Looper.getMainLooper())
                registered = true
            } catch (_: SecurityException) {}
        }
        if (!registered) {
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        return START_STICKY
    }

    override fun onDestroy() {
        try { lm?.removeUpdates(listener) } catch (_: SecurityException) {}
        running = false
        super.onDestroy()
    }
}
