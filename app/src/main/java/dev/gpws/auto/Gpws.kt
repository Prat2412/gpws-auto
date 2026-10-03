package dev.gpws.auto

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.quicksettings.TileService
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Turns "metres to destination" readings from the Maps notification into Boeing-style
 * radio-altimeter callouts, plus a few other cockpit alerts driven by GPS speed.
 *
 * Maps only refreshes its notification about once a second and rounds the distance, which is
 * far too coarse for "50, 40, 30, 20, 10". So between refreshes we dead-reckon: with GPS we use
 * the real speed; without it, the speed implied by how fast the Maps number has been dropping.
 *
 * Main thread only.
 */
object Gpws {

    enum class Level { CALLOUT, ADVISORY, CAUTION, WARNING }

    /** `file` is an audio clip in res/raw; if it's missing, text-to-speech says `speech`. */
    class Sound(val file: String, val speech: String, val label: String, val level: Level, val short: String = label)

    class Callout(val metres: Int, val sound: Sound)

    object Alerts {
        val V1 = Sound("gpws_v1", "V one", "V1", Level.ADVISORY)
        val GLIDESLOPE = Sound("gpws_glideslope", "glideslope", "GLIDESLOPE", Level.CAUTION, "G/S")
        val TRAFFIC = Sound("gpws_traffic", "traffic, traffic", "TRAFFIC", Level.CAUTION)
        val CLEAR = Sound("gpws_clear", "clear of conflict", "CLEAR OF CONFLICT", Level.ADVISORY, "CLEAR")
        val OVERSPEED = Sound("gpws_overspeed", "overspeed", "OVERSPEED", Level.WARNING, "OVSPD")
        val APPROACHING_RUNWAY = Sound("gpws_approaching_runway", "approaching runway", "APPROACHING RUNWAY", Level.ADVISORY, "APPR RWY")
        val ON_RUNWAY = Sound("gpws_on_runway", "on runway", "ON RUNWAY", Level.ADVISORY, "ON RWY")
        val TERRAIN = Sound("gpws_terrain", "terrain, terrain", "TERRAIN", Level.CAUTION)
        val DONT_SINK = Sound("gpws_dont_sink", "don't sink, don't sink", "DON'T SINK", Level.CAUTION)
        val AP_DISCONNECT = Sound("gpws_ap_disconnect", "autopilot", "AUTOPILOT DISCONNECT", Level.WARNING, "AP DISC")
        val BANK_ANGLE = Sound("gpws_bank_angle", "bank angle, bank angle", "BANK ANGLE", Level.CAUTION, "BANK")
        val SINK_RATE = Sound("gpws_sink_rate", "sink rate, sink rate", "SINK RATE", Level.CAUTION)
        val PULL_UP = Sound("gpws_pull_up", "pull up, pull up", "PULL UP", Level.WARNING)
        val TOO_LOW_TERRAIN = Sound("gpws_too_low_terrain", "too low, terrain", "TOO LOW TERRAIN", Level.CAUTION, "TOO LOW")
    }

    /** Individually switchable features. The master killswitch is [armed]. */
    enum class Feature(val key: String, val label: String, val default: Boolean = true) {
        CALLOUTS("callouts", "CALLOUTS"),
        V1("v1", "V1"),
        GLIDESLOPE("glideslope", "G/S"),
        TRAFFIC("traffic", "TRAFFIC"),
        OVERSPEED("overspeed", "OVSPD"),
        MINIMUMS("minimums", "MINIMUMS", default = false),
        DUCK("duck", "MUSIC DUCK"),  // off: never touch other apps' audio, just play over it
        RUNWAY("runway", "RUNWAY"),
        SMART_VOL("smart_vol", "SMART VOL"),
        AUTO_ARM("auto_arm", "AUTO ARM"),
        TERRAIN("terrain", "TERR"),
        AP_DISC("ap_disc", "AP DISC"),
        CALL_MUTE("call_mute", "CALL MUTE"),
        BANK_ANGLE("bank_angle", "BANK"),
        SINK_RATE("sink_rate", "SINK RATE"),  // PULL UP too
        TOO_LOW("too_low", "TOO LOW"),
        RETARD("retard", "RETARD"),
        MISSED_RETARD("missed_retard", "MISSED RETARD", default = false),  // RETARD instead of GLIDESLOPE
    }

