package dev.gpws.auto

import java.util.Locale
import kotlin.math.abs

/**
 * The parser against Maps notification samples copied from real phones: the Android 16+ Live
 * Update, the classic text in km and in miles, and other languages. Status → Maps self-test.
 */
object MapsSelfTest {

    class Sample(val name: String, val fields: NavParser.Fields, val metres: Double?, val arrived: Boolean = false)

    private const val PROGRESS_STYLE = "android.app.Notification\$ProgressStyle"

    private fun liveUpdate(title: String, sub: String, progress: Int, max: Int) =
        NavParser.Fields(listOf(title, sub), PROGRESS_STYLE, progress, max, false, sub)

    private fun classic(vararg lines: String) = NavParser.Fields(lines.toList())

    val SAMPLES = listOf(
        Sample("Live Update (Android 16+)", liveUpdate("120 m · At the roundabout, take the 2nd exit onto NDA Rd", "Arrive 6:51 pm", 305, 3489), 3184.0),
        Sample("Live Update, setting off", liveUpdate("Head toward Lane No. 2", "Arrive 6:56 pm", 2, 3490), 3488.0),
        Sample("Live Update, Maps in Hindi", liveUpdate("120 मी · गोल चक्कर से दूसरा निकास लें", "पहुंचें 6:51 pm", 305, 3489), 3184.0),
        Sample("Live Update, miles", liveUpdate("0.6 mi · Turn left onto Raj Bhavan Rd", "Arrive 7:28 pm", 1515, 3038), 1523.0),
        Sample("Live Update, reroute blip: skipped", liveUpdate("0.1 mi · Make a U-turn", "Arrive 7:31 pm", 0, 3038), null),
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
}
