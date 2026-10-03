package dev.gpws.auto

import android.app.Notification
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.pow

/**
 * Pulls "metres to destination" out of a Google Maps (or Waze) navigation notification.
 *
 * Google Maps puts the distance to the next turn in the title ("250 m") and a trip summary in
 * the subtext ("13 min · 4.6 km · 11:55 ETA"). The summary is what we want, except on the final
 * stretch: there the next "turn" is the destination itself and Maps shows it more precisely.
 *
 * On Android 16+ Maps posts a Live Update instead ("120 m · At the roundabout…", "Arrive 6:56 pm")
 * with no trip distance in the text. The trip is its progress bar, in metres: progressMax is the
 * route's length and progress how far along it you are. Numbers, so any Maps language works.
 */
object NavParser {

    val PACKAGES = setOf("com.google.android.apps.maps", "com.waze")

    /** [resolution] is how coarse Maps' number is: "0.4 km" is only good to 100 m. */
    class Reading(
        val metres: Double?,
        val resolution: Double,
        val arrived: Boolean,
        val rerouting: Boolean,
        val lines: List<String>,
        val minutes: Int? = null,  // time left, from the same trip summary
        val instruction: String = "",  // the next turn, without its distance: "Take the ramp onto NH 48"
        val turnMetres: Double? = null,  // how far off that turn is
    )

    /** What the parser reads from a notification: its text lines, and its progress bar if it has one. */
    class Fields(
        val lines: List<String>,
        val template: String? = null,
        val progress: Int = -1,
        val max: Int = 0,
        val indeterminate: Boolean = false,
        val sub: String? = null,
    )

    private class Distance(val metres: Double, val resolution: Double)

    private const val SP = """[\s\u00A0\u202F]*"""  // any spacing, incl. the non-breaking kinds Maps uses
    private val DISTANCE = Regex("""(\d+(?:[.,]\d+)?)$SP(km|mi|m|ft|yd)\b""", RegexOption.IGNORE_CASE)
    private val TIME = Regex("""\d+$SP(min|mins|h|hr|hrs|hours?)\b|\bETA\b|\d{1,2}:\d{2}""", RegexOption.IGNORE_CASE)
    private val ARRIVED = Regex("""\barrived\b""", RegexOption.IGNORE_CASE)
    private val REROUTING = Regex("""\brerout|recalculat""", RegexOption.IGNORE_CASE)
    private val HOURS = Regex("""(\d+)$SP(?:h|hr|hrs|hours?)\b""", RegexOption.IGNORE_CASE)
    private val MINUTES = Regex("""(\d+)$SP(?:min|mins)\b""", RegexOption.IGNORE_CASE)
    private val CLOCK = Regex("""(\d{1,2}):(\d{2})$SP([ap]\.?m\.?)?""", RegexOption.IGNORE_CASE)

    /** Every simple value in the notification's extras, for the drive log: finds data we don't parse yet. */
    @Suppress("DEPRECATION")
    fun extras(n: Notification): String {
        val e = n.extras
        return e.keySet().sorted().mapNotNull { key ->
            val v = e.get(key) ?: return@mapNotNull null
            val s = when (v) {
                is CharSequence, is Number, is Boolean -> v.toString()
                is Array<*> -> v.filterIsInstance<CharSequence>().joinToString("|")
                is IntArray -> v.joinToString(",")
                else -> return@mapNotNull "$key=<${v.javaClass.simpleName}>"
            }
            "$key=${s.replace('\n', ' ').take(120)}"
        }.joinToString("; ") + "; category=${n.category}; flags=0x${Integer.toHexString(n.flags)}"
    }

    @Suppress("DEPRECATION")
    fun read(ctx: Context, n: Notification): Reading {
        val lines = ArrayList<String>()
        val e = n.extras
        for (key in listOf(
            Notification.EXTRA_TITLE, Notification.EXTRA_TITLE_BIG, Notification.EXTRA_TEXT,
            Notification.EXTRA_BIG_TEXT, Notification.EXTRA_SUB_TEXT, Notification.EXTRA_INFO_TEXT,
            Notification.EXTRA_SUMMARY_TEXT,
        )) {
            add(e.getCharSequence(key), lines)
        }
        e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { add(it, lines) }
        add(n.tickerText, lines)

        val r = parse(fields(n, lines))
        if (r.metres != null || r.arrived) return r
        // Fallback for custom layouts: the numbers may only exist inside the notification's views.
        for (rv in listOf(n.bigContentView, n.contentView, n.headsUpContentView)) {
            if (rv != null) viewText(ctx, rv, lines)
        }
        return parse(fields(n, lines))
    }

