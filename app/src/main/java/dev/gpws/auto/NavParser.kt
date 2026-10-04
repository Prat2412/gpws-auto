package dev.gpws.auto

import android.app.Notification
import android.content.Context
import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.os.LocaleList
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow

/**
 * Pulls "metres to destination" out of a Google Maps (or Waze) navigation notification, in either
 * of Maps' two layouts and in any language.
 *
 * Android 16+: a Live Update ("120 m · At the roundabout…", "Arrive 6:56 pm"). Its progress bar
 * is the trip, in metres: progressMax is the route's length and progress how far along it you are.
 * Plain numbers, so the language and units don't matter.
 *
 * Older Android and older Maps: the distance to the next turn in the title ("250 m") and a trip
 * summary in the subtext ("13 min · 4.6 km · 11:55 ETA"). The summary is what we want, except on
 * the final stretch: there the next "turn" is the destination itself and Maps shows it more
 * precisely. Digits in any script are read ("٤٫٦ كم" is 4.6 km), and the units and time words of
 * about 40 languages are listed below, plus whatever the phone's own languages call them.
 */
object NavParser {

    const val MAPS = "com.google.android.apps.maps"
    val PACKAGES = setOf(MAPS, "com.waze")

    /** [resolution] is how coarse Maps' number is: "0.4 km" is only good to 100 m. */
    data class Reading(
        val metres: Double?,
        val resolution: Double,
        val arrived: Boolean,
        val rerouting: Boolean,
        val lines: List<String>,
        val minutes: Int? = null,  // time left, from the same trip summary
        val instruction: String = "",  // the next turn, without its distance: "Take the ramp onto NH 48"
        val turnMetres: Double? = null,  // how far off that turn is
        val exact: Boolean = false,  // from the Live Update bar: to the metre, but only every few seconds
        val liveUpdate: Boolean = false,  // a Live Update, even when this one's bar had nothing usable
    )

    /** What the parser reads from a notification: its text lines, and its progress bar if it has one. */
    class Fields(
        val lines: List<String>,
        val template: String? = null,
        val progress: Int = -1,
        val max: Int = 0,
        val indeterminate: Boolean = false,
        val sub: String? = null,
        val compatTemplate: String? = null,  // androidx's name for the style, there even where Android lacks it
    )

    private class Distance(val metres: Double, val resolution: Double)

    private const val KM = 1000.0
    private const val M = 1.0
    private const val MI = 1609.344
    private const val FT = 0.3048
    private const val YD = 0.9144
    private const val MAX_TRIP_M = 20_000_000  // half way round the world: anything longer is a bad bar

