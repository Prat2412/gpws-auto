package dev.gpws.auto

import android.graphics.Color
import android.graphics.Typeface

/** Boeing flight-deck palette and type. */
object Cockpit {
    val BG = Color.parseColor("#07090C")
    val PANEL = Color.parseColor("#141920")
    val PANEL_EDGE = Color.parseColor("#262E38")
    val FACE_TOP = Color.parseColor("#2C333C")
    val FACE_BOTTOM = Color.parseColor("#15191E")
    val TAPE = Color.parseColor("#B0262C35")
    val WHITE = Color.parseColor("#F2F4F5")
    val GREY = Color.parseColor("#8A949E")
    val DIM = Color.parseColor("#3A424B")
    val GREEN = Color.parseColor("#3CF26A")
    val CYAN = Color.parseColor("#37DAF2")
    val MAGENTA = Color.parseColor("#FF5CE1")
    val AMBER = Color.parseColor("#FFB000")
    val RED = Color.parseColor("#FF3A2F")
    val HAZARD = Color.parseColor("#F2C200")
    val SKY_TOP = Color.parseColor("#0A4596")
    val SKY = Color.parseColor("#3A8FE6")
    val GROUND = Color.parseColor("#8E5A28")
    val GROUND_BOTTOM = Color.parseColor("#4A2C10")

    val FONT: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    val MONO: Typeface = Typeface.MONOSPACE

    fun color(level: Gpws.Level) = when (level) {
        Gpws.Level.CALLOUT -> WHITE
        Gpws.Level.ADVISORY -> GREEN
        Gpws.Level.CAUTION -> AMBER
        Gpws.Level.WARNING -> RED
    }
}
