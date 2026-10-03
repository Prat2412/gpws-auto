package dev.gpws.auto

import android.os.Handler
import android.os.Looper
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/**
 * A pretend Google Maps, for testing indoors.
 *
 * [selfTest] runs the parser over notification samples copied from real phones (the Android 16+
 * Live Update, the classic text in km and in miles, other languages). [drive] fakes a whole Live
 * Update drive, wrong turn included, and feeds it through the same path a real Maps notification
 * takes, so the callouts, GLIDESLOPE and RETARD all play.
 */
object MockMaps {

    class Sample(val name: String, val fields: NavParser.Fields, val metres: Double?, val arrived: Boolean = false)

    private const val PROGRESS_STYLE = "android.app.Notification\$ProgressStyle"

    private fun liveUpdate(title: String, sub: String, progress: Int, max: Int) =
        NavParser.Fields(listOf(title, sub), PROGRESS_STYLE, progress, max, false, sub)

    private fun classic(vararg lines: String) = NavParser.Fields(lines.toList())

    val SAMPLES = listOf(
        Sample("Live Update (Android 16+)", liveUpdate("120 m · At the roundabout, take the 2nd exit onto NDA Rd", "Arrive 6:51 pm", 305, 3489), 3184.0),
        Sample("Live Update, setting off", liveUpdate("Head toward Lane No. 2", "Arrive 6:56 pm", 2, 3490), 3488.0),
        Sample("Live Update, Maps in Hindi", liveUpdate("120 मी · गोल चक्कर से दूसरा निकास लें", "पहुंचें 6:51 pm", 305, 3489), 3184.0),
        Sample("Classic, km", classic("250 m", "Turn left onto MG Road", "13 min · 4.6 km · 11:55 ETA"), 4600.0),
        Sample("Classic, final stretch", classic("80 m", "Destination will be on the right", "1 min · 0.1 km · 11:55 ETA"), 80.0),
        Sample("Classic, miles", classic("500 ft", "Turn right onto Main St", "12 min · 2.9 mi · 11:55 AM ETA"), 4667.1),
        Sample("Arrived", classic("Arrived at your destination"), null, arrived = true),
        Sample("Classic in Hindi: unreadable, as expected", classic("250 मी", "एमजी रोड पर बाएं मुड़ें", "13 मिनट · 4.6 कि॰मी॰"), null),
    )

    /** Every sample through the parser: whether it read what a real phone would expect, and what it got. */
    fun selfTest(): List<Pair<Boolean, String>> = SAMPLES.map { s ->
        val r = NavParser.parse(s.fields)
        val got = r.metres
        val ok = when {
            s.arrived -> r.arrived
            s.metres == null -> got == null && !r.arrived
            else -> got != null && abs(got - s.metres) <= maxOf(2.0, r.resolution)
        }
        val said = when {
            r.arrived -> "arrived"
            got == null -> "no distance"
            else -> String.format(Locale.US, "%.0f m to go", got)
        }
        ok to "${s.name}: $said"
    }

    // ---- The mock drive ----

    private const val ROUTE_M = 700
    private const val WRONG_TURN_AT_M = 400.0  // metres to go when the "driver" misses a turn
    private const val DETOUR_M = 250           // how much longer the new route is
    private const val TICK_MS = 500L

    private val main = Handler(Looper.getMainLooper())
    private var tick: Runnable? = null

    val running get() = tick != null

    /** A 700 m Live Update drive with a missed turn at 400 m to go, about a minute long. */
    fun drive() {
        stop()
        Gpws.startMock()
        var max = ROUTE_M
        var travelled = 0.0
        var missed = false
        var n = 0
        val r = object : Runnable {
            override fun run() {
                if (max - travelled <= 0.5) {
                    feed(classic("Arrived at Mock Destination"))
                    tick = null
                    main.postDelayed({ finish() }, 6000)  // leave time for RETARD, RETARD
                    return
                }
                // Slows down towards the end, like a car pulling up.
                val v = (3 + 0.08 * (max - travelled)).coerceIn(3.0, 20.0)
                travelled = min(max.toDouble(), travelled + v * TICK_MS / 1000)
                if (!missed && max - travelled <= WRONG_TURN_AT_M) {
                    missed = true
                    max += DETOUR_M  // Maps reroutes: the trip gets longer, like a real missed turn
                }
                if (n++ % 2 == 0) {  // Maps updates about once a second
                    val left = max - travelled
                    feed(liveUpdate(
                        "${(left % 300).toInt()} m · Continue on Mock Rd",
                        "Arrive ${clock((left / 12).toInt())}",
                        travelled.toInt(), max,
                    ))
                }
                main.postDelayed(this, TICK_MS)
            }
        }
        tick = r
        main.post(r)
    }

    fun stop() {
        tick?.let { main.removeCallbacks(it) }
        if (tick != null) finish()
        tick = null
    }

    private fun finish() {
        Gpws.onMapsEnded()
        Gpws.stopMock()
    }

    private fun feed(f: NavParser.Fields) {
        val r = NavParser.parse(f)
        Events.showMaps(r, "mock Maps drive")
        Gpws.onMaps(r)
    }

    /** "h:mm pm" this many seconds from now, the way Maps writes its arrival time. */
    private fun clock(seconds: Int): String {
        val c = Calendar.getInstance().apply { add(Calendar.SECOND, seconds) }
        val h = c.get(Calendar.HOUR).let { if (it == 0) 12 else it }
        return String.format(Locale.US, "%d:%02d %s", h, c.get(Calendar.MINUTE), if (c.get(Calendar.AM_PM) == Calendar.AM) "am" else "pm")
    }
}
