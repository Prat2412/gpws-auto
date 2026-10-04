package dev.gpws.auto

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** A navigation notification that has given nothing readable for this long means a new Maps layout. */
private const val UNREADABLE_MS = 60_000L

/** Watches the Maps navigation notification and feeds its distance (plus GPS speed) to [Gpws]. */
class NavListener : NotificationListenerService() {

    private var navKey: String? = null
    private var gpsOn = false
    private var car: BroadcastReceiver? = null
    private var carAskedAt = 0L
    private var carBluetooth: AudioDeviceCallback? = null
    private var probeKey: String? = null  // the navigation notification being checked for readability
    private var probeSince = 0L
    private var probePosts = 0             // -1: it has been read, so later blanks are blips

    private val gps = object : LocationListener {
        private var prev: Location? = null

        /** The fix's own speed, or, if it has none, distance over time from the fix before. */
        private fun speedOf(l: Location): Double? {
            if (l.hasSpeed()) return l.speed.toDouble()
            val p = prev ?: return null
            val secs = (l.elapsedRealtimeNanos - p.elapsedRealtimeNanos) / 1e9
            return if (secs in 0.5..5.0) p.distanceTo(l) / secs else null
        }

        override fun onLocationChanged(location: Location) {
            val mps = speedOf(location)
            prev = location
            Gpws.onGps(
                mps,
                if (location.hasBearing()) location.bearing else null,
                location.latitude,
                location.longitude,
                // GPS height is good to a few metres at best; fixes worse than 20 m are no use for hills.
                if (location.hasAltitude() && (!location.hasVerticalAccuracy() || location.verticalAccuracyMeters <= 20f)) {
                    location.altitude
                } else {
                    null
                },
            )
        }
        // Must be spelled out: on Android 10 and older these have no default implementation.
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val baro = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) = Gpws.onPressure(e.values[0])
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    // Up/down, cornering and bumps, for BANK ANGLE and TOO LOW TERRAIN.
    private val motion = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) = when (e.sensor.type) {
            Sensor.TYPE_GRAVITY -> Gpws.onGravity(e.values)
            Sensor.TYPE_GYROSCOPE -> Gpws.onGyro(e.values)
            else -> Gpws.onLinear(e.values)
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    override fun onListenerConnected() {
        Gpws.init(this)
        Events.add("listening for Maps")
        Gpws.initCar(Car.connected(this), Car.bluetoothCar(this))
        if (car == null) car = Car.watch(this) { Gpws.onAndroidAuto(it) }
        if (carBluetooth == null) carBluetooth = Car.watchBluetooth(this) { Gpws.onCarBluetooth(it) }
        activeNotifications?.forEach { onNotificationPosted(it) }
    }

    override fun onListenerDisconnected() {
        stopGps()
        car?.let { unregisterReceiver(it) }
        car = null
        carBluetooth?.let { Car.unwatchBluetooth(this, it) }
        carBluetooth = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in NavParser.PACKAGES) return
        val n = sbn.notification
        val ongoing = n.category == Notification.CATEGORY_NAVIGATION ||
            (n.flags and Notification.FLAG_ONGOING_EVENT) != 0
        if (!ongoing) return
        Gpws.init(this)
        val r = NavParser.read(this, n)
        // A Live Update is navigation even before its bar has a distance (parked, or a reroute blip).
        val useful = r.metres != null || r.arrived || r.rerouting || r.liveUpdate
        watchReadable(sbn, r, useful)
        if (useful || navKey == null || sbn.key == navKey) Events.showMaps(r, NavParser.extras(n))
        if (!useful && sbn.key != navKey) return  // some other ongoing Maps notification
        navKey = sbn.key
        startGps()
        // Backup for Android Auto's broadcast: navigating is exactly when auto-arm matters.
        val now = SystemClock.elapsedRealtime()
        if (now - carAskedAt > 30_000) {
            carAskedAt = now
            Gpws.onAndroidAuto(Car.connected(this))
            Gpws.onCarBluetooth(Car.bluetoothCar(this))
        }
        Gpws.onMaps(r)
    }

    /**
     * Navigation running, but its notification never once gave a distance: Maps has most likely
     * changed its layout. A minute and a few updates of nothing before saying so; any reading clears it.
     * A blank after a good reading (a tunnel, a reroute) is a blip, not a new layout.
     */
    private fun watchReadable(sbn: StatusBarNotification, r: NavParser.Reading, useful: Boolean) {
        if (sbn.packageName != NavParser.MAPS) return  // Waze is a bonus: its layout isn't one we promise
        // Only a notification that's clearly guiding counts: with Android Auto, Maps shows "Driving with
        // Google Maps" or "Starting navigation…" for a minute or two before the first turn.
        if (!r.guidance) return
        if (sbn.key != probeKey) {
            probeKey = sbn.key
            probeSince = SystemClock.elapsedRealtime()
            probePosts = 0
        }
        if (useful) {
            probePosts = -1
            Gpws.onMapsUnreadable(false)
            return
        }
        if (probePosts < 0) return
        probePosts++
        if (probePosts >= 3 && SystemClock.elapsedRealtime() - probeSince > UNREADABLE_MS) Gpws.onMapsUnreadable(true)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.key == probeKey) {
            probeKey = null
            Gpws.onMapsUnreadable(false)
        }
        if (sbn.key != navKey) return
        navKey = null
        stopGps()
        Gpws.onMapsEnded()
    }

    @SuppressLint("MissingPermission")
    private fun startGps() {
        if (gpsOn) return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, gps, Looper.getMainLooper())
            gpsOn = true
            // Hills: the barometer's heights are far steadier than GPS's. Five readings a second is plenty.
            val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            sm.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let { sm.registerListener(baro, it, 200_000) }
            for (type in intArrayOf(Sensor.TYPE_GRAVITY, Sensor.TYPE_GYROSCOPE, Sensor.TYPE_LINEAR_ACCELERATION)) {
                sm.getDefaultSensor(type)?.let { sm.registerListener(motion, it, 20_000) }  // 50 a second: bumps are short
            }
        } catch (e: Exception) {
            Events.add("GPS unavailable: ${e.message}")
        }
    }

    private fun stopGps() {
        if (!gpsOn) return
        (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(gps)
        (getSystemService(Context.SENSOR_SERVICE) as SensorManager).run {
            unregisterListener(baro)
            unregisterListener(motion)
        }
        gpsOn = false
    }
}
