package dev.gpws.auto

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.os.LocaleList
import java.util.Locale
import kotlin.math.abs

/**
 * The parser against Maps notification samples: the Android 16+ Live Update, the classic text in
 * km and in miles, and the classic text in other languages and scripts. Status → Maps self-test.
 */
object MapsSelfTest {

    class Sample(val name: String, val fields: NavParser.Fields, val metres: Double?, val arrived: Boolean = false)

    private const val PROGRESS_STYLE = "android.app.Notification\$ProgressStyle"
    private const val COMPAT_PROGRESS_STYLE = "androidx.core.app.NotificationCompat\$ProgressStyle"

    private fun liveUpdate(title: String, sub: String, progress: Int, max: Int) =
        NavParser.Fields(listOf(title, sub), PROGRESS_STYLE, progress, max, false, sub, COMPAT_PROGRESS_STYLE)

    private fun classic(vararg lines: String) = NavParser.Fields(lines.toList())

    // Classic: the next turn, its instruction, then the trip summary with 4.6 km to go.
    private fun summary(name: String, turn: String, instruction: String, trip: String) =
        Sample("Classic, $name", classic(turn, instruction, trip), 4600.0)

    val SAMPLES = listOf(
        Sample("Live Update (Android 16+)", liveUpdate("120 m · At the roundabout, take the 2nd exit onto NDA Rd", "Arrive 6:51 pm", 305, 3489), 3184.0),
        Sample("Live Update, setting off", liveUpdate("Head toward Lane No. 2", "Arrive 6:56 pm", 2, 3490), 3488.0),
        Sample("Live Update, Hindi", liveUpdate("120 मी · गोल चक्कर से दूसरा निकास लें", "पहुंचें 6:51 pm", 305, 3489), 3184.0),
        Sample("Live Update, Arabic", liveUpdate("١٢٠ م · انعطف يسارًا", "الوصول ٦:٥١ م", 305, 3489), 3184.0),
        Sample("Live Update, Japanese", liveUpdate("120 m · 左折", "18:51 に到着", 305, 3489), 3184.0),
        Sample("Live Update, miles", liveUpdate("0.6 mi · Turn left onto Raj Bhavan Rd", "Arrive 7:28 pm", 1515, 3038), 1523.0),
        Sample(
            "Live Update, androidx style only",
            NavParser.Fields(listOf("120 m · Turn left"), null, 305, 3489, false, null, COMPAT_PROGRESS_STYLE), 3184.0,
        ),
        Sample("Live Update, 2,600 km road trip", liveUpdate("12 km · Continue on NH 48", "Arrive 9:10 am", 1000, 2_600_000), 2_599_000.0),
        Sample("Live Update, reroute blip: skipped", liveUpdate("0.1 mi · Make a U-turn", "Arrive 7:31 pm", 0, 3038), null),
        summary("km", "250 m", "Turn left onto MG Road", "13 min · 4.6 km · 11:55 ETA"),
        Sample("Classic, final stretch", classic("80 m", "Destination will be on the right", "1 min · 0.1 km · 11:55 ETA"), 80.0),
        Sample("Classic, miles", classic("500 ft", "Turn right onto Main St", "12 min · 2.9 mi · 11:55 AM ETA"), 4667.1),
        Sample("Classic, 1 h 20 min trip", classic("2 km", "Continue onto NH 44", "1 hr 20 min · 112 km · 1:15 PM ETA"), 112_000.0),
        summary("Hindi", "250 मी", "एमजी रोड पर बाएं मुड़ें", "13 मिनट · 4.6 कि॰मी॰ · 11:55 am"),
        summary("Hindi digits", "२५० मी", "एमजी रोड पर बाएं मुड़ें", "१३ मिनट · ४.६ किमी · ११:५५"),
        summary("Bengali", "২৫০ মি", "বাঁ দিকে ঘুরুন", "১৩ মিনিট · ৪.৬ কিমি · ১১:৫৫"),
        summary("Tamil", "250 மீ", "இடதுபுறம் திரும்பவும்", "13 நிமி · 4.6 கி.மீ. · 11:55"),
        summary("Spanish", "250 m", "Gira a la izquierda en Calle Mayor", "13 min · 4,6 km · 11:55"),
        summary("German", "250 m", "Links abbiegen auf Hauptstraße", "13 Min. · 4,6 km · Ankunft 11:55"),
        summary("French", "250 m", "Tournez à gauche sur Rue de Rivoli", "13 min · 4,6 km · 11:55"),
        summary("Russian", "250 м", "Поверните налево на Тверскую улицу", "13 мин. · 4,6 км · 11:55"),
        summary("Turkish", "250 m", "Atatürk Caddesi'ne sola dönün", "13 dk · 4,6 km · 11:55"),
        summary("Indonesian", "250 m", "Belok kiri ke Jl. Sudirman", "13 mnt · 4,6 km · 11.55"),
        summary("Vietnamese", "250 m", "Rẽ trái vào Lê Lợi", "13 phút · 4,6 km · 11:55"),
        summary("Greek", "250 μ.", "Στρίψτε αριστερά", "13 λεπ. · 4,6 χλμ. · 11:55"),
        summary("Arabic", "٢٥٠ م", "انعطف يسارًا إلى شارع الملك فهد", "١٣ د · ٤٫٦ كم · ١١:٥٥ ص"),
        summary("Persian", "۲۵۰ متر", "به چپ بپیچید", "۱۳ دقیقه · ۴٫۶ کیلومتر · ۱۱:۵۵"),
        summary("Hebrew", "250 מ׳", "פנה שמאלה לרחוב הרצל", "‏13 דק׳ · 4.6 ק״מ · 11:55"),
        summary("Chinese", "250 米", "左转进入中山路", "13 分钟 · 4.6 公里 · 11:55 到达"),
        summary("Japanese", "250 m", "左折して国道1号線", "13 分 · 4.6 km · 11:55 着"),
        summary("Korean", "250m앞", "좌회전", "13분 · 4.6km · 오전 11:55 도착"),
        summary("Thai", "250 ม.", "เลี้ยวซ้ายเข้าสู่ถนนสุขุมวิท", "13 นาที · 4.6 กม. · 11:55"),
        Sample("Classic, 1,234 km in German", classic("2 km", "Weiter auf A7", "11 Std. 5 Min. · 1.234 km · 22:40"), 1_234_000.0),
        Sample("Arrived", classic("Arrived at your destination"), null, arrived = true),
        Sample("Next turn only: stays quiet", classic("250 m · Turn left onto 1 St NW"), null),
        Sample("Unknown words: stays quiet", classic("250 zz", "Turn left", "13 qq · 4.6 zz"), null),
    )

    /**
     * A trip summary written the way each of the phone's own languages writes it ("13 мин. · 4,6 км"),
     * from Android's unit data: proves the words learned from the phone read right on this phone.
     */
    private fun phoneSamples(): List<Sample> = try {
        val locales = LocaleList.getDefault()
        (0 until locales.size()).map { i ->
            val f = MeasureFormat.getInstance(locales[i], MeasureFormat.FormatWidth.SHORT)
            val trip = "${f.format(Measure(13, MeasureUnit.MINUTE))} · ${f.format(Measure(4.6, MeasureUnit.KILOMETER))} · 11:55"
            Sample("Your phone's ${locales[i].displayName}", classic("250 m", "Turn left", trip), 4600.0)
        }
    } catch (e: Throwable) {
        emptyList()  // no unit data on this Android: the built-in tables are all there is
    }

    /** Every sample through the parser: whether it read what a real phone would expect, and what it got. */
    fun selfTest(): List<Pair<Boolean, String>> {
        NavParser.learnPhoneUnits()  // as on a drive: the phone's own words in, before the first sample
        return (SAMPLES + phoneSamples()).map { check(it) }
    }

    private fun check(s: Sample): Pair<Boolean, String> {
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
        return ok to "${s.name}: $said"
    }
}