    // How Maps and Android write each unit, by language. Matched right after a number, ignoring case.
    private val UNITS: Map<String, Double> = HashMap<String, Double>().apply {
        fun add(metres: Double, words: String) = words.trim().split(Regex("\\s+")).forEach { put(it.lowercase(), metres) }
        add(
            KM,
            """km kms kilometre kilometres kilometer kilometers kilómetros quilômetros quilómetros chilometri
            kilomètres kilometrów kilometrů км км. кілометрів километра километров كم كلم كيلومتر كيلومترات
            کیلومتر کلومیٹر ק״מ ק"מ קמ קילומטר קילומטרים किमी कि.मी. कि.मी कि॰मी॰ कि॰मी किलोमीटर कि.मि.
            কিমি কি.মি. কিঃমিঃ কিলোমিটার ਕਿ.ਮੀ. ਕਿਮੀ ਕਿਲੋਮੀਟਰ કિમી કિ.મી. કિલોમીટર କି.ମି. କିମି கி.மீ. கி.மீ
            கிமீ கிலோமீட்டர் కి.మీ. కి.మీ కిమీ కిలోమీటర్లు ಕಿ.ಮೀ. ಕಿ.ಮೀ ಕಿಮೀ ಕಿಲೋಮೀಟರ್ കി.മീ. കി.മീ കിമീ
            കിലോമീറ്റർ කි.මී. කිමී กม. กม กิโลเมตร ກມ. ກມ ກິໂລແມັດ គ.ម. គីឡូម៉ែត្រ ကီလိုမီတာ 公里 千米 公裏
            キロ キロメートル ㎞ 킬로미터 χλμ. χλμ χιλ. χιλιόμετρα կմ კმ ኪ.ሜ ኪ.ሜ. ኪሜ""",
        )
        add(
            M,
            """m metre metres meter meters metros metri mètres metrów metrů м м. метр метра метров метри метрів
            م متر مترا أمتار میٹر מ׳ מ' מ מטר מטרים मी मी. मी॰ मीटर মি মিটার ਮੀ ਮੀ. ਮੀਟਰ મી મી. મીટર ମି ମି.
            ମିଟର மீ மீ. மீட்டர் మీ మీ. మీటర్లు మీటర్ ಮೀ ಮೀ. ಮೀಟರ್ മീ മീ. മീറ്റർ මී. මී මීටර් ม. ม เมตร ມ. ມ
            ແມັດ ម៉ែត្រ ម မီတာ 米 公尺 メートル ｍ 미터 μ. μ μέτρα մ მ ሜ ሜ. ሜትር""",
        )
        add(
            MI,
            """mi mile miles milla millas milha milhas miglio miglia meile meilen mijl mijlen миля мили миль ми
            ميل ميلا أميال مایل میل מייל מיילים मील মাইল ਮੀਲ માઇલ ମାଇଲ மைல் మైలు మైళ్లు ಮೈಲಿ ಮೈಲು മൈൽ ไมล์
            ໄມລ໌ ម៉ាយ မိုင် 英里 哩 マイル 마일 μίλια μίλι μιλ. մղոն მილი ማይል""",
        )
        add(
            FT,
            """ft feet foot pie pies pé pés piede piedi pied pieds fuß fuss voet stopa stóp stopy фт фут фута
            футов قدم أقدام فوت פוט רגל फ़ुट फुट ফুট ਫੁੱਟ ફૂટ ଫୁଟ அடி అడుగులు అడుగు ಅಡಿ അടി ฟุต ຟຸດ ហ្វីត ပေ
            英尺 呎 フィート 피트 πόδια πόδι ποδ. ոտնաչափ ფუტი""",
        )
        add(
            YD,
            """yd yds yard yards yarda yardas iarda iarde yarde ярд ярда ярдов ياردة ياردات יארד יארדים गज গজ
            ਗਜ਼ યાર્ડ 码 碼 ヤード 야드 γιάρδα γιάρδες""",
        )
    }

    // Minutes and hours: a line with these (or a clock time) next to a distance is the trip summary.
    private val MINUTE_WORDS = words(
        """min mins min. minute minutes minuto minutos minuti minuten minut minuty minutter minuter minuut
        minutu minuta minutė mín mín. perc dk dak dakika phút mnt menit minit daqiqa мин мин. минут минуты
        минута хв хв. хвилин хвилини дақ дақ. د د. دقيقة دقائق دقيقه دقیقه منٹ דק׳ דק' דקות דקה मिनट मि मि.
        मि॰ মিনিট মিঃ ਮਿੰਟ મિનિટ ମିନିଟ நிமி நிமி. நிமிடம் நிமிடங்கள் నిమి నిమి. నిమిషాలు నిమిషం ನಿಮಿ ನಿಮಿ.
        ನಿಮಿಷ ನಿಮಿಷಗಳು മിനിറ്റ് මිනි මිනි. මිනිත්තු นาที ນາທີ នាទី မိနစ် 分 分钟 分鐘 분 λεπ. λεπ λεπτά
        λεπτό րոպե րոպ. წთ წთ. წუთი ደቂቃ""",
    )
    private val HOUR_WORDS = words(
        """h hr hrs hr. hour hours hora horas ora ore heure heures std std. stunde stunden uur tim tim. timmar
        timer godz godz. godzina godziny hod hod. hodin óra saat giờ jam ч ч. час часа часов год год. годин
        сағ сағ. س س. ساعة ساعات ساعت گھنٹے گھنٹہ ש׳ ש' שע׳ שעות שעה घंटे घं घं. घंटा ঘণ্টা ঘঃ ਘੰਟੇ ਘੰਟਾ
        કલાક ଘଣ୍ଟା மணி மணிநேரம் గం గం. గంటలు గంట ಗಂ ಗಂ. ಗಂಟೆ ಗಂಟೆಗಳು മണിക്കൂർ පැය ชม. ชั่วโมง ຊມ.
        ຊົ່ວໂມງ ម៉ោង နာရီ 小时 小時 時間 시간 ώ ώ. ώρα ώρες ժ ժ. სთ სთ. საათი ሰዓት soat klst. klst val.""",
    )