    private const val TOUCHDOWN_M = 4.0
    private const val ARRIVAL_WINDOW_M = 80.0         // navigation ending closer than this = we landed
    private const val NEW_TRIP_JUMP_M = 20_000.0      // a jump this big means a new destination
    private const val GPS_FRESH_MS = 3000L
    private const val TICK_MS = 200L
    private const val V1_MPS = 40 / 3.6
    private const val TRAFFIC_MPS = 9 / 3.6           // averaging below this for 90 s = stuck in traffic
    private const val CLEAR_MPS = 22 / 3.6            // averaging above this for 15 s = moving again
    private const val TRAFFIC_COOLDOWN_MS = 5 * 60_000L
    private const val TRAFFIC_AHEAD_COOLDOWN_MS = 3 * 60_000L
    private const val GLIDESLOPE_COOLDOWN_MS = 60_000L
    private const val OVERSPEED_COOLDOWN_MS = 2 * 60_000L
    private const val EXACT_NO_GPS_M = 30.0        // dead-reckoning allowed past a Live Update reading without GPS
    private const val WAIT_MS = 3000L                 // a callout waits this long behind one still playing
    private const val CAR_GONE_MS = 20_000L           // the car must stay gone this long to disarm
    private const val AP_DISC_MIN_M = 300.0          // cancelling navigation further out than this = AP disconnect
    private const val SPAM_LIMIT = 20                 // sounds per minute before the spam guard steps in

    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private lateinit var voice: Voice

    private var shown: Double? = null  // distance Maps is currently showing
    private var shownAt = 0L
    private var moved = 0.0            // metres travelled since `shown` appeared
    private var step = 50.0            // Maps' rounding for the current number
    private var exact = false          // the number is Maps' Live Update bar: exact, but sparse
    private var navSpeed = 0.0         // m/s implied by Maps' drops
    private var gpsSpeed = 0.0
    private var gpsAt = 0L
    private var bearing: Float? = null
    private var bearingAt = 0L
    private var lat = 0.0
    private var lon = 0.0
    private var lastTick = 0L
    private var last: Double? = null   // last estimated distance
    private val fired = HashSet<Int>()
    private var countdownSound: Sound? = null  // the number callout playing, if it's still the last sound
    private var waiting: Callout? = null       // the next one, holding until that one finishes
    private var waitingAt = 0L
    private var retardFired = false
    private var retardRepeats = 0
    private var ticking = false
    private var simulating = false
    private var sim: Runnable? = null

