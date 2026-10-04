package dev.gpws.auto

/**
 * Highways as runways, after Honeywell's Runway Awareness and Advisory System: "APPROACHING RUNWAY"
 * as Maps counts down to a turn onto a highway, "ON RUNWAY" once that turn is behind you and you're
 * up to speed. Reads only Maps' own turn instructions ("Take the ramp onto NH 48"), so it needs no
 * network or map data. No runway numbers: that would take a clip per number.
 *
 * Joining a highway Maps never told you to turn onto (you took it instead of the service road it
 * planned) still gets its ON RUNWAY, once Maps names the highway as the road you're on.
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
    // Maps naming the road you're on: "Head southwest on NH 48", "Continue on NH 48", "Keep left to stay on NH 48".
    private val ON = Regex("""\b(?:head\b.*?\bon|continue(?:\s+straight)?\s+on(?:to)?|stay\s+on)\s+(.*)""", RegexOption.IGNORE_CASE)
    private val SIDE_ROAD = Regex("""\b(?:service|link)\s+r(?:oa)?d\b""", RegexOption.IGNORE_CASE)

    private var highway: String? = null  // the highway Maps is taking us onto
    private var called = false
    private var passedAt = 0L            // when that turn left the notification: we took it
    private var fast = 0
    private var cruise: String? = null   // a highway Maps says we're on, without a turn onto it we saw
    private var cruiseAt = 0L
    private var cruiseFast = 0
    private val announced = HashSet<String>()  // highways ON RUNWAY was said for, this trip
    private var approachAt = -COOLDOWN_MS
    private var onAt = -COOLDOWN_MS

    private fun id(road: String) = HIGHWAY.find(road)?.value?.lowercase()?.replace(Regex("""[\s-]"""), "")

    /** The highway [instruction] turns onto, or null if it isn't a turn onto one. */
    fun entry(instruction: String): String? {
        if (!ENTER.containsMatchIn(instruction) || NOT_ENTER.containsMatchIn(instruction)) return null
        return id(instruction)
    }

    /** The highway [instruction] says we're already on ("Head southwest on NH 48"), or null. */
    fun on(instruction: String): String? {
        val road = ON.find(instruction)?.groupValues?.get(1)?.substringBefore('·') ?: return null
        if (SIDE_ROAD.containsMatchIn(road)) return null
        return id(road)
    }

    /** Maps updates with a distance. True = say APPROACHING RUNWAY. */
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

    /** Every Maps update, even one without a distance: a highway we're on that we never saw us join. */
    fun cruising(instruction: String, now: Long) {
        val h = on(instruction) ?: return
        if (h in announced || h == highway || h == cruise) return
        cruise = h
        cruiseAt = now
        cruiseFast = 0
    }

    /** Every GPS fix. True = say ON RUNWAY. */
    fun lineUp(mps: Double, now: Long): Boolean {
        val h = taken(mps, now) ?: cruised(mps, now) ?: return false
        announced += h
        if (now - onAt < COOLDOWN_MS) return false
        onAt = now
        return true
    }

    // Up to speed after taking the turn onto a highway.
    private fun taken(mps: Double, now: Long): String? {
        val h = highway ?: return null
        if (passedAt == 0L) return null
        if (now - passedAt > LINEUP_WINDOW_MS) {
            reset()
            return null
        }
        fast = if (mps >= LINEUP_MPS) fast + 1 else 0
        if (fast < LINEUP_FIXES) return null
        reset()
        return h
    }

    // Up to speed on a highway Maps says we're on.
    private fun cruised(mps: Double, now: Long): String? {
        val h = cruise ?: return null
        if (now - cruiseAt > LINEUP_WINDOW_MS) {
            cruise = null
            return null
        }
        cruiseFast = if (mps >= LINEUP_MPS) cruiseFast + 1 else 0
        if (cruiseFast < LINEUP_FIXES) return null
        cruise = null
        return h
    }

    /** Off route: whatever turn we were waiting for no longer counts. Still on the same road, though. */
    fun reset() {
        highway = null
        called = false
        passedAt = 0L
        fast = 0
    }

    /** A new trip: every highway gets its ON RUNWAY again. */
    fun newTrip() {
        reset()
        cruise = null
        announced.clear()
    }
}
