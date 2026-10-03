package dev.gpws.auto

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The car's own g-forces as GPWS modes:
 *  - BANK ANGLE: a corner taken hard, over 0.4 g sideways for most of a second.
 *  - SINK RATE / PULL UP: braking harder than 0.45 g / 0.7 g, said once the braking is over:
 *    in the middle of an emergency stop, the last thing anyone needs is a voice.
 *  - TOO LOW TERRAIN: a speed breaker or pothole hit hard enough to jolt the car over 0.5 g.
 * "Up" comes from the gravity sensor, so it doesn't matter how the phone is mounted, and braking
 * is read from GPS speed, which doesn't care where the phone is at all.
 *
 * Main thread only.
 */
object Motion {

    enum class Call { BANK_ANGLE, SINK_RATE, PULL_UP, TOO_LOW_TERRAIN }

    private const val G = 9.81
    private const val BANK_G = 0.4
    private const val BANK_HOLD_MS = 700L
    private const val SINK_MS2 = 0.45 * G
    private const val PULL_UP_MS2 = 0.7 * G
    private const val JOLT_MS2 = 0.5 * G
    private const val MIN_MPS = 25 / 3.6
    private const val BANK_COOLDOWN_MS = 60_000L
    private const val BRAKE_COOLDOWN_MS = 2 * 60_000L
    private const val JOLT_COOLDOWN_MS = 60_000L
    private const val SPEED_FRESH_MS = 3000L

    /** What set off the last call, for the log. */
    var lateralG = 0.0
        private set
    var brakeG = 0.0
        private set
    var joltG = 0.0
        private set

    private val up = FloatArray(3)          // unit vector pointing up, in the phone's axes
    private var hasUp = false
    private var yawRate = 0.0               // rad/s about "up", smoothed
    private var bankSince = 0L
    private var bankAt = -BANK_COOLDOWN_MS
    private val vertical = DoubleArray(5)   // last five vertical accelerations: 100 ms
    private var vi = 0
    private var joltAt = -JOLT_COOLDOWN_MS
    private val speeds = ArrayDeque<Pair<Long, Double>>()
    private var peakDecel = 0.0
    private var hard = 0                    // fixes in a row over the braking limit
    private var calmSince = 0L
    private var brakeAt = -BRAKE_COOLDOWN_MS
    private var mps = 0.0
    private var mpsAt = -SPEED_FRESH_MS

    fun onGravity(g: FloatArray) {
        val n = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2])
        if (n < 1f) return
        for (i in 0..2) up[i] = g[i] / n
        hasUp = true
    }

    private fun moving(now: Long) = now - mpsAt < SPEED_FRESH_MS && mps >= MIN_MPS

    /** Gyroscope: turning about "up" is the car cornering; times speed, that's the sideways g. */
    fun onGyro(w: FloatArray, now: Long): Call? {
        if (!hasUp) return null
        yawRate += 0.2 * ((w[0] * up[0] + w[1] * up[1] + w[2] * up[2]) - yawRate)
        val g = abs(mps * yawRate) / G
        if (!moving(now) || g < BANK_G) {
            bankSince = 0L
            return null
        }
        if (bankSince == 0L) bankSince = now
        if (now - bankSince < BANK_HOLD_MS || now - bankAt < BANK_COOLDOWN_MS) return null
        bankAt = now
        lateralG = g
        return Call.BANK_ANGLE
    }

    /** Linear acceleration (gravity taken out): the vertical part is the road hitting the wheels. */
    fun onLinear(a: FloatArray, now: Long): Call? {
        if (!hasUp) return null
        vertical[vi++ % vertical.size] = (a[0] * up[0] + a[1] * up[1] + a[2] * up[2]).toDouble()
        val avg = vertical.average()  // one rattle of the phone mount isn't a speed breaker
        if (!moving(now) || abs(avg) < JOLT_MS2 || now - joltAt < JOLT_COOLDOWN_MS) return null
        joltAt = now
        joltG = abs(avg) / G
        return Call.TOO_LOW_TERRAIN
    }

    /** Every GPS fix. Deceleration is taken over a second, so one bad fix can't fake an emergency stop. */
    fun onSpeed(v: Double, now: Long): Call? {
        mps = v
        mpsAt = now
        speeds.addLast(now to v)
        while (speeds.size > 1 && speeds.first().first < now - 1500) speeds.removeFirst()
        val (t0, v0) = speeds.first()
        val decel = if (now - t0 >= 800) (v0 - v) / ((now - t0) / 1000.0) else 0.0
        if (decel >= SINK_MS2) {
            // Real braking lasts; a GPS glitch is one fix. Only two in a row count.
            if (++hard >= 2) peakDecel = max(peakDecel, decel)
            calmSince = 0L
            return null
        }
        hard = 0
        if (peakDecel == 0.0) return null
        // The braking's over once it has eased for a second, or the car has stopped.
        if (calmSince == 0L) calmSince = now
        if (now - calmSince < 1000 && v > 1) return null
        val peak = peakDecel
        peakDecel = 0.0
        calmSince = 0L
        if (now - brakeAt < BRAKE_COOLDOWN_MS) return null
        brakeAt = now
        brakeG = peak / G
        return if (peak >= PULL_UP_MS2) Call.PULL_UP else Call.SINK_RATE
    }

    fun reset() {
        speeds.clear()
        hard = 0
        peakDecel = 0.0
        calmSince = 0L
        bankSince = 0L
    }
}
