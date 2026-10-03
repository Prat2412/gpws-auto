package dev.gpws.auto

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.StatusBarManager
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import java.util.Locale

private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
private const val IMPORT = 1
private const val IMPORT_PACK = 2
private const val BLACK = 0xFF141414.toInt()

/**
 * The app's one screen, drawn in either of two looks (see [Skin]): a paper approach plate or
 * airport terminal signs, switched on the STATUS page. Four tabs along the bottom; everything
 * else is a sub-page with a way back.
 */
class MainActivity : Activity() {

    private enum class Page(val title: String, val tab: Boolean = false) {
        HOME("Approach", true), VOICE("Voice", true), ALERTS("Alerts", true), STATUS("Status", true),
        SOUNDS("Custom sounds"), SETUP("Setup"), LOG("Log"), PFD("PFD"),
    }

    private val tabs = listOf(Page.HOME, Page.VOICE, Page.ALERTS, Page.STATUS)
    private val tabIcons = listOf(R.drawable.ic_tab_approach, R.drawable.ic_tab_voice, R.drawable.ic_tab_alerts, R.drawable.ic_tab_status)

    private lateinit var skin: Skin
    private var page = Page.HOME
    private var parent = Page.STATUS  // where a sub-page's Back goes
    private var pendingUpdate: Updater.Release? = null  // waiting on "allow from this source"
    private lateinit var frame: LinearLayout
    private var refresh: () -> Unit = {}  // re-reads settings and state after an event
    private var live: () -> Unit = {}     // runs 5x a second while the screen is up
    private val main = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            live()
            main.postDelayed(this, 200)
        }
    }

    /** Good news in each look: black on the plate, yellow on the signs. */
    private val ok get() = if (skin.plate) skin.ink else skin.accent

    private fun dp(v: Int) = skin.dp(v)

    /** Back from a sub-page returns to where it was opened; from a tab, to the approach. */
    private fun up(p: Page) = if (p.tab) Page.HOME else parent

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Gpws.init(this)
        Fonts.init(this)
        skin = Skin(this, savedSkin())
        frame = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(frame)
        paint()
        importFile = savedInstanceState?.getString("importFile")
        importLabel = savedInstanceState?.getString("importLabel")
        parent = pageNamed(savedInstanceState?.getString("parent")) ?: Page.STATUS
        // The file picker can outlive this activity; come back to the page it was opened from.
        show(pageNamed(savedInstanceState?.getString("page")) ?: if (prefs().getBoolean("setup_done", false)) Page.HOME else Page.SETUP)
    }

    private fun pageNamed(name: String?) = Page.values().firstOrNull { it.name == name }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString("page", page.name)
        out.putString("parent", parent.name)
        out.putString("importFile", importFile)
        out.putString("importLabel", importLabel)
    }

    override fun onResume() {
        super.onResume()
        Events.onChange = { refresh() }
        refresh()
        main.post(tick)
        pendingUpdate?.let {
            if (packageManager.canRequestPackageInstalls()) {
                pendingUpdate = null
                downloadUpdate(it)
            }
        }
    }

    override fun onPause() {
        Events.onChange = null
        main.removeCallbacks(tick)
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {  // Android 12 and older
        if (page != Page.HOME) show(up(page)) else super.onBackPressed()
    }

    // Android 13+: anywhere but the approach page, Back goes up instead of closing the app.
    private var backCallback: Any? = null

    private fun updateBack() {
        if (Build.VERSION.SDK_INT < 33) return
        val cb = backCallback as? OnBackInvokedCallback ?: OnBackInvokedCallback { show(up(page)) }.also { backCallback = it }
        onBackInvokedDispatcher.unregisterOnBackInvokedCallback(cb)
        if (page != Page.HOME) onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
    }

    /** Opens a sub-page, remembering where Back should lead. */
    private fun open(p: Page) {
        if (!p.tab) parent = page
        show(p)
    }

    private fun show(p: Page) {
        page = p
        refresh = {}
        live = {}
        val content = when (p) {
            Page.HOME -> if (skin.plate) homePlate() else homeSigns()
            Page.VOICE -> voice()
            Page.ALERTS -> alerts()
            Page.STATUS -> status()
            Page.SOUNDS -> sounds()
            Page.SETUP -> setup()
            Page.LOG -> log()
            Page.PFD -> pfd()
        }
        frame.removeAllViews()
        frame.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            addView(content)
        }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        if (p.tab) {
            frame.addView(
                skin.tabs(tabs.indexOf(p), tabs.map { it.title }, tabIcons) { i -> show(tabs[i]) },
                LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(14), dp(4), dp(14), dp(12)) },
            )
        }
        updateBack()
        refresh()
        live()
    }

    /** A sub-page's title, with the way back to where it was opened. */
    private fun subTitle(p: Page) = skin.title(p.title, up(p).title) { show(up(p)) }.view

    // ---- The look ----

    private fun savedSkin() = Skin.Kind.values().firstOrNull { it.name == prefs().getString("skin", null) } ?: Skin.Kind.PLATE

    private fun useSkin(kind: Skin.Kind) {
        if (kind == skin.kind) return
        prefs().edit().putString("skin", kind.name).apply()
        skin = Skin(this, kind)
        paint()
        show(page)
    }

    /** The window around the pages: paper with dark status icons, or black with light ones. */
    private fun paint() {
        frame.setBackgroundColor(skin.ground)
        window.statusBarColor = skin.ground
        window.navigationBarColor = skin.ground
        if (Build.VERSION.SDK_INT >= 30) {
            val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (skin.plate) light else 0, light)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                if (skin.plate) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
        }
    }

    // ---- Approach: what both home pages show ----

    private class Approach(
        val distance: Double?,
        val minutes: Int?,
        val armed: Boolean,
        val simulating: Boolean,
        val callouts: List<Gpws.Callout>,
        val next: Gpws.Callout?,
        val last: String?,
        val belowMins: Boolean,
    )

    private fun approach(): Approach {
        val d = Gpws.distance
        val calls = if (Gpws.isOn(Gpws.Feature.CALLOUTS)) Gpws.callouts() else emptyList()
        val next = d?.let { dist -> calls.filter { it.metres < dist }.maxByOrNull { it.metres } }
        val s = Gpws.lastSound
        val last = if (s != null && SystemClock.elapsedRealtime() - Gpws.lastSoundAt < 120_000) s.short else null
        val below = d != null && Gpws.isOn(Gpws.Feature.MINIMUMS) && d <= Gpws.minimumsM
        return Approach(d, Gpws.minutesLeft, Gpws.armed, Gpws.isSimulating, calls, next, last, below)
    }

    /** "420" and "M", or "12.4" and "KM" from 10 km out. */
    private fun readout(d: Double?): Pair<String, String> = when {
        d == null -> "----" to "M"
        d >= 10_000 -> String.format(Locale.US, "%.1f", d / 1000) to "KM"
        else -> d.toInt().toString() to "M"
    }

    private fun minimumsOn() = Gpws.isOn(Gpws.Feature.MINIMUMS)

    /** On both home pages: Maps' own voice and GPWS share the speaker. */
    private fun mapsVoiceCaution(col: LinearLayout) = skin.caution(
        col, "Caution",
        "Using the Google Maps voice? Then use either that or these callouts, not both: they can talk over each other.",
    )

    /** On both home pages, hidden until Maps can't be read: then it points at the update. */
    private fun unreadableWarning(col: LinearLayout): View = skin.caution(
        col, "Can't read Maps",
        "Google Maps is navigating, but GPWS can't read how far you are. A Maps update probably " +
            "changed its layout. Get the latest GPWS Auto from GitHub:",
    ).apply {
        addView(skin.button("Check for update", primary = true) { checkForUpdate() }, skin.lp(top = 10))
        visibility = View.GONE
    }

    private fun missedNote() = when {
        Gpws.isOn(Gpws.Feature.MISSED_RETARD) -> "Miss a turn and you hear RETARD, RETARD; the callouts re-arm from the new distance."
        Gpws.isOn(Gpws.Feature.GLIDESLOPE) -> "Miss a turn and you hear GLIDESLOPE; the callouts re-arm from the new distance."
        else -> "Miss a turn and the callouts re-arm from the new distance."
    }

    private fun retardOn() = Gpws.isOn(Gpws.Feature.CALLOUTS) && Gpws.isOn(Gpws.Feature.RETARD)

    /** A call name ("400", "MINIMUMS", "APPR MINS") that shrinks to fit its box on one line. */
    private fun callText(maxSp: Int, color: Int, face: Typeface, heightDp: Int) = skin.text("—", maxSp.toFloat(), color, face).apply {
        maxLines = 1
        gravity = Gravity.CENTER_VERTICAL
        setAutoSizeTextTypeUniformWithConfiguration(14, maxSp, 1, TypedValue.COMPLEX_UNIT_SP)
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(heightDp))
    }

    private fun TextView.size(sp: Float) {
        if (textSize != sp * resources.displayMetrics.scaledDensity) textSize = sp
    }

    // ---- Approach page, plate: the chart header, the radio altitude, the profile view ----

    private fun homePlate(): View {
        val s = skin
        val col = s.page()

        val head = s.box()
        val top = LinearLayout(this)
        top.addView(s.text("GPWS AUTO", 18f, s.ink, Fonts.condensedBold).apply { setPadding(dp(10), dp(8), dp(10), dp(8)) }, s.lp(width = 0, weight = 1f))
        top.addView(s.line(), s.lp(width = dp(1), height = MATCH))
        val phase = s.text("", 14f, s.ink, Fonts.condensed, 0.06f).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(6), 0)
        }
        top.addView(phase, s.lp(width = 0, height = MATCH, weight = 1f))
        val state = s.text("", 16f, s.ground, Fonts.condensedBold, 0.14f).apply {
            gravity = Gravity.CENTER
            minHeight = dp(44)
            setOnClickListener {
                Gpws.armed = !Gpws.armed
                live()
            }
        }
        top.addView(state, s.lp(width = 0, height = MATCH, weight = 1f))
        head.addView(top, s.lp(width = MATCH))
        head.addView(s.line(), s.lp(width = MATCH, height = dp(1)))
        val dest = s.text("", 19f, s.ink, Fonts.condensed)
        val eta = s.text("", 19f, s.ink, Fonts.condensed)
        val dh = s.text("", 19f, s.ink, Fonts.condensed)
        val voice = s.text("", 19f, s.ink, Fonts.condensed)
        val cells = LinearLayout(this)
        listOf("Dest" to dest, "ETA" to eta, "DH" to dh, "Voice" to voice).forEachIndexed { i, (label, value) ->
            if (i > 0) cells.addView(s.line(), s.lp(width = dp(1), height = MATCH))
            cells.addView(s.vertical().apply {
                setPadding(dp(10), dp(6), dp(6), dp(6))
                addView(s.label(label))
                addView(value)
            }, s.lp(width = 0, weight = 1f))
        }
        head.addView(cells, s.lp(width = MATCH))
        col.addView(head, s.lp(width = MATCH))
        val unreadable = unreadableWarning(col)

        val ra = LinearLayout(this).apply { background = s.outline(2) }
        val number = s.text("----", 104f, s.ink, Fonts.condensedBold).apply { includeFontPadding = false }
        val unit = s.text("M", 24f, s.ink, Fonts.condensed)
        ra.addView(s.vertical().apply {
            setPadding(dp(12), dp(10), dp(8), dp(8))
            addView(s.label("Radio altitude"))
            addView(LinearLayout(context).apply {
                gravity = Gravity.BOTTOM
                addView(number)
                addView(unit, s.lp(start = 6, bottom = 8))
            }, s.lp(top = 6))
        }, s.lp(width = 0, weight = 1.6f))
        ra.addView(s.line(), s.lp(width = dp(1), height = MATCH))
        val next = callText(34, s.accent, Fonts.condensedBold, 44)
        val last = callText(34, s.ink, Fonts.condensedBold, 44)
        ra.addView(s.vertical().apply {
            addView(s.vertical().apply {
                setPadding(dp(12), dp(8), dp(8), dp(2))
                addView(s.label("Next call"))
                addView(next)
            }, s.lp(width = MATCH, height = 0, weight = 1f))
            addView(s.line(), s.lp(width = MATCH, height = dp(1)))
            addView(s.vertical().apply {
                setPadding(dp(12), dp(8), dp(8), dp(2))
                addView(s.label("Last"))
                addView(last)
            }, s.lp(width = MATCH, height = 0, weight = 1f))
        }, s.lp(width = 0, height = MATCH, weight = 1f))
        col.addView(ra, s.lp(width = MATCH, top = 10))

        fun strip(left: String, right: String?) = LinearLayout(this).apply {
            setPadding(dp(10), dp(5), dp(10), dp(5))
            addView(s.text(left.uppercase(), 12f, s.ink, Fonts.condensed, 0.12f), s.lp(width = 0, weight = 1f))
            if (right != null) addView(s.text(right.uppercase(), 12f, s.muted, Fonts.condensed, 0.12f))
        }
        val profileBox = s.box()
        profileBox.addView(strip("Profile", "Metres to destination"), s.lp(width = MATCH))
        profileBox.addView(s.line(), s.lp(width = MATCH, height = dp(1)))
        val profile = ProfileView(this, s)
        profileBox.addView(profile, s.lp(width = MATCH))
        col.addView(profileBox, s.lp(width = MATCH, top = 10))

        val seqBox = s.box()
        seqBox.addView(strip("Callout sequence", null), s.lp(width = MATCH))
        seqBox.addView(s.line(), s.lp(width = MATCH, height = dp(1)))
        val seq = FlowLayout(this, dp(10)).apply { setPadding(dp(12), dp(9), dp(12), dp(10)) }
        seqBox.addView(seq, s.lp(width = MATCH))
        col.addView(seqBox, s.lp(width = MATCH, top = 10))

        col.addView(LinearLayout(this).apply {
            background = s.outline(2)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addView(s.text("MISSED", 12f, s.ink, Fonts.condensedBold, 0.12f), s.lp(end = 10, top = 2))
            addView(
                s.text(missedNote(), 13f, s.ink).apply { setLineSpacing(0f, 1.25f) },
                s.lp(width = 0, weight = 1f),
            )
        }, s.lp(width = MATCH, top = 10))
        mapsVoiceCaution(col)

        var seqKey = ""
        live = {
            val a = approach()
            phase.text = when {
                a.simulating -> "SIM"
                a.distance != null -> "APPROACH"
                Gpws.mapsUnreadable -> "NO DATA"
                else -> "STANDBY"
            }
            unreadable.visibility = if (Gpws.mapsUnreadable && a.distance == null) View.VISIBLE else View.GONE
            state.text = if (a.armed) "ARMED" else "INHIBIT"
            state.setTextColor(if (a.armed) s.ground else s.warn)
            state.background = if (a.armed) s.fill(s.ink, 0) else s.outline(2, s.warn)
            state.contentDescription = if (a.armed) "GPWS armed. Tap to silence everything" else "GPWS silenced. Tap to arm"
            dest.text = a.distance?.let { String.format(Locale.US, "%.2f KM", it / 1000) } ?: "—"
            eta.text = a.minutes?.let { "$it MIN" } ?: "—"
            dh.text = if (minimumsOn()) "${Gpws.minimumsM} M" else "OFF"
            dh.setTextColor(if (minimumsOn()) s.accent else s.muted)
            val p = Gpws.pack
            voice.text = if (p.model.first().isDigit()) "${p.maker.first()}${p.model}" else p.model
            val (n, u) = readout(a.distance)
            number.text = n
            number.size(if (n.length > 3) 88f else 104f)
            number.setTextColor(if (a.distance == null) s.faint else if (a.belowMins) s.warn else s.ink)
            unit.text = u
            next.text = a.next?.sound?.short ?: "—"
            last.text = a.last ?: "—"
            profile.fraction = a.distance?.let { ProfileView.position(it) }
            profile.dh = if (minimumsOn()) ProfileView.position(Gpws.minimumsM.toDouble()) else null
            profile.youLabel = "YOU · $n"
            profile.retard = retardOn()
            // Passed calls struck through, the next one boxed, the rest waiting.
            fun state(c: Gpws.Callout) = when {
                a.distance != null && c.metres > a.distance -> 0
                c === a.next -> 1
                else -> 2
            }
            val key = a.callouts.joinToString(",") { "${it.metres}:${state(it)}" } + retardOn()
            if (key != seqKey) {
                seqKey = key
                seq.removeAllViews()
                a.callouts.forEach { c ->
                    seq.addView(s.text(c.sound.short, 17f, s.ink, Fonts.condensed).apply {
                        when (state(c)) {
                            0 -> {
                                setTextColor(s.faint)
                                paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                            }
                            1 -> {
                                setTextColor(s.accent)
                                background = s.outline(2, s.accent)
                                setPadding(dp(5), 0, dp(5), 0)
                            }
                        }
                    })
                }
                if (retardOn()) seq.addView(s.text("RETARD", 17f, s.ink, Fonts.condensedBold))
            }
        }
        refresh = { live() }
        return col
    }

    // ---- Approach page, signs: one big yellow sign ----

    private fun homeSigns(): View {
        val s = skin
        val col = s.page()

        val state = s.text("", 13f, BLACK, Fonts.signsBold, 0.1f).apply { setPadding(dp(10), dp(6), dp(10), dp(4)) }
        col.addView(s.horizontal().apply {
            background = s.fill(s.panel, 8)
            minimumHeight = dp(48)
            setPadding(dp(12), dp(8), dp(10), dp(8))
            addView(s.icon(R.drawable.ic_plane, s.accent, 24))
            addView(s.text("GPWS", 18f, s.ink, Fonts.signsBold, 0.04f), s.lp(width = 0, weight = 1f, start = 10))
            addView(state)
            setOnClickListener {
                Gpws.armed = !Gpws.armed
                live()
            }
        }, s.lp(width = MATCH))

        val unreadable = unreadableWarning(col)
        val hero = s.vertical().apply { setPadding(dp(18), dp(18), dp(18), dp(14)) }
        val number = s.text("----", 112f, BLACK, Fonts.signsBlack, -0.04f).apply { includeFontPadding = false }
        val unit = s.text("m", 30f, BLACK, Fonts.signsBold)
        val arrow = s.icon(R.drawable.ic_arrow_right, BLACK, 76)
        hero.addView(s.horizontal().apply {
            addView(LinearLayout(context).apply {
                gravity = Gravity.BOTTOM
                addView(number)
                addView(unit, s.lp(start = 6, bottom = 12))
            }, s.lp(width = 0, weight = 1f))
            addView(arrow)
        }, s.lp(width = MATCH))
        val heroRule = s.line(BLACK)
        hero.addView(heroRule, s.lp(width = MATCH, height = dp(4), top = 12))
        val where = s.text("", 20f, BLACK, Fonts.signsBold)
        val mins = s.text("", 20f, BLACK, Fonts.signsBold)
        hero.addView(s.horizontal().apply {
            addView(where, s.lp(width = 0, weight = 1f))
            addView(mins)
        }, s.lp(width = MATCH, top = 9))
        col.addView(hero, s.lp(width = MATCH, top = 10))

        fun panel(label: String, value: TextView) = s.vertical().apply {
            background = s.fill(s.panel, 8)
            setPadding(dp(12), dp(9), dp(12), dp(8))
            addView(s.label(label))
            addView(value)
        }
        fun pair(a: View, b: View) = LinearLayout(this).apply {
            addView(a, s.lp(width = 0, weight = 1f))
            addView(b, s.lp(width = 0, weight = 1f, start = 10))
        }
        val next = callText(40, s.ink, Fonts.signsBlack, 52)
        val last = callText(40, 0xFFC9C9C9.toInt(), Fonts.signsBlack, 52)
        col.addView(pair(panel("Next call", next), panel("Last call", last)), s.lp(width = MATCH, top = 10))
        val voice = s.text("", 18f, s.ink, Fonts.signsBold)
        val dh = s.text("", 18f, s.ink, Fonts.signsBold)
        val speed = s.text("", 18f, s.ink, Fonts.signsBold)
        val car = s.text("", 18f, s.ink, Fonts.signsBold)
        col.addView(pair(panel("Voice", voice), panel("Decision ht", dh)), s.lp(width = MATCH, top = 10))
        col.addView(pair(panel("Speed", speed), panel("Car", car)), s.lp(width = MATCH, top = 10))

        val tiles = LinearLayout(this)
        fun tile(label: String, isOn: () -> Boolean, flip: () -> Unit): () -> Unit {
            val name = s.text(label, 12f, BLACK, Fonts.signsBold, 0.12f)
            val value = s.text("", 26f, BLACK, Fonts.signsBlack)
            val t = s.vertical().apply {
                gravity = Gravity.CENTER
                minimumHeight = dp(78)
                addView(name, s.lp())   // wrap-width, so the tile's centring applies
                addView(value, s.lp())
                setOnClickListener {
                    flip()
                    live()
                }
            }
            tiles.addView(t, s.lp(width = 0, weight = 1f, start = if (tiles.childCount > 0) 10 else 0))
            return {
                val on = isOn()
                t.background = s.fill(if (on) s.accent else 0xFF3A3A3A.toInt(), 8)
                name.setTextColor(if (on) BLACK else 0xFFC9C9C9.toInt())
                value.setTextColor(if (on) BLACK else 0xFFC9C9C9.toInt())
                value.text = if (on) "ON" else "OFF"
                t.contentDescription = "$label ${if (on) "on" else "off"}"
            }
        }
        val gpwsTile = tile("GPWS", { Gpws.armed }) { Gpws.armed = !Gpws.armed }
        val terrTile = tile("TERRAIN", { Gpws.isOn(Gpws.Feature.TERRAIN) }) { Gpws.setOn(Gpws.Feature.TERRAIN, !Gpws.isOn(Gpws.Feature.TERRAIN)) }
        val trafficTile = tile("TRAFFIC", { Gpws.isOn(Gpws.Feature.TRAFFIC) }) { Gpws.setOn(Gpws.Feature.TRAFFIC, !Gpws.isOn(Gpws.Feature.TRAFFIC)) }
        col.addView(tiles, s.lp(width = MATCH, top = 10))
        mapsVoiceCaution(col)

        live = {
            val a = approach()
            state.text = if (a.armed) "ARMED" else "INHIBIT"
            state.background = s.fill(if (a.armed) s.accent else 0xFF3A3A3A.toInt(), 4)
            state.setTextColor(if (a.armed) BLACK else s.ink)
            val nav = a.distance != null
            val heroInk = if (nav) BLACK else s.ink
            hero.background = s.fill(if (nav) s.accent else s.panel, 10)
            val (n, u) = readout(a.distance)
            number.text = n
            number.size(if (n.length > 3) 84f else 112f)
            unit.text = u.lowercase()
            listOf(number, unit, where, mins).forEach { it.setTextColor(heroInk) }
            heroRule.setBackgroundColor(heroInk)
            arrow.imageTintList = ColorStateList.valueOf(if (nav) BLACK else s.muted)
            where.text = when {
                nav -> "Destination"
                Gpws.mapsUnreadable -> "Can't read Maps"
                else -> "Start navigating in Maps"
            }
            unreadable.visibility = if (Gpws.mapsUnreadable && !nav) View.VISIBLE else View.GONE
            mins.text = a.minutes?.let { "$it min" } ?: ""
            next.text = a.next?.sound?.short ?: "—"
            last.text = a.last ?: "—"
            val p = Gpws.pack
            voice.text = "${if (p.short == "MDC") "MDC" else p.maker} ${p.model}"
            dh.text = if (minimumsOn()) "${Gpws.minimumsM} m" else "Off"
            speed.text = Gpws.speedKmh?.let { "${it.toInt()} km/h" } ?: "—"
            car.text = when (Gpws.carVia) {
                "ANDROID AUTO" -> "Android Auto"
                "BLUETOOTH" -> "Bluetooth"
                else -> if (Gpws.isOn(Gpws.Feature.AUTO_ARM)) "Auto arm" else "—"
            }
            gpwsTile()
            terrTile()
            trafficTile()
        }
        refresh = { live() }
        return col
    }

    // ---- Voice: pick the aircraft, hear its clips ----

    private fun voice(): View {
        val s = skin
        val col = s.page()
        col.addView(s.title("Voice").view, s.lp(width = MATCH))
        val selected = Gpws.pack
        val packs = s.section(col, "Voice pack")
        Packs.ALL.forEach { p ->
            val tag = s.tag()
            s.setTag(tag, p.id == selected.id, p.model, p.model)
            s.row(packs, p.maker, p.tagline, tag) {
                Gpws.setPack(p.id)
                show(Page.VOICE)
            }.contentDescription = "${p.maker} ${p.model}${if (p.id == selected.id) ", selected" else ""}"
        }
        val surprise = s.tag()
        s.setTag(surprise, Gpws.surprise, "ON", "OFF")
        s.row(
            packs, "Surprise me",
            if (Gpws.surprise) "A different plane every trip · now: ${selected.model}" else "A different plane every trip",
            surprise,
        ) {
            Gpws.surprise = !Gpws.surprise
            show(Page.VOICE)
        }

        val card = s.section(col, "${selected.maker} ${selected.model}")
        val info = s.block(card)
        info.addView(s.note(selected.description))
        val mins = listOfNotNull(selected.minimums.label, selected.approach?.label).joinToString(" · ")
        info.addView(s.label("Minimums call: $mins"), s.lp(top = 10))
        val chips = FlowLayout(this, dp(8))
        selected.clips.forEach { c -> chips.addView(s.chip(c.label, Custom.has(this, selected.file(c))) { Gpws.preview(selected, c) }) }
        if (selected.retard == null) {
            val airbus = Packs.byId("a320")
            chips.addView(s.chip("RETARD (A320)", Custom.has(this, airbus.retardFile)) { Gpws.preview(airbus, airbus.retard!!) })
        }
        info.addView(chips, s.lp(width = MATCH, top = 10))

        val alerts = s.section(col, "Alerts · every voice")
        val alertBlock = s.block(alerts)
        val alertChips = FlowLayout(this, dp(8))
        Gpws.alertSounds().forEach { snd -> alertChips.addView(s.chip(snd.short, Custom.has(this, snd.file)) { Gpws.testOne(snd) }) }
        alertBlock.addView(alertChips, s.lp(width = MATCH))
        val n = Custom.count(this)
        if (n > 0) alertBlock.addView(s.label(if (s.plate) "Blue: your own sound" else "Yellow: your own sound"), s.lp(top = 10))

        val own = s.section(col, "Custom sounds")
        s.row(own, "Your own sounds", "Any audio or video, for any callout", s.value(s.name(if (n > 0) "$n set" else "Add"))) { open(Page.SOUNDS) }
        return col
    }

    // ---- Alerts: every switch, the callout picker and the limits ----

    private class Switch(val feature: Gpws.Feature, val name: String, val detail: String)

    private fun alerts(): View {
        val s = skin
        val col = s.page()
        val title = s.title("Alerts")
        col.addView(title.view, s.lp(width = MATCH))
        val groups = listOf(
            "Callouts" to listOf(
                Switch(Gpws.Feature.CALLOUTS, "Altitude callouts", "2500 → 10 as you arrive"),
                Switch(Gpws.Feature.RETARD, "Retard", "RETARD, RETARD as you stop"),
                Switch(Gpws.Feature.MINIMUMS, "Minimums", "${Gpws.pack.minimumsName}, set below"),
                Switch(Gpws.Feature.V1, "V1", "As you pull away"),
                Switch(Gpws.Feature.RUNWAY, "Runway", "Approaching and on highways"),
            ),
            "Warnings" to listOf(
                Switch(Gpws.Feature.GLIDESLOPE, "Glideslope", "You missed a turn"),
                Switch(Gpws.Feature.MISSED_RETARD, "Missed-turn RETARD", "RETARD instead of GLIDESLOPE, for fun"),
                Switch(Gpws.Feature.TRAFFIC, "Traffic", "Jams, then clear of conflict"),
                Switch(Gpws.Feature.OVERSPEED, "Overspeed", "One clacker above the limit"),
                Switch(Gpws.Feature.TERRAIN, "Terrain", "Steep ghats: terrain, don't sink"),
                Switch(Gpws.Feature.BANK_ANGLE, "Bank angle", "Corners over 0.4 g"),
                Switch(Gpws.Feature.SINK_RATE, "Sink rate · Pull up", "After hard braking"),
                Switch(Gpws.Feature.TOO_LOW, "Too low terrain", "Speed breakers hit fast"),
                Switch(Gpws.Feature.AP_DISC, "Autopilot disconnect", "Navigation cancelled early"),
            ),
            "System" to listOf(
                Switch(Gpws.Feature.DUCK, "Music duck", "Lower Spotify under callouts"),
                Switch(Gpws.Feature.SMART_VOL, "Smart volume", "Louder at speed"),
                Switch(Gpws.Feature.AUTO_ARM, "Auto arm", "Android Auto or car Bluetooth"),
                Switch(Gpws.Feature.CALL_MUTE, "Call mute", "Quiet while you're on a call"),
            ),
        )
        val redraws = mutableListOf<() -> Unit>()
        groups.forEach { (name, switches) ->
            val sec = s.section(col, name)
            switches.forEach { sw ->
                redraws += s.switchRow(sec, sw.name, sw.detail, { Gpws.isOn(sw.feature) }) {
                    Gpws.setOn(sw.feature, !Gpws.isOn(sw.feature))
                    refresh()
                }
            }
        }
        val all = groups.flatMap { it.second }

        val picker = s.section(col, "Callout picker")
        val pickBlock = s.block(picker)
        pickBlock.addView(s.note("Tap a number to skip it."))
        val numbers = FlowLayout(this, dp(8))
        val picks = Packs.NUMBERS.map { (m, _) ->
            m to s.pick("$m").apply {
                setOnClickListener {
                    Gpws.setNumber(m, !Gpws.numberOn(m))
                    refresh()
                }
                numbers.addView(this)
            }
        }
        pickBlock.addView(numbers, s.lp(width = MATCH, top = 10))

        val limits = s.section(col, "Limits")
        val overspeed = s.value()
        val minimums = s.value()
        s.stepperRow(limits, "Overspeed limit", overspeed, { changeOverspeed(-10) }, { changeOverspeed(10) })
        s.stepperRow(limits, Gpws.pack.minimumsName, minimums, { changeMinimums(-10) }, { changeMinimums(10) })

        val traffic = s.section(col, "Live traffic")
        val key = s.value()
        s.row(traffic, "TomTom key", "Traffic 100–200 m ahead", s.horizontal().apply {
            addView(key, s.lp(end = 10))
            addView(s.button("Edit") { editTomTomKey() })
        }, null)
        s.block(traffic).addView(
            s.note(
                "Every ~20 s while you drive, GPWS asks TomTom about the road ahead. Your position goes to " +
                    "TomTom only while a key is set; get a free one at developer.tomtom.com. Without one, " +
                    "TRAFFIC still warns after 90 s stuck in a jam.",
            ),
        )

        refresh = {
            redraws.forEach { it() }
            title.setRight("${all.count { Gpws.isOn(it.feature) }} OF ${all.size} ON")
            picks.forEach { (m, v) -> s.setPick(v, Gpws.numberOn(m)) }
            overspeed.text = s.name("${Gpws.overspeedKmh} km/h")
            minimums.text = s.name("${Gpws.minimumsM} m")
            minimums.setTextColor(if (minimumsOn()) (if (s.plate) s.ink else s.accent) else s.muted)
            val set = Gpws.tomtomKey.isNotBlank()
            key.text = if (set) s.name("Set") else "—"
            key.setTextColor(if (set) ok else s.muted)
        }
        return col
    }

    private fun changeOverspeed(delta: Int) {
        Gpws.overspeedKmh = (Gpws.overspeedKmh + delta).coerceIn(40, 200)
        refresh()
    }

    private fun changeMinimums(delta: Int) {
        Gpws.minimumsM = (Gpws.minimumsM + delta).coerceIn(30, 500)
        refresh()
    }

    private fun editTomTomKey() {
        val s = skin
        val input = EditText(this).apply {
            setText(Gpws.tomtomKey)
            hint = "Paste your TomTom API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            typeface = Typeface.MONOSPACE
            setTextColor(s.ink)
            setHintTextColor(s.muted)
            backgroundTintList = ColorStateList.valueOf(s.accent)
        }
        val body = s.dialogBody().apply {
            addView(s.note("Free from developer.tomtom.com. Leave it empty to turn traffic-ahead off."))
            addView(input, s.lp(top = 8, width = MATCH))
        }
        s.show(
            s.dialog("TomTom traffic key")
                .setView(body)
                .setPositiveButton("Save") { _, _ ->
                    Gpws.tomtomKey = input.text.toString()
                    refresh()
                }
                .setNeutralButton("Test") { _, _ ->
                    val (lat, lon) = testPosition()
                    Traffic.test(input.text.toString(), lat, lon) { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
                }
                .setNegativeButton("Cancel", null),
        )
    }

    /** Somewhere real to test the key against: the last GPS fix, else the phone's last known location. */
    @SuppressLint("MissingPermission")
    private fun testPosition(): Pair<Double, Double> {
        val p = Gpws.position
        if (p.first != 0.0) return p
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
                lm.getLastKnownLocation(provider)?.let { return it.latitude to it.longitude }
            }
        }
        return 52.3702 to 4.8952  // Amsterdam, near TomTom HQ: any road works for a key check
    }

    // ---- Status: the look, permissions, the car, tools ----

    private fun status(): View {
        val s = skin
        val col = s.page()
        col.addView(s.title("Status").view, s.lp(width = MATCH))

        val look = s.section(col, "Look")
        Skin.Kind.values().forEach { k ->
            val tag = s.tag()
            s.setTag(tag, k == s.kind, "IN USE", "USE")
            s.row(look, k.title, k.blurb, tag) { useSkin(k) }
        }

        val perms = s.section(col, "Permissions")
        val notif = s.value()
        val location = s.value()
        val battery = s.value()
        s.row(perms, "Notification access", "Reads how far Maps says you are", notif) { openNotificationAccess() }
        s.row(perms, "Location", "Speed between Maps updates", location) { askLocation() }
        s.row(perms, "Battery", "Not put to sleep mid-drive", battery) { exemptBattery() }
        val tile = if (Build.VERSION.SDK_INT < 33) null else s.value().also { s.row(perms, "Quick Settings tile", "Silence GPWS in one tap", it) { addTile() } }

        val carSec = s.section(col, "Car")
        val androidAuto = s.value()
        val bluetooth = s.value()
        s.row(carSec, "Android Auto", "Arms GPWS when it connects", androidAuto, null)
        s.row(carSec, "Car Bluetooth", "For cars without Android Auto", bluetooth) { pickCar() }

        val tools = s.section(col, "Tools")
        s.row(tools, "Setup checklist", null, s.value(s.name("Open"))) { open(Page.SETUP) }
        s.row(tools, "Simulated approach", "A fake 650 m arrival, sounds and all", s.value(s.name("Run"))) {
            Gpws.simulate()
            show(Page.HOME)  // watch it count down
        }
        s.row(tools, "Event log", null, s.value(s.name("Open"))) { open(Page.LOG) }
        s.row(tools, "Classic PFD", "The old Boeing-style display", s.value(s.name("Open"))) { open(Page.PFD) }
        s.row(tools, "Updates", "Version ${Updater.installed(this)} · from GitHub", s.value(s.name("Check"))) { checkForUpdate() }

        col.addView(
            s.note(
                "GPWS reads Google Maps' navigation notification to know how far away you are. Nothing " +
                    "leaves your phone, except TomTom traffic checks if you add a key, and update checks " +
                    "when you tap Check.",
            ),
            s.lp(top = 14),
        )

        refresh = {
            fun state(v: TextView, good: Boolean, okText: String) {
                v.text = s.name(if (good) okText else "Fix")
                v.setTextColor(if (good) ok else s.warn)
            }
            state(notif, notificationAccess(), "OK")
            state(location, backgroundLocation(), "Always")
            state(battery, batteryExempt(), "Unrestricted")
            tile?.let {
                val added = prefs().getBoolean("tile_added", false)
                it.text = s.name(if (added) "Added" else "Add")
                it.setTextColor(if (added) ok else s.accent)
            }
            val linked = Gpws.carVia == "ANDROID AUTO"
            androidAuto.text = if (linked) s.name("Linked") else "—"
            androidAuto.setTextColor(if (linked) ok else s.muted)
            val mine = Gpws.carBluetooth
            bluetooth.text = s.name(mine?.take(14) ?: "Set")
            bluetooth.setTextColor(if (mine != null) ok else s.accent)
        }
        return col
    }

    // ---- Updates: from GitHub, only when asked ----

    private fun checkForUpdate() {
        if (!Updater.ready) {
            message("Updates", "Updates aren't set up yet: GPWS Auto has no GitHub page so far.")
            return
        }
        Toast.makeText(this, "Checking GitHub…", Toast.LENGTH_SHORT).show()
        Updater.check { r, error ->
            if (isFinishing || isDestroyed) return@check
            val mine = Updater.installed(this)
            when {
                r == null -> message("Couldn't check", error ?: "Something went wrong.")
                !Updater.newer(r.version, mine) -> message("Up to date", "You have the latest version ($mine).")
                else -> offerUpdate(r, mine)
            }
        }
    }

    private fun message(title: String, text: String) {
        val s = skin
        s.show(s.dialog(title).setView(s.dialogBody().apply { addView(s.note(text)) }).setPositiveButton("OK", null))
    }

    private fun offerUpdate(r: Updater.Release, mine: String) {
        val s = skin
        val notes = r.notes.trim().take(600)
        val body = s.dialogBody().apply {
            addView(s.note("You have $mine."))
            if (notes.isNotEmpty()) addView(s.text(notes, 14f, s.ink).apply { setLineSpacing(0f, 1.25f) }, s.lp(top = 8))
        }
        val d = s.dialog("Version ${r.version.removePrefix("v")} is out").setView(body)
        if (r.apkUrl != null) {
            d.setPositiveButton("Install") { _, _ -> downloadUpdate(r) }
        } else {
            d.setPositiveButton("Open GitHub") { _, _ -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.page))) }
        }
        s.show(d.setNegativeButton("Later", null))
    }

    private fun downloadUpdate(r: Updater.Release) {
        val s = skin
        if (!packageManager.canRequestPackageInstalls()) {
            // Android asks once per app; we carry on by ourselves when the user comes back.
            s.show(
                s.dialog("Allow updates")
                    .setView(s.dialogBody().apply {
                        addView(s.note("Android needs your OK once before GPWS Auto can install its own updates. Turn on \"Allow from this source\", then come back."))
                    })
                    .setPositiveButton("Open settings") { _, _ ->
                        pendingUpdate = r
                        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                    }
                    .setNegativeButton("Cancel", null),
            )
            return
        }
        val status = s.note("Starting…")
        val dlg = s.show(s.dialog("Downloading").setView(s.dialogBody().apply { addView(status) }).setCancelable(false))
        Updater.download(this, r, { pct -> status.text = "$pct%" }) { file, error ->
            dlg.dismiss()
            if (isFinishing || isDestroyed) return@download
            if (file == null) {
                message("Download failed", error ?: "Something went wrong.")
            } else {
                Updater.install(this, file) { why -> message("Couldn't install", why) }
            }
        }
    }

    private fun prefs() = getSharedPreferences("gpws", MODE_PRIVATE)

    // Android 11+ can open GPWS's own switch, rather than a list of every app.
    private fun openNotificationAccess() {
        val list = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        val direct = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
            ComponentName(this, NavListener::class.java).flattenToString(),
        )
        try {
            startActivity(if (Build.VERSION.SDK_INT >= 30) direct else list)
        } catch (e: Exception) {
            startActivity(list)
        }
    }

    // ---- Setup: the before-start checklist, shown on first launch ----

    private class Item(
        val label: String,
        val why: String,
        val action: String,
        val optional: Boolean,
        val done: () -> Boolean,
        val act: () -> Unit,
    )

    private fun setup(): View {
        val s = skin
        val col = s.page()
        col.addView(subTitle(Page.SETUP), s.lp(width = MATCH))
        val list = s.section(col, "Before start checklist")
        val items = listOfNotNull(
            Item(
                "Notification access", "So GPWS can read how far Google Maps says you are. Switch greyed out? " +
                    "Open App info below, tap ⋮ → Allow restricted settings, then try again.",
                "Grant", false, { notificationAccess() },
            ) { openNotificationAccess() },
            Item(
                "Location", "Speed and heading between Maps updates: that's what lands 50, 40, 30 on time.",
                "Grant", false, { granted(Manifest.permission.ACCESS_FINE_LOCATION) },
            ) { askLocation() },
            Item(
                "Location all the time", "Keeps GPWS working with the screen off. Choose \"Allow all the time\".",
                "Grant", false, { backgroundLocation() },
            ) { askLocation() },
            Item(
                "Battery", "Stops Android putting GPWS to sleep halfway through a drive.",
                "Allow", false, { batteryExempt() },
            ) { exemptBattery() },
            Item(
                "Sound check", "Optional: plays \"one thousand\". Can't hear it? Turn the volume up.",
                "Play", true, { prefs().getBoolean("sound_checked", false) },
            ) {
                val p = Gpws.pack
                Gpws.preview(p, p.numbers.first { it.name == "1000" })
                prefs().edit().putBoolean("sound_checked", true).apply()
                refresh()
            },
            Item(
                "Car Bluetooth", "Optional, for cars without Android Auto: tap while the phone is connected to " +
                    "your car's Bluetooth, and GPWS arms itself whenever you get in.",
                "Set", true, { Gpws.carBluetooth != null },
            ) { pickCar() },
            if (Build.VERSION.SDK_INT < 33) null else Item(
                "Quick Settings tile", "Optional: a Quick Settings switch that silences GPWS in one tap.",
                "Add", true, { prefs().getBoolean("tile_added", false) },
            ) { addTile() },
        )
        val states = items.map { item ->
            val v = s.value()
            s.row(list, item.label, item.why, v) { item.act() }
            item to v
        }
        s.row(list, "App info", "Android's page for this app", s.value(s.name("Open"))) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }

        val summary = s.text("", 15f, s.warn, s.sub)
        col.addView(summary, s.lp(top = 14))
        fun missing() = items.filter { !it.optional && !it.done() }.map { it.label }
        val start = s.button("Start", primary = true) {
            val todo = missing()
            if (todo.isNotEmpty()) {
                Toast.makeText(this, "Still to do: ${todo.joinToString(", ")}. Or tap Later.", Toast.LENGTH_LONG).show()
            } else {
                prefs().edit().putBoolean("setup_done", true).apply()
                show(Page.HOME)
            }
        }
        val later = s.button("Later") {
            prefs().edit().putBoolean("setup_done", true).apply()
            show(Page.HOME)
        }
        col.addView(LinearLayout(this).apply {
            addView(start, s.lp(width = 0, weight = 1f))
            addView(later, s.lp(width = 0, weight = 1f, start = 10))
        }, s.lp(width = MATCH, top = 12))

        refresh = {
            states.forEach { (item, v) ->
                val done = item.done()
                v.text = s.name(if (done) "Checked" else item.action)
                v.setTextColor(if (done) ok else if (item.optional) s.muted else s.accent)
            }
            val todo = missing()
            summary.text = s.name(if (todo.isEmpty()) "Checklist complete" else "To go: ${todo.joinToString(", ")}")
            summary.setTextColor(if (todo.isEmpty()) ok else s.warn)
            start.alpha = if (todo.isEmpty()) 1f else 0.45f
        }
        return col
    }

    /** Remember which Bluetooth device is the car: pick from what's connected right now. */
    private fun pickCar() {
        val s = skin
        var dialog: AlertDialog? = null
        val body = s.dialogBody()
        fun choose(label: String, color: Int, act: () -> Unit) = s.option(body, label, color) {
            dialog?.dismiss()
            act()
            Gpws.onCarBluetooth(Car.bluetoothCar(this))
            refresh()
        }
        val names = Car.bluetoothNames(this)
        val mine = Gpws.carBluetooth
        if (names.isEmpty()) {
            body.addView(s.note("Connect the phone to your car's Bluetooth first, then come back here."), s.lp(top = 8, bottom = 8))
        } else {
            body.addView(s.note("Which of these is your car?"), s.lp(bottom = 4))
            names.forEach { name -> choose(if (name == mine) "$name  ✓" else name, s.accent) { Gpws.carBluetooth = name } }
        }
        if (mine != null) choose("Forget $mine", s.warn) { Gpws.carBluetooth = null }
        dialog = s.show(s.dialog("My car · Bluetooth").setView(body).setNegativeButton("Cancel", null))
    }

    private fun notificationAccess() =
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true

    private fun backgroundLocation() = granted(Manifest.permission.ACCESS_FINE_LOCATION) &&
        (Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION))

    private fun batteryExempt() = (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun askLocation() {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
        } else if (Build.VERSION.SDK_INT >= 29) {
            // Android shows a settings page for this one: choose "Allow all the time".
            requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 2)
        }
    }

    @SuppressLint("BatteryLife")
    private fun exemptBattery() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun addTile() {
        if (Build.VERSION.SDK_INT < 33) return
        val bar = getSystemService(StatusBarManager::class.java) ?: return
        bar.requestAddTileService(
            ComponentName(this, KillTile::class.java),
            "GPWS",
            Icon.createWithResource(this, R.drawable.ic_tile),
            mainExecutor,
        ) { result ->
            if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
            ) {
                prefs().edit().putBoolean("tile_added", true).apply()
                refresh()
            }
            Events.add(
                when (result) {
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "Quick Settings tile added"
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "Quick Settings tile already there"
                    else -> "Quick Settings tile not added"
                }
            )
        }
    }

    // ---- Custom sounds: your own clip for any callout ----

    private var importFile: String? = null   // the sound being replaced, while the file picker is up
    private var importLabel: String? = null

    private fun sounds(): View {
        val s = skin
        val col = s.page()
        col.addView(subTitle(Page.SOUNDS), s.lp(width = MATCH))
        val pack = s.block(s.section(col, "Sound pack"))
        val count = s.value()
        pack.addView(s.horizontal().apply {
            addView(count, s.lp(width = 0, weight = 1f))
            addView(s.button("Share") { sharePack() })
            addView(s.button("Import") { pickPack() }, s.lp(start = 8))
        }, s.lp(width = MATCH))
        pack.addView(s.note("Send all your custom sounds to a friend as one file, or load theirs."), s.lp(top = 10))

        val list = s.section(col, "Sounds")
        val rows = Gpws.allSounds().map { snd ->
            val v = s.value()
            s.row(list, snd.label, null, v) { soundMenu(snd) }
            snd to v
        }
        col.addView(
            s.note(
                "Tap a sound to use your own: any audio or video file, including a screen recording. Then drag " +
                    "the markers to just the callout. Custom sounds stay on this phone. Numbers belong to the " +
                    "${Gpws.pack.short} ${Gpws.pack.model} voice; alerts are shared by every voice.",
            ),
            s.lp(top = 14),
        )
        refresh = {
            count.text = s.name("${Custom.count(this)} custom")
            rows.forEach { (snd, v) ->
                val mine = Custom.has(this, snd.file)
                v.text = s.name(if (mine) "Custom" else "Built-in")
                v.setTextColor(if (mine) s.accent else s.muted)
            }
        }
        return col
    }

    private fun soundMenu(snd: Gpws.Sound) {
        if (!Custom.has(this, snd.file)) return pick(snd)
        val s = skin
        var dialog: AlertDialog? = null
        val body = s.dialogBody()
        s.option(body, "Play", s.accent) {
            dialog?.dismiss()
            Gpws.testOne(snd)
        }
        s.option(body, "Replace…", s.accent) {
            dialog?.dismiss()
            pick(snd)
        }
        s.option(body, "Back to built-in", s.warn) {  // warning colour: it deletes your sound
            dialog?.dismiss()
            Custom.reset(this, snd.file)
            Events.add("custom sound removed: ${snd.label}")
            refresh()
        }
        dialog = s.show(s.dialog(snd.label).setView(body))
    }

    private fun sharePack() {
        val zip = Custom.export(this, Gpws.soundLabels())
        if (zip == null) {
            Toast.makeText(this, "No custom sounds yet: import one first", Toast.LENGTH_LONG).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, ShareProvider.URI)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(ShareProvider.NAME, ShareProvider.URI)  // carries the read grant through the chooser
        startActivity(Intent.createChooser(send, "Share GPWS sounds"))
    }

    private fun pickPack() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, IMPORT_PACK)
        } catch (e: Exception) {
            Toast.makeText(this, "No file picker on this phone", Toast.LENGTH_LONG).show()
        }
    }

    private fun pick(snd: Gpws.Sound) {
        importFile = snd.file
        importLabel = snd.label
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "video/*"))
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, IMPORT)
        } catch (e: Exception) {
            Toast.makeText(this, "No file picker on this phone", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == IMPORT_PACK) {
            if (resultCode != RESULT_OK || uri == null) return
            Thread {
                val result = runCatching { Custom.import(this, uri) }
                main.post {
                    result.onSuccess {
                        Events.add("sound pack: $it sounds imported")
                        Toast.makeText(this, "Imported $it sounds", Toast.LENGTH_LONG).show()
                        refresh()
                    }.onFailure { Toast.makeText(this, "Couldn't import: ${it.message}", Toast.LENGTH_LONG).show() }
                }
            }.start()
            return
        }
        val file = importFile
        val label = importLabel
        if (requestCode != IMPORT || resultCode != RESULT_OK || uri == null || file == null || label == null) return
        Toast.makeText(this, "Reading the sound…", Toast.LENGTH_SHORT).show()
        Thread {
            val result = runCatching { Custom.decode(this, uri) }
            main.post {
                if (isFinishing || isDestroyed) return@post
                result.onSuccess {
                    if (it.size < Custom.RATE / 10) Toast.makeText(this, "That file has no sound in it", Toast.LENGTH_LONG).show()
                    else trimmer(file, label, it)
                }.onFailure { Toast.makeText(this, "Couldn't read that file: ${it.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun trimmer(file: String, label: String, samples: FloatArray) {
        val s = skin
        val wave = WaveView(this, samples, Custom.RATE, s)
        val times = s.text("", 12f, s.ink, Typeface.MONOSPACE)
        wave.onChange = { times.text = wave.describe() }
        wave.playhead = { Custom.playhead() }
        wave.onChange()
        val body = s.dialogBody().apply {
            addView(s.note("Drag the two markers so only the callout is highlighted. Zoom to fine-tune."))
            addView(wave, s.lp(top = 10, width = MATCH))
            addView(times, s.lp(top = 6))
            addView(LinearLayout(context).apply {
                addView(s.button("Play") {
                    Custom.preview(samples, wave.start, wave.end)
                    wave.invalidate()
                })
                addView(s.button("Zoom") { wave.zoom() }, s.lp(start = 8))
                addView(s.button("Full") { wave.full() }, s.lp(start = 8))
            }, s.lp(top = 10))
        }
        s.show(
            s.dialog(label)
                .setView(body)
                .setPositiveButton("Save") { _, _ ->
                    Custom.save(this, file, samples, wave.start, wave.end)
                    Events.add("custom sound: $label (%.2f s)".format((wave.end - wave.start) / Custom.RATE.toDouble()))
                    refresh()
                }
                .setNegativeButton("Cancel", null)
                .setOnDismissListener { Custom.stopPreview() },
        )
    }

    // ---- Log and PFD ----

    private fun log(): View {
        val s = skin
        val col = s.page()
        col.addView(subTitle(Page.LOG), s.lp(width = MATCH))
        val maps = s.text("", 12f, s.ink, Typeface.MONOSPACE)
        s.block(s.section(col, "Maps")).addView(maps)
        val events = s.text("", 12f, s.ink, Typeface.MONOSPACE)
        s.block(s.section(col, "Events")).addView(events)
        col.addView(s.note("Every drive is also saved on the phone in files/drive_log.txt."), s.lp(top = 12))
        refresh = {
            maps.text = Events.maps
            events.text = Events.log().ifEmpty { "Nothing yet." }
        }
        return col
    }

    private fun pfd(): View {
        val col = skin.page()
        col.addView(subTitle(Page.PFD), skin.lp(width = MATCH))
        col.addView(PfdView(this), skin.lp(width = MATCH, top = 12))
        return col
    }
}
