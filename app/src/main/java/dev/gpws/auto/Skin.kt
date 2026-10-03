package dev.gpws.auto

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

/** The typefaces both looks use, loaded once. */
object Fonts {
    lateinit var condensedBold: Typeface   // Barlow Condensed Bold: the plate's headings and numbers
    lateinit var condensed: Typeface       // Barlow Condensed SemiBold
    lateinit var barlow: Typeface
    lateinit var barlowMedium: Typeface
    lateinit var signs: Typeface           // Overpass 700
    lateinit var signsBold: Typeface       // Overpass 800
    lateinit var signsBlack: Typeface      // Overpass 900

    fun init(ctx: Context) {
        if (::condensedBold.isInitialized) return
        val r = ctx.resources
        condensedBold = r.getFont(R.font.barlow_condensed_bold)
        condensed = r.getFont(R.font.barlow_condensed_semibold)
        barlow = r.getFont(R.font.barlow_regular)
        barlowMedium = r.getFont(R.font.barlow_medium)
        // Overpass ships as one variable font; each weight is a setting on its 'wght' axis.
        fun overpass(weight: Int): Typeface =
            Typeface.Builder(ctx.assets, "fonts/overpass.ttf").setFontVariationSettings("'wght' $weight").build()
        signs = overpass(700)
        signsBold = overpass(800)
        signsBlack = overpass(900)
    }
}

/**
 * The app's two looks, picked on the STATUS page:
 *  - PLATE: a paper approach chart. Black on cream, ruled boxes, condensed capitals.
 *  - SIGNS: airport wayfinding. Yellow on black, big bold panels.
 * Every page is built from the same pieces (title, section, row, tag, button, chip, tabs) and each
 * piece draws itself in the current look. Home differs most, so MainActivity builds one per look.
 */
class Skin(private val ctx: Context, val kind: Kind) {

    enum class Kind(val title: String, val blurb: String) {
        PLATE("Approach plate", "A paper chart: black on cream, with your descent drawn out"),
        SIGNS("Terminal signs", "Airport wayfinding: yellow on black, big and bold"),
    }

    val plate = kind == Kind.PLATE

    // ---- Palette ----
    val ground = if (plate) 0xFFF4F1E8.toInt() else 0xFF141414.toInt()  // the page
    val ink = if (plate) 0xFF141414.toInt() else 0xFFFFFFFF.toInt()
    val muted = if (plate) 0xFF4A4740.toInt() else 0xFFA8A8A8.toInt()
    val faint = if (plate) 0xFF6E695F.toInt() else 0xFF8C8C8C.toInt()   // callouts already passed
    val accent = if (plate) 0xFF1C55A6.toInt() else 0xFFFFC72C.toInt()  // the next call, things you set
    val warn = if (plate) 0xFFA33A12.toInt() else 0xFFFF7A45.toInt()    // GPWS off, something to fix
    val rule = if (plate) 0xFFB9B3A5.toInt() else 0xFF3A3A3A.toInt()
    val panel = if (plate) 0xFFECE7DA.toInt() else 0xFF232323.toInt()
    private val offFill = 0xFF3A3A3A.toInt()
    private val offInk = 0xFFC9C9C9.toInt()
    private val chipFill = 0xFF383838.toInt()  // a step lighter than the panel it sits on

    // ---- Type ----
    val head: Typeface get() = if (plate) Fonts.condensedBold else Fonts.signsBlack
    val sub: Typeface get() = if (plate) Fonts.condensed else Fonts.signsBold
    val body: Typeface get() = if (plate) Fonts.barlow else Fonts.signs

    /** Plate labels are chart capitals; the signs use sentence case, like real wayfinding. */
    fun name(s: String) = if (plate) s.uppercase() else s

    private val density = ctx.resources.displayMetrics.density
    fun dp(v: Int) = (v * density).toInt()