    // Said once you're there, in past tense only: "arrival 11:55" must not count.
    private val ARRIVED = phrases(
        "\\barrived\\b",
        "has llegado", "llegaste", "você chegou", "chegou ao destino", "êtes arrivé", "sei arrivat",
        "ziel erreicht", "angekommen", "bent aangekomen", "dotarłeś", "вы прибыли", "ви прибули", "vardınız",
        "đã đến", "telah tiba", "وصلت", "הגעת", "पहुंच गए", "पहुँच गए", "到着しました", "已到达", "已抵達", "도착했습니다",
    )
    private val REROUTING = phrases(
        "rerout", "recalcul", "ricalcol", "neu berechn", "neuberechn", "herberekenen", "opnieuw berekenen", "przelicz",
        "перестро", "пересчит", "перерасч", "перебудов", "yeniden hesapla", "tính lại", "menghitung ulang",
        "重新规划", "重新規劃", "重新计算", "重新計算", "再検索", "재탐색", "מחשב מחדש", "إعادة حساب", "إعادة التوجيه",
    )

    private fun words(list: String) = list.trim().split(Regex("\\s+")).map { it.lowercase() }.toSet()

    private fun phrases(vararg p: String) = Regex(p.joinToString("|") { if (it.startsWith("\\b")) it else Regex.escape(it) }, RegexOption.IGNORE_CASE)

    // A number, with its thousands marks: "4.6", "4,6", "250", "1,234", "1 234". Never the tail of a clock time.
    private const val NUMBER = """(?<![\d.,:])(\d{1,3}(?:[ ,.'’]\d{3})+(?:[.,]\d+)?|\d+(?:[.,]\d+)?)"""
    private val CLOCK = Regex("""(?<![\d.,:])(\d{1,2}):(\d{2})(?!\d)""")
    private val LATIN_AM_PM = Regex("""^\s*([ap])\.?\s?m\b""", RegexOption.IGNORE_CASE)

    // CJK, Thai and their neighbours write without spaces, so their units can run straight into the
    // next word. Everywhere else a unit must end where its word does: "5 min" is not "5 m". A letter
    // from one of those scripts may still follow, as in Korean "250m앞".
    private val UNSPACED = setOf(
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HANGUL, Character.UnicodeScript.THAI, Character.UnicodeScript.LAO,
        Character.UnicodeScript.KHMER, Character.UnicodeScript.MYANMAR,
    )
    private const val WORD_END = "(?:(?![\\p{L}\\p{M}])|(?=[\\u0E00-\\u0EFF\\u1000-\\u109F\\u1100-\\u11FF" +
        "\\u1780-\\u17FF\\u3040-\\u30FF\\u3130-\\u318F\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uAC00-\\uD7AF\\uF900-\\uFAFF]))"

    private fun alternatives(words: Collection<String>): String {
        fun alt(ws: List<String>) = ws.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        val (open, spaced) = words.partition { w ->
            w.firstOrNull { it.isLetter() }?.let { Character.UnicodeScript.of(it.code) in UNSPACED } == true
        }
        return listOfNotNull(
            spaced.takeIf { it.isNotEmpty() }?.let { "(?:${alt(it)})$WORD_END" },
            open.takeIf { it.isNotEmpty() }?.let { "(?:${alt(it)})" },
        ).joinToString("|")
    }

    /** The regexes for one set of words: the built-in tables, plus the phone's own once learned. */
    private class Patterns(val units: Map<String, Double>, minuteWords: Set<String>, hourWords: Set<String>) {
        val distance = Regex("""$NUMBER *(${alternatives(units.keys)})""", RegexOption.IGNORE_CASE)
        val minutes = Regex("""(?<![\d.,:])(\d+) *(?:${alternatives(minuteWords)})""", RegexOption.IGNORE_CASE)
        val hours = Regex("""(?<![\d.,:])(\d+) *(?:${alternatives(hourWords)})""", RegexOption.IGNORE_CASE)
    }

    private var rx = Patterns(UNITS, MINUTE_WORDS, HOUR_WORDS)
    private var learned = false

