package dev.gpws.auto

/**
 * Highways as runways, after Honeywell's Runway Awareness and Advisory System: "APPROACHING RUNWAY"
 * as Maps counts down to a turn onto a highway, "ON RUNWAY" once that turn is behind you and you're
 * up to speed. Reads only Maps' own turn instructions ("Take the ramp onto NH 48"), so it needs no
 * network or map data. No runway numbers: that would take a clip per number.
 *
 * Main thread only.
 */
object Runway {

    private const val APPROACH_M = 400.0            // APPROACHING RUNWAY this far from the turn
    private const val LINEUP_MPS = 50 / 3.6         // ON RUNWAY once this fast after the turn...
    private const val LINEUP_FIXES = 4              // ...for this many GPS fixes in a row (~2 s)...
    private const val LINEUP_WINDOW_MS = 3 * 60_000L // ...within this long of taking it
    private const val COOLDOWN_MS = 2 * 60_000L

    // What Maps calls highways: "NH 48", "NH48A", "AH47", "I-95", "Mumbai–Pune Expressway", "Samruddhi Mahamarg".
    private val HIGHWAY = Regex(
        """\b(?:NH|AH|NE)[\s-]?\d+[A-Z]?\b|\bI-\d+\b|""" +
            """\b(?:expressway|expy|highway|hwy|motorway|freeway|fwy|interstate|autobahn|tollway|turnpike|mahamarg)\b""",
        RegexOption.IGNORE_CASE,
    )
    // Getting on: "Take the ramp onto…", "Merge onto…", "Turn left onto…", "Join…".
    private val ENTER = Regex("""\b(?:onto|ramp|merge|join)\b""", RegexOption.IGNORE_CASE)
    // Already on one, leaving, or beside it: "Continue on…", "Keep left to stay on…", "Take exit 12…",
    // "Turn left onto NH 48 Service Rd", "Turn left onto Ram Nagar Hwy Link Rd".
    private val NOT_ENTER = Regex("""\b(?:exit|continue|stay|(?:service|link)\s+r(?:oa)?d)\b""", RegexOption.IGNORE_CASE)

    private var highway: String? = null  // the highway Maps is taking us onto
    private var called = false
    private var passedAt = 0L            // when that turn left the notification: we took it
    private var fast = 0
    private var approachAt = -COOLDOWN_MS
    private var onAt = -COOLDOWN_MS

    /** The highway [instruction] turns onto, or null if it isn't a turn onto one. */
    fun entry(instruction: String): String? {
        if (!ENTER.containsMatchIn(instruction) || NOT_ENTER.containsMatchIn(instruction)) return null
        return HIGHWAY.find(instruction)?.value?.lowercase()?.replace(Regex("""[\s-]"""), "")
    }

    /** Every Maps update. True = say APPROACHING RUNWAY. */
    fun approaching(instruction: String, turnMetres: Double?, now: Long): Boolean {
        val h = entry(instruction)
        if (h == null) {
            if (highway != null && passedAt == 0L) passedAt = now
            return false
        }
        if (h != highway) {
            highway = h
            called = false
            passedAt = 0L
            fast = 0
        }
        if (called || turnMetres == null || turnMetres > APPROACH_M) return false
        called = true
        if (now - approachAt < COOLDOWN_MS) return false
        approachAt = now
        return true
    }

    /** Every GPS fix. True = say ON RUNWAY. */
    fun lineUp(mps: Double, now: Long): Boolean {
        if (highway == null || passedAt == 0L) return false
        if (now - passedAt > LINEUP_WINDOW_MS) {
            reset()
            return false
        }
        fast = if (mps >= LINEUP_MPS) fast + 1 else 0
        if (fast < LINEUP_FIXES) return false
        reset()
        if (now - onAt < COOLDOWN_MS) return false
        onAt = now
        return true
    }

    /** Off route or trip over: whatever turn we were waiting for no longer counts. */
    fun reset() {
        highway = null
        called = false
        passedAt = 0L
        fast = 0
    }
}