    private var v1Done = false
    private var glideslopeAt = -GLIDESLOPE_COOLDOWN_MS
    private var inTraffic = false
    private var trafficAt = -TRAFFIC_COOLDOWN_MS
    private val speeds = ArrayDeque<Pair<Long, Double>>()  // recent GPS speeds, for traffic
    private var overCount = 0
    private var overspeedArmed = true
    private var overspeedAt = -OVERSPEED_COOLDOWN_MS
    private val recentSounds = ArrayDeque<Long>()
    private var spamLogged = false
    private var tripMinutes: Int? = null
    private var tripAt = 0L
    private var aheadNote: String? = null
    private var aheadAt = 0L

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        Events.init(app)
        voice = Voice(app, { isOn(Feature.DUCK) }, { boostMb() })
    }

    private fun prefs() = app.getSharedPreferences("gpws", Context.MODE_PRIVATE)

    // ---- Settings ----

    /** The master killswitch. false = everything silenced, right now and until re-armed. */
    var armed: Boolean
        get() = prefs().getBoolean("enabled", true)
        set(value) {
            prefs().edit().putBoolean("enabled", value).apply()
            if (!value) silence()
            Events.add(if (value) "GPWS ARMED" else "GPWS INHIBIT: everything silenced")
            TileService.requestListeningState(app, ComponentName(app, KillTile::class.java))
        }

    fun isOn(f: Feature) = prefs().getBoolean(f.key, f.default)

    /** GLIDESLOPE and missed-turn RETARD are the same moment, so switching one on switches the other off. */
    fun setOn(f: Feature, on: Boolean) {
        val e = prefs().edit().putBoolean(f.key, on)
        if (on && f == Feature.GLIDESLOPE) e.putBoolean(Feature.MISSED_RETARD.key, false)
        if (on && f == Feature.MISSED_RETARD) e.putBoolean(Feature.GLIDESLOPE.key, false)
        e.apply()
    }

    var overspeedKmh: Int
        get() = prefs().getInt("overspeed_kmh", 100)
        set(value) = prefs().edit().putInt("overspeed_kmh", value).apply()

    /** Metres before the destination where the pack says its minimums call (and 100 m above, if it has one). */
    var minimumsM: Int
        get() = prefs().getInt("minimums_m", 100)
        set(value) = prefs().edit().putInt("minimums_m", value).apply()

    /** TomTom Traffic API key for "traffic ahead"; blank = off. */
    var tomtomKey: String
        get() = prefs().getString("tomtom_key", "") ?: ""
        set(value) = prefs().edit().putString("tomtom_key", value.trim()).apply()

    val pack: Packs.Pack get() = Packs.byId(prefs().getString("pack", "b777"))

    /** Picking an aircraft by hand also ends "Surprise me". */
    fun setPack(id: String) {
        prefs().edit().putString("pack", id).putBoolean("surprise", false).apply()
        val p = Packs.byId(id)
        Events.add("voice: ${p.maker} ${p.model}")
    }

    /** "Surprise me": a different aircraft for every trip. */
    var surprise: Boolean
        get() = prefs().getBoolean("surprise", false)
        set(value) = prefs().edit().putBoolean("surprise", value).apply()

    // A new trip under "Surprise me": any aircraft but the last one.
    private fun shufflePack() {
        val p = Packs.ALL.filter { it.id != pack.id }.random()
        prefs().edit().putString("pack", p.id).apply()
        Events.add("surprise voice: ${p.maker} ${p.model}")
    }

    /** Maps is navigating, but nothing in its notification can be read: most likely a new Maps layout. */
    var mapsUnreadable = false
        private set

    fun onMapsUnreadable(on: Boolean) {
        if (on == mapsUnreadable) return
        mapsUnreadable = on
        Events.add(if (on) "can't read Maps: navigating, but no distance in its notification" else "Maps readable again")
    }

    private fun sound(p: Packs.Pack, c: Packs.Clip, level: Level) = Sound(p.file(c), c.speech, c.label, level)

    private fun retardSound() = Sound(pack.retardFile, "retard, retard", "RETARD", Level.CAUTION)

    /** What gets called out, and where, for the selected pack and minimums setting. */
    fun callouts(): List<Callout> {
        val p = pack
        val list = p.numbers.filter { numberOn(it.name.toInt()) }
            .map { Callout(it.name.toInt(), sound(p, it, Level.CALLOUT)) }.toMutableList()
        val approach = p.approach
        if (isOn(Feature.MINIMUMS)) {
            val dh = minimumsM
            val approachAt = if (approach != null) dh + 100 else null
            // Like the aircraft: the minimums calls replace any number that lands on top of them.
            list.removeAll { abs(it.metres - dh) < 15 || (approachAt != null && abs(it.metres - approachAt) < 15) }
            list += Callout(dh, sound(p, p.minimums, Level.CAUTION))
            if (approach != null && approachAt != null) list += Callout(approachAt, sound(p, approach, Level.CALLOUT))
        }
        return list.sortedByDescending { it.metres }
    }

    /** Every sound the current setup can make, in the order the sound check lists them. */
    fun allSounds(): List<Sound> {
        val p = pack
        return p.numbers.map { sound(p, it, Level.CALLOUT) } +
            listOfNotNull(p.approach?.let { sound(p, it, Level.CALLOUT) }, sound(p, p.minimums, Level.CAUTION)) +
            retardSound() + alertSounds()
    }

    /** The alerts every voice shares. */
    fun alertSounds() = with(Alerts) {
        listOf(
            V1, GLIDESLOPE, TRAFFIC, CLEAR, OVERSPEED, APPROACHING_RUNWAY, ON_RUNWAY, TERRAIN, DONT_SINK,
            TOO_LOW_TERRAIN, BANK_ANGLE, SINK_RATE, PULL_UP, AP_DISCONNECT,
        )
    }

    /** "b777_500" -> "BOEING 777 500": what each sound is, for the list inside a shared sound pack. */
    fun soundLabels(): Map<String, String> =
        Packs.ALL.flatMap { p -> p.clips.map { c -> p.file(c) to "${p.short} ${p.model} ${c.label}" } }.toMap() +
            alertSounds().associate { it.file to it.label }

    /** Callout picker: whether this number plays. */
    fun numberOn(metres: Int) = "$metres" !in (prefs().getStringSet("numbers_off", null) ?: emptySet())

    fun setNumber(metres: Int, on: Boolean) {
        val off = (prefs().getStringSet("numbers_off", null) ?: emptySet()).toMutableSet()
        if (on) off -= "$metres" else off += "$metres"
        prefs().edit().putStringSet("numbers_off", off).apply()
    }

    /** Plays one clip of any pack, for the voice page. */
    fun preview(p: Packs.Pack, c: Packs.Clip) {
        val s = sound(p, c, Level.CALLOUT)
        show(s)
        voice.play(s.file, s.speech)
    }

    // ---- Live state, for the cockpit display ----

    /** Estimated metres to the destination, or null when not navigating. */
    val distance: Double? get() = if (shown == null) null else last
    val navActive get() = shown != null
    val retarded get() = retardFired
    val isSimulating get() = simulating
    val speedKmh: Double? get() = if (gpsFresh()) gpsSpeed * 3.6 else null
    val heading: Float? get() = if (SystemClock.elapsedRealtime() - bearingAt < GPS_FRESH_MS) bearing else null
    val minutesLeft: Int? get() = if (shown == null) null else tripMinutes

    /** "Traffic ahead" note for the display, shown for a minute after TomTom reports a jam. */
    val trafficAhead: String? get() = if (SystemClock.elapsedRealtime() - aheadAt < 60_000) aheadNote else null
    var bankDeg = 0.0
        private set
    var lastSound: Sound? = null
        private set
    var lastSoundAt = 0L
        private set

    /** In the car, by Android Auto or the car's Bluetooth: null until we know. */
    var car: Boolean? = null
        private set
    private var viaAndroidAuto: Boolean? = null
    private var viaBluetooth = false

    /** How we know we're in the car, for the display. */
    val carVia: String? get() = when {
        viaAndroidAuto == true -> "ANDROID AUTO"
        viaBluetooth -> "BLUETOOTH"
        else -> null
    }

    /** The car stereo's Bluetooth name, for cars without Android Auto. */
    var carBluetooth: String?
        get() = prefs().getString("car_bt", null)?.takeIf { it.isNotBlank() }
        set(value) = prefs().edit().putString("car_bt", value ?: "").apply()

    // ---- Real-world inputs (ignored while a simulation runs) ----

    fun onMapsDistance(metres: Double, resolution: Double, minutes: Int?, exact: Boolean = false) {
        if (simulating) return
        tripMinutes = minutes
        this.exact = exact
        distance(metres, resolution)
    }
    /** Maps' next-turn instruction and how far off it is, for the highway (runway) calls. */
    fun onMapsManeuver(instruction: String, turnMetres: Double?) {
        if (simulating || shown == null) return
        if (Runway.approaching(instruction, turnMetres, SystemClock.elapsedRealtime()) && isOn(Feature.RUNWAY)) {
            say(Alerts.APPROACHING_RUNWAY, instruction.take(60))
        }
    }
    /** One reading of Maps' navigation notification. */
    fun onMaps(r: NavParser.Reading) {
        if (r.rerouting) onMapsRerouting()
        if (r.arrived) {
            onMapsArrived()
        } else {
            r.metres?.let {
                onMapsDistance(it, r.resolution, r.minutes, r.exact)
                onMapsManeuver(r.instruction, r.turnMetres)
            }
        }
    }

    fun onMapsRerouting() { if (!simulating) rerouted() }
    fun onMapsArrived() { if (!simulating) arrived() }
    fun onMapsEnded() { if (!simulating) ended() }
    fun onGps(mps: Double?, bearing: Float?, lat: Double, lon: Double, altitude: Double?) {
        if (simulating) return
        this.lat = lat
        this.lon = lon
        gps(mps, bearing)
        if (mps != null && shown != null) checkTerrain(lat, lon, altitude, mps)
        if (mps != null && bearing != null && shown != null && armed && isOn(Feature.TRAFFIC)) {
            Traffic.onFix(tomtomKey, lat, lon, bearing, mps) { trafficAhead(it) }
        }
    }

    /**
     * Android Auto connected or disconnected. With AUTO ARM on, getting in the car arms GPWS and
     * getting out silences it. Only changes count: the killswitch still works mid-drive.
     */
    fun onAndroidAuto(connected: Boolean?) {
        if (connected == null) return
        viaAndroidAuto = connected
        carChanged()
    }

    fun onCarBluetooth(connected: Boolean) {
        viaBluetooth = connected
        carChanged()
    }

    /** Both at once when the listener starts, so the first look is never mistaken for getting in. */
    fun initCar(androidAuto: Boolean?, bluetooth: Boolean) {
        viaAndroidAuto = androidAuto
        viaBluetooth = bluetooth
        carChanged()
    }

    private fun carChanged() {
        val inCar = viaAndroidAuto == true || viaBluetooth
        if (car == null) {
            car = inCar  // the first reading after start is just noted
            return
        }
        if (inCar) main.removeCallbacks(carLeft)  // back within the grace period: never left
        if (inCar == car) return
        if (!inCar) {
            // Car Bluetooth drops for a second when it switches profiles; only a lasting loss counts.
            main.removeCallbacks(carLeft)
            main.postDelayed(carLeft, CAR_GONE_MS)
            return
        }
        car = true
        Events.add("CAR: ${carVia?.lowercase()} connected")
        if (isOn(Feature.AUTO_ARM) && !armed) armed = true
    }

    private val carLeft = Runnable {
        if (viaAndroidAuto == true || viaBluetooth) return@Runnable
        car = false
        Events.add("CAR: disconnected")
        if (isOn(Feature.AUTO_ARM) && armed) armed = false
    }

    /** Smart volume, in millibels: nothing up to 40 km/h, rising to +6 dB at 100 km/h. */
    private fun boostMb(): Int {
        if (!isOn(Feature.SMART_VOL)) return 0
        val kmh = speedKmh ?: return 0
        return (((kmh - 40) / 60).coerceIn(0.0, 1.0) * 600).roundToInt()
    }

    fun onPressure(hPa: Float) = Terrain.onPressure(hPa, SystemClock.elapsedRealtime())

    fun onGravity(v: FloatArray) = Motion.onGravity(v)

    fun onGyro(v: FloatArray) {
        if (!simulating && shown != null) Motion.onGyro(v, SystemClock.elapsedRealtime())?.let { motion(it) }
    }

    fun onLinear(v: FloatArray) {
        if (!simulating && shown != null) Motion.onLinear(v, SystemClock.elapsedRealtime())?.let { motion(it) }
    }

    /** Last known position, for testing the TomTom key. */
    val position get() = lat to lon

    private fun trafficAhead(f: Traffic.Flow) {
        if (!f.jammed || shown == null) return
        val now = SystemClock.elapsedRealtime()
        aheadNote = if (f.closed) "ROAD CLOSED AHEAD" else "TRAFFIC ${f.aheadMetres} M AHEAD"
        aheadAt = now
        if (now - trafficAt < TRAFFIC_AHEAD_COOLDOWN_MS) return
        trafficAt = now
        say(Alerts.TRAFFIC, "jam ~${f.aheadMetres} m ahead: ${f.currentKmh} km/h, normally ${f.freeFlowKmh}")
    }

    // ---- Engine ----

    private fun distance(metres: Double, resolution: Double) {
        val now = SystemClock.elapsedRealtime()
        val prev = shown
        step = resolution.coerceIn(10.0, 100.0)
        if (prev == null || metres > prev + NEW_TRIP_JUMP_M || (retardFired && metres > prev + 50)) {
            if (prev != null) Events.add("new trip: %.0f m".format(metres))
            reset()
            if (surprise) shufflePack()
            main.removeCallbacks(retardAgain)
            tripAt = now
            shown = metres
            shownAt = now
            last = metres
            startTicking()
            return
        }
        if (metres == prev) return
        if (metres > prev + max(150.0, 1.5 * resolution)) {
            goAround(metres, now)
            return
        }
        if (metres < prev) {
            val secs = (now - shownAt) / 1000.0
            if (secs in 0.5..30.0) {
                val v = (prev - metres) / secs
                navSpeed = if (navSpeed == 0.0) v else 0.6 * navSpeed + 0.4 * v
            }
        }
        shown = metres
        shownAt = now
        moved = 0.0
        tick()
    }

    // Off route, and Maps found a longer way in. Like a go-around: re-arm the callouts below us.
    private fun goAround(metres: Double, now: Long) {
        Events.add("reroute: %.0f m → %.0f m".format(last ?: 0.0, metres))
        fired.removeAll { it < metres }
        shown = metres
        shownAt = now
        moved = 0.0
        last = metres
        rerouted()
    }

    private fun rerouted() {
        Runway.reset()
        val now = SystemClock.elapsedRealtime()
        if (shown == null || now - glideslopeAt < GLIDESLOPE_COOLDOWN_MS) return
        glideslopeAt = now
        when {
            isOn(Feature.MISSED_RETARD) -> say(retardSound(), "off route: missed-turn RETARD")
            isOn(Feature.GLIDESLOPE) -> say(Alerts.GLIDESLOPE, "off route")
        }
    }

    private fun gps(mps: Double?, bearing: Float?) {
        val now = SystemClock.elapsedRealtime()
        if (bearing != null) {
            // For the display's horizon: the bank of a coordinated turn at this speed and turn rate.
            val prevBearing = this.bearing
            if (prevBearing != null && mps != null && mps > 1 && now > bearingAt) {
                val turn = ((bearing - prevBearing + 540f) % 360f) - 180f
                val rate = Math.toRadians(turn.toDouble()) / ((now - bearingAt) / 1000.0)
                bankDeg = Math.toDegrees(atan(mps * rate / 9.81)).coerceIn(-35.0, 35.0)
            } else if (mps != null && mps <= 1) {
                bankDeg = 0.0
            }
            this.bearing = bearing
            bearingAt = now
        }
        if (mps == null) return
        gpsSpeed = mps
        gpsAt = now
        if (shown == null) return
        checkV1(mps)
        checkOverspeed(mps, now)
        checkTraffic(mps, now)
        if (Runway.lineUp(mps, now) && isOn(Feature.RUNWAY)) say(Alerts.ON_RUNWAY, "%.0f km/h".format(mps * 3.6))
        Motion.onSpeed(mps, now)?.let { motion(it) }
    }

    private fun motion(call: Motion.Call) = when (call) {
        Motion.Call.BANK_ANGLE ->
            if (isOn(Feature.BANK_ANGLE)) say(Alerts.BANK_ANGLE, "%.2f g sideways".format(Motion.lateralG)) else Unit
        Motion.Call.SINK_RATE ->
            if (isOn(Feature.SINK_RATE)) say(Alerts.SINK_RATE, "braked at %.2f g".format(Motion.brakeG)) else Unit
        Motion.Call.PULL_UP ->
            if (isOn(Feature.SINK_RATE)) say(Alerts.PULL_UP, "braked at %.2f g".format(Motion.brakeG)) else Unit
        Motion.Call.TOO_LOW_TERRAIN ->
            if (isOn(Feature.TOO_LOW)) say(Alerts.TOO_LOW_TERRAIN, "%.2f g jolt".format(Motion.joltG)) else Unit
    }

    private fun checkV1(mps: Double) {
        if (v1Done || mps < V1_MPS || (last ?: 0.0) < 300) return
        v1Done = true
        if (isOn(Feature.V1)) say(Alerts.V1, "%.0f km/h".format(mps * 3.6))
    }

    // One clacker burst per overspeed, never a continuous one. Re-arms once you're 10 km/h under.
    private fun checkOverspeed(mps: Double, now: Long) {
        val limit = overspeedKmh / 3.6
        overCount = if (mps > limit) overCount + 1 else 0
        if (overspeedArmed && overCount >= 2) {
            overspeedArmed = false
            if (now - overspeedAt > OVERSPEED_COOLDOWN_MS && isOn(Feature.OVERSPEED)) {
                overspeedAt = now
                say(Alerts.OVERSPEED, "%.0f km/h".format(mps * 3.6))
            }
        } else if (!overspeedArmed && mps < limit - 10 / 3.6) {
            overspeedArmed = true
        }
    }

    private fun checkTraffic(mps: Double, now: Long) {
        speeds.addLast(now to mps)
        while (speeds.first().first < now - 120_000) speeds.removeFirst()
        if (!inTraffic) {
            val avg = averageSpeed(now, 90_000) ?: return
            if (avg < TRAFFIC_MPS && (last ?: 0.0) > 300 && now - trafficAt > TRAFFIC_COOLDOWN_MS) {
                inTraffic = true
                trafficAt = now
                if (isOn(Feature.TRAFFIC)) say(Alerts.TRAFFIC, "crawling for 90 s")
            }
        } else {
            val avg = averageSpeed(now, 15_000) ?: return
            if (avg > CLEAR_MPS) {
                inTraffic = false
                if (isOn(Feature.TRAFFIC)) say(Alerts.CLEAR, "moving again")
            }
        }
    }

    private fun checkTerrain(lat: Double, lon: Double, altitude: Double?, mps: Double) {
        val call = Terrain.onFix(lat, lon, altitude, mps, SystemClock.elapsedRealtime()) ?: return
        if (!isOn(Feature.TERRAIN)) return
        val grade = Terrain.grade() ?: ""
        say(if (call == Terrain.Call.TERRAIN) Alerts.TERRAIN else Alerts.DONT_SINK, grade)
    }

    /** Average GPS speed over the last [windowMs], or null without that much history yet. */
    private fun averageSpeed(now: Long, windowMs: Long): Double? {
        if (speeds.isEmpty() || speeds.first().first > now - windowMs + 2000) return null
        var sum = 0.0
        var n = 0
        for ((t, v) in speeds) if (t >= now - windowMs) { sum += v; n++ }
        return if (n == 0) null else sum / n
    }

    private fun arrived() {
        if (!retardFired && (last ?: Double.MAX_VALUE) < ARRIVAL_WINDOW_M * 2) retard("Maps: arrived")
    }

    private fun ended() {
        val d = last
        if (!retardFired && (d ?: Double.MAX_VALUE) < ARRIVAL_WINDOW_M) {
            retard("navigation ended")
        } else if (!retardFired && d != null && d > AP_DISC_MIN_M && isOn(Feature.AP_DISC) &&
            SystemClock.elapsedRealtime() - tripAt > 60_000
        ) {
            // Navigation cancelled short of the destination: the autopilot just dropped out.
            say(Alerts.AP_DISCONNECT, "navigation cancelled %.1f km out".format(d / 1000))
        }
        stopTicking()
        reset()
    }

    private fun gpsFresh() = SystemClock.elapsedRealtime() - gpsAt < GPS_FRESH_MS

    private fun tick() {
        val s = shown ?: return
        val now = SystemClock.elapsedRealtime()
        val gps = gpsFresh()
        moved += (if (gps) gpsSpeed else navSpeed) * (now - lastTick) / 1000.0
        lastTick = now
        // Classic Maps rounds ("0.4 km") and changes the number as you cross each step, so never
        // dead-reckon past its rounding. The Live Update bar is exact but only comes every 1-10 s,
        // sometimes 100 m apart: with GPS speed, count down freely in between. Without GPS we
        // can't tell a red light from driving, so stay close to the number Maps shows.
        val floor = when {
            exact && gps -> 0.0
            exact -> max(0.0, s - EXACT_NO_GPS_M)
            else -> max(0.0, s - (if (gps) step else step / 2))
        }
        val d = max(floor, s - moved)
        val prev = last ?: d
        last = d
        if (!live() || !isOn(Feature.CALLOUTS)) return
        // A late Maps update can jump several callouts at once: only say the lowest one.
        val crossed = callouts().filter { it.metres !in fired && prev > it.metres && d <= it.metres }
        crossed.forEach { fired += it.metres }
        crossed.lastOrNull()?.let { c ->
            // Callouts never cut each other off: a new one waits for the one still playing, and if another
            // crosses before its turn only the newest is kept, like the real sequencer skipping behind.
            if (voice.busy && lastSound === countdownSound) {
                waiting = c
                waitingAt = now
            } else {
                countdown(c, d)
            }
        }
        waiting?.let { w ->
            if (!voice.busy) {
                waiting = null
                if (now - waitingAt < WAIT_MS) countdown(w, d)
            }
        }
        if (!retardFired && prev > TOUCHDOWN_M && d <= TOUCHDOWN_M) retard("touchdown")
    }

    private fun countdown(c: Callout, d: Double) {
        countdownSound = c.sound
        say(c.sound, "%.0f m".format(d))
    }

    private fun retard(why: String) {
        retardFired = true  // even when muted: the trip still counts as landed
        if (!live() || !isOn(Feature.CALLOUTS) || !isOn(Feature.RETARD)) return
        say(retardSound(), why)
        retardRepeats = 0
        main.removeCallbacks(retardAgain)
        main.postDelayed(retardAgain, 2000)
    }

    // Like the real thing: keep nagging RETARD until you've actually stopped (needs GPS).
    private val retardAgain = object : Runnable {
        override fun run() {
            val moving = gpsFresh() && gpsSpeed > 1.5
            if (!moving || retardRepeats >= 3 || !live() || onCall() || !isOn(Feature.RETARD)) return
            retardRepeats++
            val r = retardSound()
            voice.play(r.file, "retard")
            show(r)
            main.postDelayed(this, 2000)
        }
    }

    // Real drives obey the killswitch; the simulation is a test, so it always plays.
    private fun live() = armed || simulating

    private fun say(s: Sound, note: String) {
        if (!live()) return
        if (!simulating && onCall()) {
            Events.add("${s.label} held: on a call")
            return
        }
        if (!simulating && !spamOk()) return
        voice.play(s.file, s.speech)
        show(s)
        val boost = boostMb()
        Events.add("${s.label}  ($note)" + if (boost > 0) "  +%.1f dB".format(boost / 100.0) else "")
    }

    /** On a call, or the phone is ringing: with CALL MUTE on, nothing talks over it. */
    private fun onCall(): Boolean {
        if (!isOn(Feature.CALL_MUTE)) return false
        val mode = (app.getSystemService(Context.AUDIO_SERVICE) as AudioManager).mode
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_RINGTONE
    }

    private fun show(s: Sound) {
        lastSound = s
        lastSoundAt = SystemClock.elapsedRealtime()
    }

    // Backstop in case a bug or odd Maps data makes it chatter: cap the sounds per minute.
    private fun spamOk(): Boolean {
        val now = SystemClock.elapsedRealtime()
        while (recentSounds.isNotEmpty() && recentSounds.first() < now - 60_000) recentSounds.removeFirst()
        if (recentSounds.size >= SPAM_LIMIT) {
            if (!spamLogged) Events.add("SPAM GUARD: too many alerts, muted for a minute")
            spamLogged = true
            return false
        }
        spamLogged = false
        recentSounds.addLast(now)
        return true
    }

    private fun silence() {
        voice.stopAll()
        main.removeCallbacks(retardAgain)
        stopSimulation()
    }

    private fun reset() {
        shown = null
        last = null
        moved = 0.0
        navSpeed = 0.0
        fired.clear()
        waiting = null
        retardFired = false
        v1Done = false
        inTraffic = false
        speeds.clear()
        overCount = 0
        overspeedArmed = true
        Runway.reset()
        Terrain.reset()
        Motion.reset()
    }

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            main.postDelayed(this, TICK_MS)
        }
    }

    private fun startTicking() {
        lastTick = SystemClock.elapsedRealtime()
        if (ticking) return
        ticking = true
        main.postDelayed(ticker, TICK_MS)
    }

    private fun stopTicking() {
        ticking = false
        main.removeCallbacks(ticker)
    }

    // ---- Testing without a car ----

    /** Plays one sound, cutting off whatever test was playing. */
    fun testOne(s: Sound) {
        show(s)
        voice.play(s.file, s.speech)
    }


    /** Fakes an approach from 650 m at 54 km/h with a gentle S-turn, braking at the end. */
    fun simulate() {
        stopSimulation()
        voice.stopAll()
        stopTicking()
        reset()
        simulating = true
        Events.add("simulated approach from 650 m")
        var actual = 650.0
        var n = 0
        var t = 0.0
        var stoppedTicks = 0
        val r = object : Runnable {
            override fun run() {
                val v = if (actual > 0) min(15.0, 1.2 + actual * 0.3) else 0.0
                actual = max(0.0, actual - v * 0.25)
                t += 0.25
                gps(v, (90 + 25 * sin(t / 3)).toFloat())
                if (n++ % 4 == 0) distance(mapsRounding(actual), if (actual >= 100) 50.0 else 10.0)
                if (actual == 0.0 && ++stoppedTicks > 12) {
                    ended()
                    simulating = false
                    sim = null
                    Events.add("simulation done")
                    return
                }
                main.postDelayed(this, 250)
            }
        }
        sim = r
        main.post(r)
    }

    private fun stopSimulation() {
        sim?.let { main.removeCallbacks(it) }
        sim = null
        if (simulating) {
            simulating = false
            stopTicking()
            reset()
            Events.add("simulation stopped")
        }
    }

    private fun mapsRounding(m: Double): Double = when {
        m >= 1000 -> (m / 100).roundToInt() * 100.0
        m >= 100 -> (m / 50).roundToInt() * 50.0
        else -> (m / 10).roundToInt() * 10.0
    }
}