    /**
     * Adds what the phone's own languages call km, m, mi, ft, yd, minutes and hours (Android's
     * CLDR unit data, the same source Maps' translations follow), for anything the tables miss.
     */
    private fun learnPhoneUnits() {
        if (learned) return
        learned = true
        try {
            val units = HashMap(UNITS)
            val minuteWords = HashSet(MINUTE_WORDS)
            val hourWords = HashSet(HOUR_WORDS)
            val locales = LocaleList.getDefault()
            for (i in 0 until locales.size()) {
                for (width in listOf(MeasureFormat.FormatWidth.SHORT, MeasureFormat.FormatWidth.WIDE)) {
                    val f = MeasureFormat.getInstance(locales[i], width)
                    fun said(unit: MeasureUnit) = listOf<Number>(1, 2, 3, 5, 11, 21, 1.5, 0.5).mapNotNull { unitWord(f.format(Measure(it, unit))) }
                    for ((unit, metres) in listOf(
                        MeasureUnit.KILOMETER to KM, MeasureUnit.METER to M, MeasureUnit.MILE to MI,
                        MeasureUnit.FOOT to FT, MeasureUnit.YARD to YD,
                    )) {
                        said(unit).forEach { if (it !in units && it !in MINUTE_WORDS && it !in HOUR_WORDS) units[it] = metres }
                    }
                    // Never a lone Latin letter ("t", "u"): road numbers like "Route 7 T" would pass for times.
                    fun time(w: String) = w.length > 1 || w[0] !in 'a'..'z'
                    minuteWords += said(MeasureUnit.MINUTE).filter(::time)
                    hourWords += said(MeasureUnit.HOUR).filter(::time)
                }
            }
            // A word that's both a unit and a time ("m" for minutes, somewhere) is safer as neither time.
            minuteWords.removeAll(units.keys)
            hourWords.removeAll(units.keys)
            rx = Patterns(units, minuteWords, hourWords)
        } catch (e: Throwable) {
            // Old or trimmed-down Android without the unit data: the built-in tables still work.
        }
    }

    /** "5 км" → "км": the unit word of a formatted measure, or null if it isn't a single word. */
    private fun unitWord(formatted: String): String? {
        val w = normalize(formatted).replace(Regex("""\d+(?:[.,]\d+)?"""), " ").trim().lowercase()
        return w.takeIf { it.length in 1..16 && ' ' !in it && it.any { c -> c.isLetter() } }
    }

    private val BIDI = setOf('‎', '‏', '؜', '​', '⁠', '﻿') +
        ('‪'..'‮') + ('⁦'..'⁩')

