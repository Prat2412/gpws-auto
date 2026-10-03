package dev.gpws.auto

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The approach plate's profile view: the glide path from 2500 m down to the destination, flown
 * part solid and the rest dashed, with you on it, the decision height marked and RETARD at the end.
 */
class ProfileView(ctx: Context, private val skin: Skin) : View(ctx) {

    /** Where you are: 0 = 2500 m or more out, 1 = there; null = not navigating. */
    var fraction: Float? = null
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** The decision height, same scale; null = minimums off. */
    var dh: Float? = null
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** Whether RETARD is switched on, so the chart only promises it when it'll be said. */
    var retard = true
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var youLabel = ""
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val d = resources.displayMetrics.density
    private val path = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = skin.ink
        strokeWidth = 2.5f * d
        style = Paint.Style.STROKE
    }
    private val ahead = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = skin.ink
        strokeWidth = 2 * d
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(6 * d, 5 * d), 0f)
    }
    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = skin.rule
        strokeWidth = d
        pathEffect = DashPathEffect(floatArrayOf(2 * d, 3 * d), 0f)
    }
    private val ground = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = skin.ink
        strokeWidth = 1.5f * d
    }
    private val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = skin.ink }
    private val paper = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = skin.ground }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = skin.accent
        strokeWidth = 3 * d
    }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = skin.ink
        textSize = 12 * resources.displayMetrics.scaledDensity
        typeface = Fonts.condensed
        textAlign = Paint.Align.CENTER
    }
    private val bold = Paint(mark).apply { typeface = Fonts.condensedBold }
    private val dhText = Paint(bold).apply { color = skin.accent }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (186 * d).toInt())

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val left = 14 * d
        val top = 28 * d
        val floor = height - 26 * d
        val endX = w - 36 * d
        val endY = floor - 8 * d
        fun x(f: Float) = left + f * (endX - left)
        fun y(f: Float) = top + f * (endY - top)

        for (i in 0..3) {
            val f = i / 4f
            c.drawLine(x(f), y(f), x(f), floor, guide)
        }
        c.drawLine(0f, floor, w, floor, ground)
        c.drawRect(endX - 8 * d, floor - 4 * d, w, floor + d, solid)

        val you = fraction
        if (you == null) {
            c.drawLine(left, top, endX, endY, ahead)
        } else {
            c.drawLine(left, top, x(you), y(you), path)
            c.drawLine(x(you), y(you), endX, endY, ahead)
        }
        dh?.let { f ->
            c.drawLine(x(f) - 9 * d, y(f), x(f) + 9 * d, y(f), bar)
            c.drawText("DH", x(f), y(f) - 9 * d, dhText)
        }
        if (you != null) {
            val px = x(you)
            val py = y(you)
            c.drawLine(px, py - 9 * d, px, py - 36 * d, ground)
            val half = bold.measureText(youLabel) / 2 + 2 * d
            c.drawText(youLabel, px.coerceIn(half, w - half), py - 42 * d, bold)
            c.drawCircle(px, py, 7 * d, paper)
            c.drawCircle(px, py, 7 * d, path)
            c.drawCircle(px, py, 2.5f * d, solid)
        }
        if (retard) c.drawText("RETARD", w - 22 * d, floor - 14 * d, bold)
        listOf("2500", "1000", "500", "100").forEachIndexed { i, s -> c.drawText(s, x(i / 4f), floor + 17 * d, mark) }
        c.drawText("0", endX + 14 * d, floor + 17 * d, mark)
    }

    companion object {
        val MARKS = listOf(2500, 1000, 500, 100, 0)

        /** Evenly spaced marks, linear between them: the callouts bunch up at the end, so should the scale. */
        fun position(metres: Double): Float {
            if (metres >= MARKS[0]) return 0f
            for (i in 0 until MARKS.lastIndex) {
                val hi = MARKS[i]
                val lo = MARKS[i + 1]
                if (metres >= lo) return ((i + (hi - metres) / (hi - lo)) / MARKS.lastIndex).toFloat()
            }
            return 1f
        }
    }
}

/** The dotted leader on a checklist line: "TERRAIN ........ ON". */
class Leader(ctx: Context, color: Int) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val dots = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        strokeWidth = 2 * d
        pathEffect = DashPathEffect(floatArrayOf(2 * d, 3 * d), 0f)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (8 * d).toInt())

    override fun onDraw(c: Canvas) = c.drawLine(0f, height - 2 * d, width.toFloat(), height - 2 * d, dots)
}

