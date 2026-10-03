package dev.gpws.auto

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A Boeing-style primary flight display driven by the live GPWS state: the horizon banks with
 * your turns, the speed tape is GPS speed, and the "altitude" tape and radio altitude are metres
 * left to the destination.
 */
class PfdView(ctx: Context) : View(ctx) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Cockpit.FONT
        textAlign = Paint.Align.CENTER
    }
    private val path = Path()
    private val r = RectF()

    // Layout, in units of 1% of the width
    private var unit = 1f
    private val Number.u: Float get() = toFloat() * unit
    private val adiR = RectF()
    private val speedR = RectF()
    private val distR = RectF()
    private lateinit var sky: Shader
    private lateinit var ground: Shader
    private lateinit var bezelShade: Shader
    private lateinit var glareShade: Shader

    // Smoothed values, so everything glides between GPS fixes instead of jumping
    private var speed = 0f
    private var dist = 0f
    private var hasDist = false
    private var bank = 0f
    private var pitch = 0f
    private var heading = 0f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * 1.04f).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        unit = w / 100f
        adiR.set(22.u, 20.u, 78.u, 76.u)
        speedR.set(5.u, 20.u, 18.u, 76.u)
        distR.set(82.u, 20.u, 95.u, 76.u)
        sky = LinearGradient(0f, -adiR.height(), 0f, 0f, Cockpit.SKY_TOP, Cockpit.SKY, Shader.TileMode.CLAMP)
        ground = LinearGradient(0f, 0f, 0f, adiR.height(), Cockpit.GROUND, Cockpit.GROUND_BOTTOM, Shader.TileMode.CLAMP)
        bezelShade = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF262C35.toInt(), 0xFF0C0F13.toInt(), Shader.TileMode.CLAMP)
        glareShade = LinearGradient(0f, 0f, w * 0.55f, h * 0.45f, 0x16FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        animateValues()
        bezel(c)
        adi(c)
        speedTape(c)
        distanceTape(c)
        compass(c)
        modes(c)
        fill.color = Color.BLACK  // shaders draw at the paint's alpha
        fill.shader = glareShade
        r.set(3.u, 3.u, width - 3.u, height - 3.u)
        c.drawRoundRect(r, 2.u, 2.u, fill)
        fill.shader = null
        postInvalidateOnAnimation()
    }

    private fun animateValues() {
        speed += ((Gpws.speedKmh ?: 0.0).toFloat() - speed) * 0.12f
        val d = Gpws.distance
        when {
            d == null -> hasDist = false
            !hasDist -> { dist = d.toFloat(); hasDist = true }
            else -> dist += (d.toFloat() - dist) * 0.2f
        }
        val turning = Gpws.heading != null && speed > 3
        bank += ((if (turning) Gpws.bankDeg.toFloat() else 0f) - bank) * 0.06f
        val targetPitch = when {
            !hasDist || Gpws.retarded -> 0f
            dist <= 20 -> 2.5f     // flare
            dist <= 1000 -> -2.5f  // on the glideslope
            else -> 1f
        }
        pitch += (targetPitch - pitch) * 0.04f
        Gpws.heading?.let { h ->
            val diff = ((h - heading + 540f) % 360f) - 180f
            heading = (heading + diff * 0.15f + 360f) % 360f
        }
    }

    private fun bezel(c: Canvas) {
        r.set(0f, 0f, width.toFloat(), height.toFloat())
        fill.color = Color.BLACK
        fill.shader = bezelShade
        c.drawRoundRect(r, 4.u, 4.u, fill)
        fill.shader = null
        fill.color = 0xFF3C444E.toInt()
        line.color = 0xFF14181C.toInt()
        line.strokeWidth = 0.25.u
        for ((x, y) in listOf(1.6.u to 1.6.u, width - 1.6.u to 1.6.u, 1.6.u to height - 1.6.u, width - 1.6.u to height - 1.6.u)) {
            c.drawCircle(x, y, 0.8.u, fill)
            c.drawLine(x - 0.55.u, y - 0.55.u, x + 0.55.u, y + 0.55.u, line)
        }
        r.set(3.u, 3.u, width - 3.u, height - 3.u)
        fill.color = Color.BLACK
        c.drawRoundRect(r, 2.u, 2.u, fill)
    }

    // ---- Attitude indicator ----

    private fun adi(c: Canvas) {
        val a = adiR
        val cx = a.centerX()
        val cy = a.centerY()
        val pxDeg = a.height() / 36f
        c.save()
        path.reset()
        path.addRoundRect(a, 3.u, 3.u, Path.Direction.CW)
        c.clipPath(path)
        c.rotate(-bank, cx, cy)
        c.translate(cx, cy + pitch * pxDeg)
        val big = a.width() * 1.5f
        fill.color = Color.BLACK
        fill.shader = sky
        c.drawRect(-big, -big, big, 0f, fill)
        fill.shader = ground
        c.drawRect(-big, 0f, big, big, fill)
        fill.shader = null
        line.color = Cockpit.WHITE
        line.strokeWidth = 0.35.u
        c.drawLine(-big, 0f, big, 0f, line)
        text.textSize = 2.4.u
        text.color = Cockpit.WHITE
        for (deg in listOf(-20f, -15f, -10f, -5f, -2.5f, 2.5f, 5f, 10f, 15f, 20f)) {
            val y = -deg * pxDeg
            val half = when {
                deg % 10f == 0f -> 7.u
                deg % 5f == 0f -> 4.u
                else -> 2.u
            }
            c.drawLine(-half, y, half, y, line)
            if (deg % 10f == 0f) {
                val label = abs(deg).toInt().toString()
                c.drawText(label, -half - 2.5.u, y + 0.9.u, text)
                c.drawText(label, half + 2.5.u, y + 0.9.u, text)
            }
        }
        c.restore()
        bankScale(c, cx, cy, a.height() * 0.42f)
        aircraft(c, cx, cy)
        radioAltitude(c, cx, a)
        flash(c, cx, a)
    }

    private fun bankScale(c: Canvas, cx: Float, cy: Float, rad: Float) {
        line.color = Cockpit.WHITE
        line.strokeWidth = 0.35.u
        for (t in listOf(-60, -45, -30, -20, -10, 10, 20, 30, 45, 60)) {
            val len = if (abs(t) == 30 || abs(t) == 60) 2.4.u else 1.4.u
            c.save()
            c.rotate(t.toFloat(), cx, cy)
            c.drawLine(cx, cy - rad, cx, cy - rad - len, line)
            c.restore()
        }
        fill.color = Cockpit.WHITE
        path.reset()
        path.moveTo(cx, cy - rad)
        path.lineTo(cx - 1.2.u, cy - rad - 2.u)
        path.lineTo(cx + 1.2.u, cy - rad - 2.u)
        path.close()
        c.drawPath(path, fill)
        // The sky pointer turns with the horizon.
        c.save()
        c.rotate(-bank, cx, cy)
        path.reset()
        path.moveTo(cx, cy - rad + 0.4.u)
        path.lineTo(cx - 1.4.u, cy - rad + 2.5.u)
        path.lineTo(cx + 1.4.u, cy - rad + 2.5.u)
        path.close()
        c.drawPath(path, line)
        c.restore()
    }

    private fun aircraft(c: Canvas, cx: Float, cy: Float) {
        fill.color = Color.BLACK
        line.color = Cockpit.WHITE
        line.strokeWidth = 0.4.u
        for (side in listOf(-1f, 1f)) {
            path.reset()
            path.moveTo(cx + side * 17.u, cy - 0.9.u)
            path.lineTo(cx + side * 5.u, cy - 0.9.u)
            path.lineTo(cx + side * 5.u, cy + 3.6.u)
            path.lineTo(cx + side * 6.8.u, cy + 3.6.u)
            path.lineTo(cx + side * 6.8.u, cy + 0.9.u)
            path.lineTo(cx + side * 17.u, cy + 0.9.u)
            path.close()
            c.drawPath(path, fill)
            c.drawPath(path, line)
        }
        r.set(cx - 0.9.u, cy - 0.9.u, cx + 0.9.u, cy + 0.9.u)
        c.drawRect(r, fill)
        c.drawRect(r, line)
    }

    private fun radioAltitude(c: Canvas, cx: Float, a: RectF) {
        if (!hasDist) return
        val v = dist.roundToInt()
        text.color = Cockpit.WHITE
        text.textSize = 2.2.u
        c.drawText("RADIO", cx, a.bottom - 11.u, text)
        text.textSize = 6.u
        text.color = if (v <= Gpws.minimumsM) Cockpit.AMBER else Cockpit.WHITE
        c.drawText(if (v >= 10_000) "%.1fK".format(v / 1000f) else v.toString(), cx, a.bottom - 4.5.u, text)
    }

    // The callout or alert that just played, boxed in the middle of the display.
    private fun flash(c: Canvas, cx: Float, a: RectF) {
        val s = Gpws.lastSound ?: return
        val age = SystemClock.elapsedRealtime() - Gpws.lastSoundAt
        val callout = s.level == Gpws.Level.CALLOUT
        if (age > (if (callout) 1200 else 3500)) return
        if (s.level == Gpws.Level.WARNING && (age / 250) % 2 == 1L) return  // warnings flash
        val color = Cockpit.color(s.level)
        text.textSize = if (callout) 9.u else 5.5.u
        val w = text.measureText(s.label) + 4.u
        val h = text.textSize * 1.3f
        val y = a.top + a.height() * 0.3f
        r.set(cx - w / 2, y - h / 2, cx + w / 2, y + h / 2)
        fill.color = 0xD0000000.toInt()
        c.drawRoundRect(r, 1.u, 1.u, fill)
        line.color = color
        line.strokeWidth = 0.4.u
        c.drawRoundRect(r, 1.u, 1.u, line)
        text.color = color
        c.drawText(s.label, cx, y + text.textSize * 0.36f, text)
    }

    // ---- Speed tape (GPS km/h) ----

    private fun speedTape(c: Canvas) {
        val t = speedR
        val cy = t.centerY()
        val px = t.height() / 120f  // 120 km/h visible
        fill.color = Cockpit.TAPE
        c.drawRect(t, fill)
        c.save()
        c.clipRect(t)
        line.color = Cockpit.WHITE
        line.strokeWidth = 0.35.u
        text.textSize = 3.u
        text.color = Cockpit.WHITE
        text.textAlign = Paint.Align.RIGHT
        var v = max(0, ((speed - 70) / 10).toInt() * 10)
        while (v <= speed + 70) {
            val y = cy - (v - speed) * px
            c.drawLine(t.right - 2.5.u, y, t.right, y, line)
            if (v % 20 == 0) c.drawText(v.toString(), t.right - 3.3.u, y + 1.1.u, text)
            v += 10
        }
        text.textAlign = Paint.Align.CENTER
        // Red and black barber pole from the overspeed limit up.
        var y = cy - (Gpws.overspeedKmh - speed) * px
        var red = true
        while (y > t.top) {
            fill.color = if (red) Cockpit.RED else Color.BLACK
            c.drawRect(t.right - 1.5.u, y - 2.u, t.right, y, fill)
            y -= 2.u
            red = !red
        }
        c.restore()

        val gps = Gpws.speedKmh != null
        readout(c, t.left - 1.u, t.right - 1.5.u, cy, pointRight = true)
        text.textSize = if (gps) 5.u else 3.6.u
        text.color = if (gps) Cockpit.WHITE else Cockpit.AMBER
        c.drawText(if (gps) speed.roundToInt().toString() else "SPD", (t.left + t.right - 2.5.u) / 2, cy + 1.8.u, text)
        text.textSize = 2.4.u
        text.color = Cockpit.MAGENTA
        c.drawText("VMO ${Gpws.overspeedKmh}", t.centerX(), t.top - 1.5.u, text)
        text.color = if (gps) Cockpit.GREEN else Cockpit.AMBER
        c.drawText(if (gps) "GPS" else "NO GPS", t.centerX(), t.bottom + 3.2.u, text)
    }

    // ---- Distance tape (metres to destination, where an altimeter would be) ----

    private fun distanceTape(c: Canvas) {
        val t = distR
        val cy = t.centerY()
        fill.color = Cockpit.TAPE
        c.drawRect(t, fill)
        text.textSize = 2.4.u
        text.color = Cockpit.CYAN
        c.drawText("DEST M", t.centerX(), t.top - 1.5.u, text)
        text.color = Cockpit.GREEN
        c.drawText("MINS ${Gpws.minimumsM}", t.centerX(), t.bottom + 3.2.u, text)
        if (!hasDist) {
            text.textSize = 3.2.u
            text.color = Cockpit.AMBER
            c.drawText("NO", t.centerX(), cy - 0.6.u, text)
            c.drawText("NAV", t.centerX(), cy + 3.u, text)
            return
        }
        val span = when {
            dist > 3000 -> 4000f
            dist > 700 -> 1000f
            else -> 400f
        }
        val tick = span / 20
        val px = t.height() / span
        c.save()
        c.clipRect(t)
        // The ground: amber hatching below zero.
        val y0 = cy + dist * px
        if (y0 < t.bottom) {
            fill.color = Cockpit.AMBER
            c.drawRect(t.left, y0, t.right, t.bottom, fill)
            line.color = Color.BLACK
            line.strokeWidth = 0.7.u
            val depth = t.bottom - y0
            var x = t.left - depth
            while (x < t.right) {
                c.drawLine(x, t.bottom, x + depth, y0, line)
                x += 2.2.u
            }
        }
        line.color = Cockpit.WHITE
        line.strokeWidth = 0.35.u
        text.textSize = 2.8.u
        text.color = Cockpit.WHITE
        text.textAlign = Paint.Align.LEFT
        var i = floor((dist - span / 2) / tick).toInt()
        while (i <= ceil((dist + span / 2) / tick).toInt()) {
            val v = i * tick
            if (v >= 0) {
                val y = cy - (v - dist) * px
                c.drawLine(t.left, y, t.left + 2.5.u, y, line)
                if (i % 5 == 0) c.drawText(v.roundToInt().toString(), t.left + 3.2.u, y + 1.u, text)
            }
            i++
        }
        text.textAlign = Paint.Align.CENTER
        // Minimums bug
        val ym = cy - (Gpws.minimumsM - dist) * px
        fill.color = Cockpit.GREEN
        path.reset()
        path.moveTo(t.left, ym - 1.3.u)
        path.lineTo(t.left + 2.2.u, ym)
        path.lineTo(t.left, ym + 1.3.u)
        path.close()
        c.drawPath(path, fill)
        c.restore()

        readout(c, t.left + 1.5.u, t.right + 1.u, cy, pointRight = false)
        val v = dist.roundToInt()
        text.textSize = 4.2.u
        text.color = if (v <= Gpws.minimumsM) Cockpit.AMBER else Cockpit.WHITE
        c.drawText(if (v >= 10_000) "%.1fK".format(v / 1000f) else v.toString(), (t.left + t.right + 2.5.u) / 2, cy + 1.6.u, text)
    }

    private fun readout(c: Canvas, left: Float, right: Float, cy: Float, pointRight: Boolean) {
        r.set(left, cy - 3.6.u, right, cy + 3.6.u)
        fill.color = Color.BLACK
        c.drawRect(r, fill)
        line.color = Cockpit.WHITE
        line.strokeWidth = 0.35.u
        c.drawRect(r, line)
        val tip = if (pointRight) right + 1.6.u else left - 1.6.u
        val base = if (pointRight) right else left
        path.reset()
        path.moveTo(base, cy - 1.4.u)
        path.lineTo(tip, cy)
        path.lineTo(base, cy + 1.4.u)
        path.close()
        c.drawPath(path, fill)
        c.drawPath(path, line)
    }

    // ---- Compass rose (GPS track) ----

    private fun compass(c: Canvas) {
        val cx = 50.u
        val rad = 30.u
        val top = 83.u
        val ccy = top + rad
        c.save()
        c.clipRect(22.u, top - 0.5.u, 78.u, height - 3.5.u)
        fill.color = 0xFF1C2229.toInt()
        c.drawCircle(cx, ccy, rad, fill)
        line.color = Cockpit.WHITE
        line.strokeWidth = 0.3.u
        text.textSize = 2.6.u
        text.color = Cockpit.WHITE
        for (deg in 0 until 360 step 5) {
            val rel = ((deg - heading + 540f) % 360f) - 180f
            if (abs(rel) > 60) continue
            c.save()
            c.rotate(rel, cx, ccy)
            c.drawLine(cx, ccy - rad, cx, ccy - rad + (if (deg % 10 == 0) 2.u else 1.1.u), line)
            if (deg % 30 == 0) c.drawText(compassLabel(deg), cx, ccy - rad + 5.3.u, text)
            c.restore()
        }
        c.restore()
        val ok = Gpws.heading != null
        r.set(cx - 4.2.u, top - 5.u, cx + 4.2.u, top - 0.9.u)
        fill.color = Color.BLACK
        c.drawRect(r, fill)
        line.color = Cockpit.WHITE
        c.drawRect(r, line)
        text.textSize = 3.u
        text.color = if (ok) Cockpit.WHITE else Cockpit.AMBER
        c.drawText(if (ok) "%03d".format(heading.roundToInt() % 360) else "HDG", cx, top - 1.9.u, text)
        fill.color = Cockpit.WHITE
        path.reset()
        path.moveTo(cx - 1.u, top - 0.9.u)
        path.lineTo(cx + 1.u, top - 0.9.u)
        path.lineTo(cx, top + 1.u)
        path.close()
        c.drawPath(path, fill)
    }

    private fun compassLabel(deg: Int) = when (deg) {
        0 -> "N"
        90 -> "E"
        180 -> "S"
        270 -> "W"
        else -> (deg / 10).toString()
    }

    // ---- Flight-mode annunciators and GPWS status ----

    private fun modes(c: Canvas) {
        val d = Gpws.distance
        val (thrust, roll, pitchMode) = when {
            d == null -> Triple("", "", "")
            Gpws.retarded -> Triple("IDLE", "ROLLOUT", "")
            d <= 15 -> Triple("RETARD", "LOC", "FLARE")
            d <= 1000 -> Triple("SPD", "LOC", "G/S")
            else -> Triple("SPD", "LNAV", "VNAV PTH")
        }
        r.set(22.u, 4.u, 78.u, 11.5.u)
        fill.color = 0xFF10141A.toInt()
        c.drawRect(r, fill)
        line.color = Cockpit.DIM
        line.strokeWidth = 0.3.u
        val col = r.width() / 3
        c.drawLine(r.left + col, r.top + 1.u, r.left + col, r.bottom - 1.u, line)
        c.drawLine(r.left + 2 * col, r.top + 1.u, r.left + 2 * col, r.bottom - 1.u, line)
        text.textSize = 3.4.u
        text.color = Cockpit.GREEN
        listOf(thrust, roll, pitchMode).forEachIndexed { i, mode ->
            c.drawText(mode, r.left + (i + 0.5f) * col, r.bottom - 2.3.u, text)
        }
        text.textSize = 2.8.u
        if (Gpws.armed) {
            text.color = if (d != null) Cockpit.GREEN else Cockpit.WHITE
            c.drawText(if (d != null) "GPWS ARMED" else "GPWS STANDBY", 50.u, 16.6.u, text)
        } else {
            r.set(37.u, 13.3.u, 63.u, 18.u)
            line.color = Cockpit.AMBER
            c.drawRect(r, line)
            text.color = Cockpit.AMBER
            c.drawText("GPWS INHIBIT", 50.u, 16.6.u, text)
        }
    }
}