    /**
     * The text with plain 0-9 digits whatever script Maps wrote them in (Arabic, Devanagari, Thai…),
     * '.' for the Arabic decimal mark, plain spaces, and no invisible direction marks around numbers.
     */
    fun normalize(s: CharSequence): String {
        val b = StringBuilder(s.length)
        for (c in s) {
            when {
                c in '0'..'9' -> b.append(c)
                Character.isDigit(c) -> b.append('0' + Character.digit(c, 10))
                c == '٫' || c == '．' -> b.append('.')  // Arabic decimal mark, fullwidth stop
                c == '٬' -> b.append(',')                     // Arabic thousands mark
                c == '：' -> b.append(':')                     // fullwidth colon in CJK times
                c in BIDI -> {}
                Character.isSpaceChar(c) || c == '\t' -> b.append(' ')
                else -> b.append(c)
            }
        }
        return b.toString()
    }

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
        learnPhoneUnits()
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
        if (r.metres != null || r.arrived || r.liveUpdate) return r
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
            e.getString("androidx.core.app.extra.COMPAT_TEMPLATE"),
        )
    }

    /** The reading from a notification's fields: the progress bar if it's a Live Update, else the text. */
    fun parse(f: Fields): Reading {
        val r = parseText(f.lines)
        val live = f.template?.endsWith("ProgressStyle") == true || f.compatTemplate?.endsWith("ProgressStyle") == true
        if (!live) return r
        val left = tripBar(f) ?: return r.copy(liveUpdate = true)
        return fromBar(r, left, f.sub)
    }

    private fun parseText(original: List<String>): Reading {
        val p = rx
        val lines = original.map { normalize(it) }
        var trip: Distance? = null
        var turn: Distance? = null
        var minutes: Int? = null
        val summaries = HashSet<Int>()
        for ((i, line) in lines.withIndex()) {
            val found = p.distance.findAll(line).mapNotNull { m -> toDistance(m, p.units)?.let { m.range to it } }.toList()
            if (found.isEmpty()) continue
            // Where the line talks time: "13 min", "1 h", or a clock like the ETA "11:55".
            val times = (p.minutes.findAll(line) + p.hours.findAll(line) + CLOCK.findAll(line)).map { it.range }.toList()
            if (times.isNotEmpty()) {
                summaries += i
                if (trip == null) {
                    // The trip is the distance next to the time, should a line ever hold the turn's too.
                    trip = found.minByOrNull { (range, _) -> times.minOf { gap(range, it) } }!!.second
                    val h = p.hours.find(line)?.groupValues?.get(1)?.toIntOrNull()
                    val min = p.minutes.find(line)?.groupValues?.get(1)?.toIntOrNull()
                    if (h != null || min != null) minutes = (h ?: 0) * 60 + (min ?: 0)
                }
            } else if (turn == null) {
                turn = found.first().second
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
            original,
            minutes,
            lines.filterIndexed { i, _ -> i !in summaries }
                .map { p.distance.replace(it, "").trim(' ', ',', '·', '-') }
                .filter { it.isNotEmpty() }
                .joinToString(" · "),
            u?.metres,
        )
    }

    private fun gap(a: IntRange, b: IntRange) = maxOf(0, maxOf(a.first, b.first) - minOf(a.last, b.last))

    /** Metres left on a Live Update's progress bar, or null if it has nothing usable right now. */
    private fun tripBar(f: Fields): Double? {
        if (f.indeterminate) return null
        // While Maps reroutes it posts the new route's length with progress 0 for an update or two,
        // as if you were back at the start: a false jump. Skip those; the next reading has it right.
        if (f.max !in 1..MAX_TRIP_M || f.progress !in 1..f.max) return null
        return (f.max - f.progress).toDouble()
    }

    /** The text reading [r] with the bar's exact distance in charge, and minutes from "Arrive 6:56 pm". */
    private fun fromBar(r: Reading, left: Double, sub: String?): Reading {
        // A classic summary, if Maps still sends one, agrees anyway; the bar is to the metre.
        val minutes = r.minutes ?: sub?.let { untilClock(it) }
        return r.copy(
            metres = left,
            resolution = 10.0,
            lines = r.lines + String.format(Locale.US, "progress bar: %.0f m to go", left),
            minutes = minutes,
            exact = true,
            liveUpdate = true,
        )
    }

    private fun nowMinutes(): Int = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

    /**
     * Minutes from now until a clock time: "6:56 pm", "18:56", "오후 6:56", "٦:٥٦ م". Where the
     * morning/afternoon word isn't English, a 12-hour time is whichever of the two comes next.
     */
    fun untilClock(s: String, now: Int = nowMinutes()): Int? {
        val t = normalize(s)
        val m = CLOCK.find(t) ?: return null
        val hText = m.groupValues[1]
        val h = hText.toInt()
        val min = m.groupValues[2].toInt()
        if (h > 23 || min > 59) return null
        val ampm = LATIN_AM_PM.find(t.substring(m.range.last + 1))?.groupValues?.get(1)?.lowercase()
        val hours = when {
            ampm != null && h in 1..12 -> listOf(h % 12 + if (ampm == "p") 12 else 0)
            h == 0 || h > 12 || hText.startsWith("0") -> listOf(h)  // a 24-hour clock
            else -> listOf(h % 12, h % 12 + 12)
        }
        // -5: a minute or two late is still "arriving now", not tomorrow.
        return hours.minOf { ((it * 60 + min - now + 5) % 1440 + 1440) % 1440 - 5 }.coerceAtLeast(0)
    }

    /** "4.6" → 4.6 and 1 decimal; "1,234" or "1.234" → 1234 (a thousands mark: Maps never shows 3 decimals). */
    private fun number(s: String): Pair<Double, Int> {
        val marks = s.indices.filter { !s[it].isDigit() }
        if (marks.isEmpty()) return s.toDouble() to 0
        val last = marks.last()
        val tail = s.length - last - 1
        val lead = s.substring(0, last).filter { it.isDigit() }
        val decimal = s[last] in ".," && (tail != 3 || lead == "0") && marks.dropLast(1).none { s[it] == s[last] }
        return if (decimal) "$lead.${s.substring(last + 1)}".toDouble() to tail else s.filter { it.isDigit() }.toDouble() to 0
    }

    private fun toDistance(m: MatchResult, units: Map<String, Double>): Distance? {
        val unit = units[m.groupValues[2].lowercase()] ?: return null
        val (n, decimals) = number(m.groupValues[1])
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