/** Lays children out left to right, wrapping onto new rows: for chips. */
class FlowLayout(ctx: Context, private val gap: Int) : ViewGroup(ctx) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            measureChild(child, MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + child.measuredWidth > maxW) {
                x = 0
                y += rowH + gap
                rowH = 0
            }
            x += child.measuredWidth + gap
            rowH = max(rowH, child.measuredHeight)
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), y + rowH + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (x > 0 && x + child.measuredWidth > maxW) {
                x = 0
                y += rowH + gap
                rowH = 0
            }
            child.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + child.measuredWidth, paddingTop + y + child.measuredHeight)
            x += child.measuredWidth + gap
            rowH = max(rowH, child.measuredHeight)
        }
    }
}

/** A waveform with draggable start and end markers, for trimming an imported sound down to the callout. */
class WaveView(ctx: Context, private val samples: FloatArray, private val rate: Int, private val skin: Skin) : View(ctx) {

    var start: Int
    var end: Int
    var onChange: () -> Unit = {}
    var playhead: () -> Int? = { null }

    private val d = resources.displayMetrics.density
    private var from = 0               // the visible window, in samples
    private var to = samples.size
    private var dragging = 0           // -1 start marker, 1 end marker
    private val blocks: FloatArray     // peak of every BLOCK samples, so redraws stay fast on long files
    private val scale: Float
    private val loud = Paint().apply { color = if (skin.plate) skin.ink else skin.accent; strokeWidth = 1f }
    private val quiet = Paint().apply { color = skin.rule; strokeWidth = 1f }
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (skin.plate) skin.accent else skin.ink; strokeWidth = 2 * d }
    private val head = Paint().apply { color = skin.warn; strokeWidth = 2 * d }

    init {
        val (a, b) = Custom.bounds(samples)
        start = a
        end = b
        blocks = FloatArray((samples.size + BLOCK - 1) / BLOCK) { i ->
            var p = 0f
            for (j in i * BLOCK until min(samples.size, (i + 1) * BLOCK)) p = max(p, abs(samples[j]))
            p
        }
        scale = max(1e-6f, blocks.maxOrNull() ?: 0f)
    }

    /** Fills the view with the selection plus a margin, for placing the markers precisely. */
    fun zoom() {
        val pad = max((end - start) / 3, rate / 4)
        from = max(0, start - pad)
        to = min(samples.size, end + pad)
        invalidate()
    }

    fun full() {
        from = 0
        to = samples.size
        invalidate()
    }

    fun describe() = String.format(
        java.util.Locale.US, "%.2f – %.2f s   (%.2f s)", start / rate.toFloat(), end / rate.toFloat(), (end - start) / rate.toFloat(),
    )

    private fun x(i: Int) = (i - from).toFloat() / (to - from) * width
    private fun index(px: Float) = (from + px / width * (to - from)).toInt().coerceIn(0, samples.size)

    private fun peak(a: Int, b: Int): Float {
        var p = 0f
        if (b - a >= BLOCK * 2) {
            for (k in a / BLOCK until min(blocks.size, b / BLOCK)) p = max(p, blocks[k])
        } else {
            for (j in a until min(samples.size, b)) p = max(p, abs(samples[j]))
        }
        return p
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (150 * d).toInt())

    override fun onDraw(c: Canvas) {
        c.drawColor(skin.panel)
        val mid = height / 2f
        val span = (to - from).toFloat() / width
        for (px in 0 until width) {
            val a = from + (px * span).toInt()
            val b = max(a + 1, from + ((px + 1) * span).toInt())
            val h = max(0.5f, peak(a, b) / scale * (mid - 4 * d))
            c.drawLine(px.toFloat(), mid - h, px.toFloat(), mid + h, if (a in start until end) loud else quiet)
        }
        for (i in listOf(start, end)) {
            val xi = x(i)
            c.drawLine(xi, 0f, xi, height.toFloat(), marker)
            c.drawRect(xi - 6 * d, 0f, xi + 6 * d, 12 * d, marker)  // a tab to grab
        }
        playhead()?.let {
            val xp = x(it)
            c.drawLine(xp, 0f, xp, height.toFloat(), head)
            postInvalidateOnAnimation()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = if (abs(e.x - x(start)) <= abs(e.x - x(end))) -1 else 1
                move(e.x)
            }
            MotionEvent.ACTION_MOVE -> move(e.x)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = 0
        }
        return true
    }

    private fun move(px: Float) {
        val i = index(px)
        val shortest = rate / 10
        if (dragging < 0) start = i.coerceIn(0, end - shortest) else end = i.coerceIn(start + shortest, samples.size)
        onChange()
        invalidate()
    }

    companion object {
        private const val BLOCK = 64
    }
}
