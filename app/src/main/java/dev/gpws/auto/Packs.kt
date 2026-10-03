package dev.gpws.auto

/**
 * Voice packs: one aircraft's callout voice, stored as res/raw/<id>_<clip>.wav.
 * Every pack has the numbers 2500 → 10; what else it says depends on the manufacturer.
 * All clips come from the FlightGear project (GPL); see README for exact sources.
 */
object Packs {

    class Clip(val name: String, val label: String, val speech: String)

    class Pack(
        val id: String,
        val maker: String,
        val short: String,         // the maker on a pushbutton: BOEING, AIRBUS, MDC
        val model: String,
        val tagline: String,       // the one-line subtitle on the voice page
        val description: String,
        val minimumsName: String,  // what this manufacturer calls the minimums setting
        val approach: Clip?,       // said 100 m above minimums, if this aircraft has it
        val minimums: Clip,        // said at minimums
        val retard: Clip?,         // null: RETARD is borrowed from the Airbus pack
    ) {
        val numbers = NUMBERS.map { (m, speech) -> Clip("$m", "$m", speech) }
        val clips: List<Clip> get() = numbers + listOfNotNull(approach, minimums, retard)
        fun file(c: Clip) = "${id}_${c.name}"
        val retardFile get() = if (retard != null) file(retard) else "a320_retard"
    }

    val NUMBERS = listOf(
        2500 to "twenty five hundred", 1000 to "one thousand", 500 to "five hundred", 400 to "four hundred",
        300 to "three hundred", 200 to "two hundred", 100 to "one hundred", 50 to "fifty", 40 to "forty",
        30 to "thirty", 20 to "twenty", 10 to "ten",
    )

    private val MINIMUMS = Clip("minimums", "MINIMUMS", "minimums")
    private val MINIMUM = Clip("minimum", "MINIMUM", "minimum")

    val ALL = listOf(
        Pack(
            "b777", "Boeing", "BOEING", "777", "HONEYWELL EGPWS",
            "The Honeywell ground-proximity voice fitted to most Boeing jets. Boeings never say " +
                "RETARD, so this pack borrows the Airbus one for the stop.",
            "Decision height", null, MINIMUMS, null,
        ),
        Pack(
            "b737", "Boeing", "BOEING", "737", "APPROACHING MINIMUMS",
            "The 737 Next Generation callouts, with the full \"approaching minimums, minimums\" pair. " +
                "RETARD is borrowed from Airbus.",
            "Decision height", Clip("approaching_minimums", "APPR MINS", "approaching minimums"), MINIMUMS, null,
        ),
        Pack(
            "a320", "Airbus", "AIRBUS", "A320", "FWC · RETARD",
            "The A320 flight warning computer voice. Airbus is the family that says RETARD, and it " +
                "keeps saying it until you stop.",
            "Minimum", Clip("hundred_above", "100 ABOVE", "hundred above"), MINIMUM, Clip("retard", "RETARD", "retard, retard"),
        ),
        Pack(
            "md11", "McDonnell Douglas", "MDC", "MD-11", "CAWS",
            "From the MD-11's central aural warning system, on McDonnell Douglas' last widebody. " +
                "RETARD is borrowed from Airbus.",
            "Minimum", Clip("approaching_minimum", "APPR MIN", "approaching minimum"), MINIMUM, null,
        ),
        Pack(
            "dc10", "McDonnell Douglas", "MDC", "DC-10", "CAWS · 1970s",
            "From the DC-10's central aural warning system: McDonnell Douglas' 1970s trijet and the " +
                "oldest airframe in the set. RETARD is borrowed from Airbus.",
            "Minimum", null, MINIMUM, null,
        ),
    )

    fun byId(id: String?) = ALL.firstOrNull { it.id == id } ?: ALL[0]
}
