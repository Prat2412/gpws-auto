package dev.gpws.auto

import android.hardware.SensorManager
import kotlin.math.cos

/**
 * Hills as terrain, after two real GPWS modes: "TERRAIN, TERRAIN" on a steep climb (mode 2: closing
 * on rising ground) and "DON'T SINK" on a steep descent (mode 3: losing height). The grade is taken
 * over the last 400 m driven, so flyovers and speed bumps don't count; ghats do.
 *
 * Altitude comes from the barometer when the phone has one (steady to well under a metre), else GPS.
 * Main thread only.
 */
object Terrain {

    enum class Call { TERRAIN, DONT_SINK }

    private const val WINDOW_M = 400.0
    private const val TRIGGER_M = 24.0     // height gained or lost over the window: a 6% grade
    private const val REARM_M = 10.0       // eases below 2.5% before the same call can come again
    private const val MIN_MPS = 15 / 3.6
    private const val COOLDOWN_MS = 3 * 60_000L
    private const val BARO_FRESH_MS = 3000L

    private val track = ArrayDeque<Pair<Double, Double>>()  // (metres driven, altitude)
    private var fromBaro = false           // which altitude the track holds: the two never mix
    private var driven = 0.0
    private var lastLat = Double.NaN
    private var lastLon = 0.0
    private var baro: Double? = null       // smoothed barometric altitude
    private var baroAt = -BARO_FRESH_MS
    private var gps: Double? = null        // smoothed GPS altitude
    private var climbArmed = true
    private var sinkArmed = true
    private var climbAt = -COOLDOWN_MS
    private var sinkAt = -COOLDOWN_MS

    fun onPressure(hPa: Float, now: Long) {
        val alt = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, hPa).toDouble()
        baro = baro?.let { it + 0.05 * (alt - it) } ?: alt
        baroAt = now
    }

    /** Every GPS fix while navigating. Returns the call to make, if any. */
    fun onFix(lat: Double, lon: Double, gpsAltitude: Double?, mps: Double, now: Long): Call? {
        if (!lastLat.isNaN()) driven += metres(lastLat, lastLon, lat, lon)
        lastLat = lat
        lastLon = lon
        if (gpsAltitude != null) gps = gps?.let { it + 0.3 * (gpsAltitude - it) } ?: gpsAltitude
        val useBaro = now - baroAt < BARO_FRESH_MS
        val alt = (if (useBaro) baro else gps) ?: return null
        if (useBaro != fromBaro) {
            track.clear()
            fromBaro = useBaro
        }
        track.addLast(driven to alt)
        while (track.size > 2 && track[1].first <= driven - WINDOW_M) track.removeFirst()
        if (driven - track.first().first < WINDOW_M * 0.9 || mps < MIN_MPS) return null

        val change = alt - track.first().second
        if (change < REARM_M) climbArmed = true
        if (change > -REARM_M) sinkArmed = true
        if (change >= TRIGGER_M && climbArmed) {
            climbArmed = false
            if (now - climbAt >= COOLDOWN_MS) {
                climbAt = now
                return Call.TERRAIN
            }
        }
        if (change <= -TRIGGER_M && sinkArmed) {
            sinkArmed = false
            if (now - sinkAt >= COOLDOWN_MS) {
                sinkAt = now
                return Call.DONT_SINK
            }
        }
        return null
    }

    /** "+18 M / 400 M" style readout of the current grade, for the log. */
    fun grade(): String? {
        if (track.size < 2) return null
        val span = driven - track.first().first
        if (span < 50) return null
        return "%+.0f m over %.0f m (%s)".format(track.last().second - track.first().second, span, if (fromBaro) "baro" else "GPS")
    }

    /** New trip: forget the old road, keep the barometer running. */
    fun reset() {
        track.clear()
        driven = 0.0
        lastLat = Double.NaN
        gps = null
        climbArmed = true
        sinkArmed = true
    }

    // Flat-earth distance: plenty for the few metres between two GPS fixes.
    private fun metres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dy = (lat2 - lat1) * 110_540
        val dx = (lon2 - lon1) * 111_320 * cos(Math.toRadians(lat1))
        return Math.sqrt(dx * dx + dy * dy)
    }
}
