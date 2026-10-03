package dev.gpws.auto

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.sin

/**
 * Live traffic on the road just ahead, from TomTom's Traffic Flow API (the user brings a free key).
 * Google Maps doesn't share its red traffic line, so this asks TomTom about the spot the car will
 * reach in ~12 s. Everything runs on one background thread with short timeouts: a slow or missing
 * network never touches the callouts or the screen, and a failed check is just skipped.
 */
object Traffic {

    /** TomTom's reading for one road segment. */
    class Flow(val aheadMetres: Int, val currentKmh: Int, val freeFlowKmh: Int, val confidence: Double, val closed: Boolean) {
        /** Red on the map: far below the usual speed, or closed. */
        val jammed get() = closed || (confidence >= 0.6 && freeFlowKmh >= 25 && currentKmh < freeFlowKmh * 0.4)
    }

    const val INTERVAL_MS = 20_000L

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var lastCheck = -INTERVAL_MS
    private var busy = false
    private var lastError = ""

    /** Called with each GPS fix while navigating; checks at most every [INTERVAL_MS], only while moving. */
    fun onFix(key: String, lat: Double, lon: Double, bearing: Float, mps: Double, result: (Flow) -> Unit) {
        val now = SystemClock.elapsedRealtime()
        if (key.isBlank() || busy || mps < 4 || now - lastCheck < INTERVAL_MS) return
        lastCheck = now
        busy = true
        val ahead = (mps * 12).coerceIn(100.0, 250.0)  // 100–200 m ahead at town speeds
        val (pLat, pLon) = project(lat, lon, bearing.toDouble(), ahead)
        worker.execute {
            val flow = try {
                query(key, pLat, pLon, ahead)
            } catch (e: Exception) {
                report(e.message ?: e.javaClass.simpleName)
                null
            }
            main.post {
                busy = false
                if (flow != null) result(flow)
            }
        }
    }

    /** One-off check for the settings page, to see whether a key works. */
    fun test(key: String, lat: Double, lon: Double, done: (String) -> Unit) {
        worker.execute {
            val msg = try {
                val f = query(key, lat, lon, 0.0)
                "Key works. Nearby road: ${f.currentKmh} km/h now, ${f.freeFlowKmh} km/h normally."
            } catch (e: Exception) {
                "Didn't work: ${e.message ?: e.javaClass.simpleName}"
            }
            main.post { done(msg) }
        }
    }

    private fun query(key: String, lat: Double, lon: Double, ahead: Double): Flow {
        val url = URL(
            String.format(
                Locale.US,
                "https://api.tomtom.com/traffic/services/4/flowSegmentData/absolute/12/json?point=%.6f,%.6f&unit=KMPH&key=%s",
                lat, lon, URLEncoder.encode(key.trim(), "UTF-8"),
            )
        )
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 4000
        c.readTimeout = 4000
        try {
            val code = c.responseCode
            if (code == 403 || code == 401) throw IllegalStateException("TomTom rejected the key (HTTP $code)")
            if (code != 200) throw IllegalStateException("TomTom HTTP $code")
            val body = c.inputStream.bufferedReader().use { it.readText() }
            val j = JSONObject(body).getJSONObject("flowSegmentData")
            return Flow(
                ahead.toInt(),
                j.getInt("currentSpeed"),
                j.getInt("freeFlowSpeed"),
                j.optDouble("confidence", 1.0),
                j.optBoolean("roadClosure", false),
            )
        } finally {
            c.disconnect()
        }
    }

    // Log each distinct failure once, so a dead network doesn't flood the event log.
    private fun report(error: String) {
        if (error == lastError) return
        lastError = error
        main.post { Events.add("traffic check failed: $error") }
    }

    /** The point [metres] along [bearing] from here (flat-earth is plenty over 250 m). */
    private fun project(lat: Double, lon: Double, bearing: Double, metres: Double): Pair<Double, Double> {
        val b = Math.toRadians(bearing)
        val dLat = metres * cos(b) / 111_320.0
        val dLon = metres * sin(b) / (111_320.0 * cos(Math.toRadians(lat)))
        return lat + dLat to lon + dLon
    }
}