    fun lp(top: Int = 0, bottom: Int = 0, start: Int = 0, end: Int = 0, width: Int = WRAP, height: Int = WRAP, weight: Float = 0f) =
        LinearLayout.LayoutParams(width, height, weight).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
            marginStart = dp(start)
            marginEnd = dp(end)
        }

    fun text(s: String, sp: Float, color: Int = ink, face: Typeface = body, spacing: Float = 0f) = TextView(ctx).apply {
        text = s
        textSize = sp
        setTextColor(color)
        typeface = face
        letterSpacing = spacing
    }

    /** A small heading over a value: "NEXT CALL". */
    fun label(s: String) =
        if (plate) text(s.uppercase(), 10f, muted, Fonts.barlowMedium, 0.08f) else text(s.uppercase(), 11f, accent, Fonts.signsBold, 0.1f)

    fun note(s: String) = text(s, 13f, muted).apply { setLineSpacing(0f, 1.3f) }

    fun outline(widthDp: Int, color: Int = ink, fillColor: Int = 0, radiusDp: Int = 0) = GradientDrawable().apply {
        setColor(fillColor)
        setStroke(dp(widthDp), color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    fun fill(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    fun vertical() = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    fun horizontal() = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }

    /** A thin line: between cells of a plate box, under a signs heading. */
    fun line(color: Int = ink) = View(ctx).apply { setBackgroundColor(color) }

    fun icon(res: Int, color: Int, sizeDp: Int) = ImageView(ctx).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(color)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    // ---- Page furniture ----

    fun page() = vertical().apply { setPadding(dp(14), dp(12), dp(14), dp(16)) }

    /** A ruled box on the plate, a dark panel on the signs. */
    fun box() = vertical().apply { background = if (plate) outline(2) else fill(panel, 8) }

    class Title(val view: View, private val right: TextView) {
        /** The note at the title's right ("14 OF 16 ON"); hidden while empty. */
        fun setRight(s: String) {
            right.text = s
            right.visibility = if (s.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    /** A page's title. Sub-pages get a way back (to [backTo]); tab pages have the tab bar instead. */
    fun title(heading: String, backTo: String? = null, onBack: (() -> Unit)? = null): Title {
        val right = if (plate) text("", 14f, muted, Fonts.condensed, 0.08f) else text("", 13f, ground, Fonts.signsBold, 0.06f)
        right.visibility = View.GONE
        if (plate) {
            val col = vertical()
            if (backTo != null && onBack != null) {
                col.addView(text("‹  ${backTo.uppercase()}", 15f, ink, Fonts.condensed, 0.08f).apply {
                    minHeight = dp(44)
                    gravity = Gravity.CENTER_VERTICAL
                    setOnClickListener { onBack() }
                })
            }
            col.addView(horizontal().apply {
                background = outline(2)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                addView(text(heading.uppercase(), 30f, ink, Fonts.condensedBold, 0.04f), lp(width = 0, weight = 1f))
                addView(right)
            }, lp(width = MATCH))
            return Title(col, right)
        }
        val row = horizontal().apply { minimumHeight = dp(56) }
        if (onBack != null) {
            row.addView(icon(R.drawable.ic_back, accent, 26).apply {
                contentDescription = "Back to ${backTo ?: "approach"}"
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                scaleType = ImageView.ScaleType.CENTER
                setOnClickListener { onBack() }
            })
        }
        row.addView(text(heading.uppercase(), 28f, ink, Fonts.signsBlack, 0.02f), lp(width = 0, weight = 1f, start = if (onBack == null) 2 else 4))
        row.addView(right.apply {
            background = fill(accent, 4)
            setPadding(dp(10), dp(6), dp(10), dp(4))
        })
        return Title(row, right)
    }

    /** A titled group. Returns the container to add rows to; it's already on [parent]. */
    fun section(parent: LinearLayout, title: String): LinearLayout {
        if (plate) {
            val box = vertical().apply { background = outline(2) }
            box.addView(text(title.uppercase(), 13f, ground, Fonts.condensedBold, 0.16f).apply {
                setBackgroundColor(ink)
                setPadding(dp(12), dp(6), dp(12), dp(6))
            }, lp(width = MATCH))
            parent.addView(box, lp(top = 12, width = MATCH))
            return box
        }
        parent.addView(text(title.uppercase(), 14f, accent, Fonts.signsBlack, 0.12f).apply {
            setPadding(dp(2), dp(16), dp(2), dp(6))
        }, lp(width = MATCH))
        parent.addView(line(accent), lp(width = MATCH, height = dp(4), bottom = 6))
        val rows = vertical()
        parent.addView(rows, lp(width = MATCH))
        return rows
    }

    /** A caution box: something to know before relying on GPWS. Never a left-edge stripe: a full frame. */
    fun caution(parent: LinearLayout, heading: String, message: String): LinearLayout {
        val box = vertical().apply {
            background = if (plate) outline(2, warn) else outline(2, warn, panel, 8)
            setPadding(dp(12), dp(10), dp(12), dp(12))
            addView(text(heading.uppercase(), 13f, warn, if (plate) Fonts.condensedBold else Fonts.signsBlack, 0.14f))
            addView(text(message, 13f, ink, body).apply { setLineSpacing(0f, 1.3f) }, lp(top = 4))
        }
        parent.addView(box, lp(top = 12, width = MATCH))
        return box
    }

    /** Free content inside a section: padded on the plate, a panel on the signs. */
    fun block(section: LinearLayout) = vertical().apply {
        if (plate) {
            setPadding(dp(12), dp(10), dp(12), dp(12))
            if (section.childCount > 1) section.addView(line(rule), lp(width = MATCH, height = dp(1)))
            section.addView(this, lp(width = MATCH))
        } else {
            background = fill(panel, 8)
            setPadding(dp(14), dp(12), dp(14), dp(14))
            section.addView(this, lp(width = MATCH, top = if (section.childCount > 0) 4 else 0))
        }
    }

    /**
     * One line of a section: a name, an optional detail underneath and something on the right.
     * On the plate it's a checklist line with a dotted leader; on the signs, its own panel.
     */
    fun row(section: LinearLayout, name: String, detail: String?, right: View?, onClick: (() -> Unit)?): LinearLayout {
        val row = horizontal()
        val left = vertical()
        if (plate) {
            val line = LinearLayout(ctx).apply { gravity = Gravity.BOTTOM }
            line.addView(text(name.uppercase(), 17f, ink, Fonts.condensed))
            if (right != null) line.addView(Leader(ctx, ink), lp(width = 0, weight = 1f, start = 6, bottom = 4))
            left.addView(line, lp(width = MATCH))
            if (detail != null) left.addView(text(detail, 12f, muted), lp(top = 1))
            row.minimumHeight = dp(54)
            row.setPadding(dp(12), dp(6), dp(12), dp(6))
            if (section.childCount > 1) section.addView(line(rule), lp(width = MATCH, height = dp(1)))
            row.addView(left, lp(width = 0, weight = 1f))
            right?.let { row.addView(it, lp(start = 10)) }
            section.addView(row, lp(width = MATCH))
        } else {
            left.addView(text(name, 16f, ink, Fonts.signsBold))
            if (detail != null) left.addView(text(detail, 12.5f, muted, Fonts.signs))
            row.minimumHeight = dp(56)
            row.background = fill(panel, 8)
            row.setPadding(dp(14), dp(6), dp(8), dp(6))
            row.addView(left, lp(width = 0, weight = 1f))
            right?.let { row.addView(it, lp(start = 12)) }
            section.addView(row, lp(width = MATCH, top = if (section.childCount > 0) 4 else 0))
        }
        if (onClick != null) row.setOnClickListener { onClick() }
        return row
    }

    /** An ON / OFF state (or any two words): filled when on, outlined or greyed when off. */
    fun tag(): TextView = if (plate) {
        text("", 15f, ink, Fonts.condensedBold, 0.08f).apply {
            minWidth = dp(52)
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(5), dp(6), dp(3))
        }
    } else {
        text("", 16f, ink, Fonts.signsBlack).apply {
            minWidth = dp(64)
            minHeight = dp(44)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(2), dp(8), 0)
        }
    }

    fun setTag(tag: TextView, on: Boolean, onText: String = "ON", offText: String = "OFF") {
        tag.text = if (on) onText else offText
        if (plate) {
            tag.background = outline(2, ink, if (on) ink else 0)
            tag.setTextColor(if (on) ground else ink)
        } else {
            tag.background = fill(if (on) accent else offFill, 6)
            tag.setTextColor(if (on) 0xFF141414.toInt() else offInk)
        }
    }

    /** A switch line: tapping anywhere on it flips it. Returns the call that redraws its tag. */
    fun switchRow(section: LinearLayout, name: String, detail: String, isOn: () -> Boolean, flip: () -> Unit): () -> Unit {
        val tag = tag()
        val row = row(section, name, detail, tag, null)
        val redraw = {
            setTag(tag, isOn())
            row.contentDescription = "$name, ${if (isOn()) "on" else "off"}"
        }
        row.setOnClickListener {
            flip()
            redraw()
        }
        return redraw
    }

    /** The value on the right of a row: "100 KM/H", "OK", "CUSTOM". */
    fun value(s: String = "") = if (plate) text(s, 17f, ink, Fonts.condensedBold, 0.04f) else text(s, 15f, accent, Fonts.signsBold)

    /** A real button: outlined, or filled when [primary]. At least 44 dp each way. */
    fun button(label: String, primary: Boolean = false, onClick: () -> Unit) = (
        if (plate) text(label.uppercase(), 15f, if (primary) ground else ink, Fonts.condensed, 0.08f)
        else text(label, 15f, if (primary) 0xFF141414.toInt() else accent, Fonts.signsBlack)
        ).apply {
        minWidth = dp(44)
        minHeight = dp(44)
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(2), dp(12), 0)
        background = if (plate) outline(2, ink, if (primary) ink else 0) else outline(2, accent, if (primary) accent else 0, 6)
        isClickable = true
        setOnClickListener { onClick() }
    }

    fun stepperRow(section: LinearLayout, name: String, value: TextView, minus: () -> Unit, plus: () -> Unit) {
        val controls = horizontal().apply {
            addView(button("−") { minus() }.apply { contentDescription = "Lower $name" })
            addView(value.apply { gravity = Gravity.CENTER }, lp(width = dp(88)))
            addView(button("+") { plus() }.apply { contentDescription = "Raise $name" })
        }
        row(section, name, null, controls, null)
    }

    /** A tap-to-play clip. Your own sounds show in the accent colour. */
    fun chip(label: String, mine: Boolean, onClick: () -> Unit): TextView {
        val color = if (mine) accent else ink
        return (if (plate) text(label, 16f, color, Fonts.condensed, 0.02f) else text(label, 14f, color, Fonts.signsBold)).apply {
            val play = ctx.getDrawable(R.drawable.ic_play)?.mutate()?.apply { setTint(color) }
            setCompoundDrawablesRelativeWithIntrinsicBounds(play, null, null, null)
            compoundDrawablePadding = dp(6)
            minHeight = dp(40)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(2), dp(10), 0)
            background = if (plate) outline(if (mine) 2 else 1, color) else outline(2, if (mine) accent else chipFill, chipFill, 6)
            setOnClickListener { onClick() }
        }
    }

    /** A number in the callout picker: lit when it plays, dim when skipped. */
    fun pick(label: String): TextView = (if (plate) text(label, 17f, ink, Fonts.condensedBold) else text(label, 16f, ink, Fonts.signsBlack)).apply {
        minWidth = dp(54)
        minHeight = dp(44)
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(2), dp(8), 0)
    }

    fun setPick(v: TextView, on: Boolean) {
        if (plate) {
            v.background = outline(2, if (on) ink else rule)
            v.setTextColor(if (on) ink else faint)
            v.paintFlags = if (on) v.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
            else v.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
        } else {
            v.background = fill(if (on) accent else offFill, 6)
            v.setTextColor(if (on) 0xFF141414.toInt() else offInk)
        }
        v.contentDescription = "${v.text} ${if (on) "plays" else "skipped"}"
    }

    /** The tab bar along the bottom. */
    fun tabs(active: Int, names: List<String>, icons: List<Int>, go: (Int) -> Unit): View {
        val bar = LinearLayout(ctx)
        if (plate) {
            bar.background = outline(2)
            names.forEachIndexed { i, n ->
                if (i > 0) bar.addView(line(), lp(width = dp(1), height = MATCH))
                bar.addView(text(n.uppercase(), 14f, if (i == active) ground else ink, Fonts.condensed, 0.1f).apply {
                    gravity = Gravity.CENTER
                    minHeight = dp(50)
                    if (i == active) setBackgroundColor(ink)
                    contentDescription = n
                    setOnClickListener { go(i) }
                }, lp(width = 0, weight = 1f))
            }
        } else {
            bar.background = fill(panel, 10)
            names.forEachIndexed { i, n ->
                val color = if (i == active) accent else offInk
                bar.addView(vertical().apply {
                    gravity = Gravity.CENTER
                    minimumHeight = dp(58)
                    addView(icon(icons[i], color, 22))
                    addView(text(n.uppercase(), 11f, color, Fonts.signsBold, 0.08f), lp(top = 2))
                    contentDescription = n
                    setOnClickListener { go(i) }
                }, lp(width = 0, weight = 1f))
            }
        }
        return bar
    }

    // ---- Dialogs ----

    fun dialog(title: String): AlertDialog.Builder =
        AlertDialog.Builder(ctx, if (plate) android.R.style.Theme_DeviceDefault_Light_Dialog_Alert else android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setCustomTitle(text(name(title), 22f, ink, head, 0.02f).apply { setPadding(dp(22), dp(20), dp(22), dp(6)) })

    fun show(builder: AlertDialog.Builder): AlertDialog = builder.show().apply {
        val card = if (plate) outline(2, ink, ground) else outline(2, accent, ground, 10)
        window?.setBackgroundDrawable(InsetDrawable(card, dp(16)))
        for (which in listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)) {
            getButton(which)?.apply {
                typeface = sub
                setTextColor(accent)
            }
        }
    }

    /** A dialog body to add options or text to. */
    fun dialogBody() = vertical().apply { setPadding(dp(22), dp(4), dp(22), dp(12)) }

    fun option(body: LinearLayout, label: String, color: Int, act: () -> Unit) =
        body.addView(text(name(label), 17f, color, sub, if (plate) 0.06f else 0f).apply {
            minHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { act() }
        }, lp(width = MATCH))
}