    private fun fields(n: Notification, lines: List<String>): Fields {
        val e = n.extras
        return Fields(
            lines.toList(),
            e.getString(Notification.EXTRA_TEMPLATE),
            e.getInt(Notification.EXTRA_PROGRESS, -1),
            e.getInt(Notification.EXTRA_PROGRESS_MAX, 0),
            e.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false),
            e.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
        )
    }

    /** The reading from a notification's fields: the progress bar if it's a Live Update, else the text. */
    fun parse(f: Fields): Reading {
        val r = parseText(f.lines)
        val left = tripBar(f) ?: return r
        return fromBar(r, left, f.sub)
    }

    private fun parseText(lines: List<String>): Reading {
        var trip: Distance? = null
        var turn: Distance? = null
        var minutes: Int? = null
        for (line in lines) {
            val m = DISTANCE.find(line) ?: continue
            if (TIME.containsMatchIn(line)) {
                if (trip == null) {
                    trip = toDistance(m)
                    val h = HOURS.find(line)?.groupValues?.get(1)?.toInt()
                    val min = MINUTES.find(line)?.groupValues?.get(1)?.toInt()
                    if (h != null || min != null) minutes = (h ?: 0) * 60 + (min ?: 0)
                }
            } else if (turn == null) {
                turn = toDistance(m)
            }
        }
        val t = trip
        val u = turn
        // Only the trip summary says how far the destination is. A turn distance is used just on the
        // final stretch, where it agrees with the summary to within its rounding and is more precise.
        // Without a summary we stay quiet: guessing from turn distances made every turn count down.
        val pick = when {
            t != null && u != null && abs(t.metres - u.metres) <= t.resolution / 2 + 20 -> u
            else -> t
        }
        return Reading(
            pick?.metres,
            pick?.resolution ?: 50.0,
            lines.any { ARRIVED.containsMatchIn(it) },
            lines.any { REROUTING.containsMatchIn(it) },
            lines,
            minutes,
            lines.filterNot { DISTANCE.containsMatchIn(it) && TIME.containsMatchIn(it) }
                .map { DISTANCE.replace(it, "").trim(' ', ',', '·', '-') }
                .filter { it.isNotEmpty() }
                .joinToString(" · "),
            u?.metres,
        )
    }

    /** Metres left on a Live Update's progress bar, or null if this notification isn't one. */
    private fun tripBar(f: Fields): Double? {
        if (f.template?.endsWith("ProgressStyle") != true || f.indeterminate) return null
        if (f.max !in 1..2_000_000 || f.progress !in 0..f.max) return null
        return (f.max - f.progress).toDouble()
    }

    /** The text reading [r] with the bar's exact distance in charge, and minutes from "Arrive 6:56 pm". */
    private fun fromBar(r: Reading, left: Double, sub: String?): Reading {
        // A classic summary, if Maps still sends one, agrees anyway; the bar is to the metre.
        val minutes = r.minutes ?: sub?.let { untilClock(it) }
        return Reading(
            left, 10.0, r.arrived, r.rerouting,
            r.lines + "progress bar: %.0f m to go".format(left),
            minutes, r.instruction, r.turnMetres,
        )
    }

    /** Minutes from now until a clock time like "6:56 pm" or "18:56", or null. */
    private fun untilClock(s: String): Int? {
        val m = CLOCK.find(s) ?: return null
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        val ampm = m.groupValues[3].lowercase().replace(".", "")
        if (ampm == "pm" && h < 12) h += 12
        if (ampm == "am" && h == 12) h = 0
        if (h > 23 || min > 59) return null
        val c = java.util.Calendar.getInstance()
        val nowMin = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
        var diff = h * 60 + min - nowMin
        if (diff < -5) diff += 24 * 60  // arriving after midnight
        return diff.coerceAtLeast(0)
    }

    private fun toDistance(m: MatchResult): Distance {
        val digits = m.groupValues[1].replace(',', '.')
        val n = digits.toDouble()
        val unit = when (m.groupValues[2].lowercase()) {
            "km" -> 1000.0
            "mi" -> 1609.344
            "ft" -> 0.3048
            "yd" -> 0.9144
            else -> 1.0
        }
        val decimals = if ('.' in digits) digits.length - digits.indexOf('.') - 1 else 0
        val step = when {
            decimals > 0 -> 10.0.pow(-decimals)
            unit >= 1000 -> 1.0      // "12 km"
            n >= 100 -> 50.0         // "450 m" / "500 ft": Maps counts down in 50s
            else -> 10.0
        }
        return Distance(n * unit, step * unit)
    }

    private fun add(text: CharSequence?, out: MutableList<String>) {
        if (text == null) return
        for (line in text.split('\n')) {
            val t = line.trim()
            if (t.isNotEmpty() && t !in out) out += t
        }
    }

    private fun viewText(ctx: Context, rv: RemoteViews, out: MutableList<String>) {
        try {
            collect(rv.apply(ctx, FrameLayout(ctx)), out)
        } catch (e: Exception) {
            // Some layouts refuse to inflate outside their own app; nothing more to read then.
        }
    }

    private fun collect(v: View, out: MutableList<String>) {
        if (v is TextView && v.visibility == View.VISIBLE) add(v.text, out)
        if (v is ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i), out)
    }
}
