package io.resultguesser.i18n

import io.resultguesser.predict.Game
import io.resultguesser.predict.Leg
import io.resultguesser.predict.Period
import io.resultguesser.predict.PlayerRef
import io.resultguesser.predict.PlayerStat
import io.resultguesser.predict.Res
import io.resultguesser.predict.Selection
import io.resultguesser.predict.SimMatch
import io.resultguesser.predict.Stat
import io.resultguesser.stats.GoalMethod

/*
 * Market families beyond plain goal counts: team statistics (corners, cards,
 * fouls, offsides, shots, throw-ins, goal kicks, tackles), minute windows,
 * goal order and timing, penalties and red cards, player markets, and live
 * "rest of the match" markets. All settle on a [SimMatch].
 */

/** A countable team statistic as named in market titles. */
internal enum class Metric(val stats: Set<Stat>) {
    CORNERS(setOf(Stat.CORNERS)),
    CARDS(setOf(Stat.YELLOW, Stat.RED)),
    FOULS(setOf(Stat.FOULS)),
    OFFSIDES(setOf(Stat.OFFSIDES)),
    SHOTS_ON_TARGET(setOf(Stat.SHOTS_ON_TARGET)),
    SHOTS(setOf(Stat.SHOTS)),
    THROW_INS(setOf(Stat.THROW_INS)),
    GOAL_KICKS(setOf(Stat.GOAL_KICKS)),
    TACKLES(setOf(Stat.TACKLES));

    fun count(m: SimMatch, team: Int?, p: Period): Int =
        if (this == CARDS) m.cards(team, p) else m.count(stats.single(), team, p)
}

private val METRIC_NOUNS = mapOf(
    "szogletszam" to Metric.CORNERS,
    "szogletek szama" to Metric.CORNERS,
    "buntetolap-szam" to Metric.CARDS,
    "buntetolapok szama" to Metric.CARDS,
    "szabalytalansagok szama" to Metric.FOULS,
    "lesszam" to Metric.OFFSIDES,
    "lesek szama" to Metric.OFFSIDES,
    "kaput eltalalo golszerzesi kiserletek szama" to Metric.SHOTS_ON_TARGET,
    "kapura tarto golszerzesi kiserletek szama" to Metric.SHOTS,
    "bedobasok szama" to Metric.THROW_INS,
    "kirugasok szama" to Metric.GOAL_KICKS,
    "szerelesek szama" to Metric.TACKLES,
)

/** Metric named anywhere in a phrase like "melyik csapat végez el több szögletet?". */
private fun metricIn(phrase: String): Metric? = when {
    phrase.contains("szoglet") -> Metric.CORNERS
    phrase.contains("buntetolap") || phrase.contains("lapot") -> Metric.CARDS
    phrase.contains("szabalytalansag") -> Metric.FOULS
    phrase.contains("kaput eltalalo") -> Metric.SHOTS_ON_TARGET
    phrase.contains("kapura tarto") -> Metric.SHOTS
    phrase.contains("bedobas") -> Metric.THROW_INS
    phrase.contains("kirugas") -> Metric.GOAL_KICKS
    phrase.contains("szereles") -> Metric.TACKLES
    Regex("""\bles(t|en|ek|re|t\?)?\b""").containsMatchIn(phrase) -> Metric.OFFSIDES
    else -> null
}

/** Player statistics named in player-market titles ("<player> kaput eltaláló …"). */
private val PLAYER_STAT_NOUNS = mapOf(
    "kaput eltalalo golszerzesi kiserletek szama" to PlayerStat.SHOTS_ON_TARGET,
    "kapura tarto golszerzesi kiserletek szama" to PlayerStat.SHOTS,
    "golszerzesi kiserletek szama" to PlayerStat.SHOTS,
    "elkovetett szabalytalansagok szama" to PlayerStat.FOULS,
    "szerelesek szama" to PlayerStat.TACKLES,
    "lesek szama" to PlayerStat.OFFSIDES,
    "golpasszainak szama" to PlayerStat.ASSISTS,
)
private val PLAYER_STAT_TITLE = Regex("""^(.+?) (${PLAYER_STAT_NOUNS.keys.sortedByDescending { it.length }.joinToString("|")})$""")

/** A minute window like "0:00-14:59" → [0, 15); "30:00-45+" → [30, 45) incl. 1st-half stoppage time. */
private val WINDOW = Regex("""(\d+):(\d{2})\s*-\s*(\d+)(?::(\d{2}))?(\+)?""")

private fun window(s: String): ClosedFloatingPointRange<Double>? {
    val m = WINDOW.find(s) ?: return null
    val from = m.groupValues[1].toInt() + m.groupValues[2].toInt() / 60.0
    // Simulated 1st-half minutes are < 45 and 2nd-half ones < 90, so "45+" / "90+" end exactly there.
    if (m.groupValues[5].isNotEmpty()) return from..m.groupValues[3].toDouble()
    val toSec = m.groupValues[4].ifEmpty { "0" }.toInt()
    val to = m.groupValues[3].toInt() + (toSec + 1) / 60.0
    return from..to
}

private operator fun ClosedFloatingPointRange<Double>.contains(minute: Double?) =
    minute != null && minute >= start && minute < endInclusive

private fun sim(g: Game) = g as SimMatch
private fun idx(t: String) = if (t == H) 0 else 1

// ---- entry points (called from Parser) ----

/** Live "rest of the match" markets, e.g. "Ázsiai hendikep, hátralévő rész (1:1)": settled on goals from now on. */
internal fun Parser.restOfMatch(body: String, label: String): Selection? {
    val inner = Regex("""^(.+?),? hatralevo resz \(\d+:\d+\)$""").matchEntire(body)?.groupValues?.get(1)
        ?: if (Regex("""^melyik csapat nyeri a hatralevo reszt\? \(\d+:\d+\)$""").matches(body)) "1x2" else return null
    val s = Parser(p, ctx, columnTeam).parse(inner, label) ?: return null
    if (!s.liveOk || s.usesHalves) return null
    val legs: List<Leg> = s.legs.map { leg -> { g: Game -> leg((g as? SimMatch)?.rest ?: g) } }
    return Selection(legs, usesHalves = false, liveOk = true, stats = s.stats)
}

internal fun Parser.extraMarket(body: String, label: String): Selection? {
    METRIC_NOUNS[body]?.let { m -> return uses(*m.stats.toTypedArray()).countSelection(label) { g -> m.count(sim(g), null, p) } }
    Regex("""^(.+) - azsiai hendikep$""").matchEntire(body)?.let { r ->
        METRIC_NOUNS[r.groupValues[1]]?.let { m -> return uses(*m.stats.toTypedArray()).metricAsian(label, m) }
    }
    if (body == "szoglet hendikep") return uses(Stat.CORNERS).metricEuropean(label, Metric.CORNERS)?.let(::sel)

    windowMarket(body, label)?.let { return it }
    goalTimeline(body, label)?.let { return it }
    cardsAndPenalties(body, label)?.let { return it }
    metricComparison(body, label)?.let { return it }
    return playerMarket(body, label)
}

/** Team-prefixed markets not about plain goals ("{h} szögletszám", "{a} piros lap", …). Label has the team prefix removed. */
internal fun Parser.extraTeamMarket(t: String, rest: String, label: String): Selection? {
    val team = idx(t)
    METRIC_NOUNS[rest]?.let { m -> return uses(*m.stats.toTypedArray()).countSelection(label) { g -> m.count(sim(g), team, p) } }
    if (rest == "piros lap") return uses(Stat.RED).yesNo(label)?.let { y -> sel { g -> (sim(g).count(Stat.RED, team, p) > 0) == y } }
    if (Regex("""^\d+-est vegezhet el$""").matches(rest)) {
        return uses(Stat.PENALTIES).yesNo(label)?.let { y -> sel { g -> (sim(g).count(Stat.PENALTIES, team, p) > 0) == y } }
    }
    Regex("""^golszam $NUM az adott idoszakaszban:$""").matchEntire(rest)?.let { r ->
        return timing().windowCount(label, num(r.groupValues[1])) { m, w -> m.goals.count { it.team == team && it.minute in w } }
    }
    Regex("""^szerez golt az adott idoszakaszban \(perc\): (.+)\?$""").matchEntire(rest)?.let { r ->
        val w = window(r.groupValues[1]) ?: return null
        timing().liveIfAfter(w.start)
        return yesNo(label)?.let { y -> sel { g -> sim(g).goals.any { it.team == team && it.minute in w } == y } }
    }
    Regex("""^mikor szerzi a\(z\) (\d+)\. goljat\?$""").matchEntire(rest)?.let { r ->
        val n = r.groupValues[1].toInt()
        timing()
        if (label.trim() == "nincs") return sel { g -> sim(g).goals.count { it.team == team } < n }
        val w = window(label) ?: return null
        return sel { g -> sim(g).goals.filter { it.team == team }.getOrNull(n - 1)?.minute in w }
    }
    Regex("""^szerez (\d+) egymast koveto golt$""").matchEntire(rest)?.let { r ->
        val n = r.groupValues[1].toInt()
        return timing().yesNo(label)?.let { y -> sel { g -> consecutive(sim(g), team, n) == y } }
    }
    Regex("""^szogletszam $NUM az adott idoszakaszban: (.+)$""").matchEntire(rest)?.let { r ->
        val w = window(r.groupValues[2]) ?: return null
        return uses(Stat.CORNERS).countSelection(label) { g -> sim(g).corners.count { it.team == team && it.minute in w } }
    }
    if (rest == "ki szerzi az elso golt?" || rest == "ki szerzi a(z) 1. golt?") {
        timing()
        if (label.trim() == "nincs") return sel { g -> sim(g).goals.none { it.team == team } }
        val ref = playerRef(label, team, PlayerStat.GOALS) ?: return null
        return sel { g -> sim(g).goals.firstOrNull { it.team == team }?.let { it.scorer == ref.index } == true }
    }
    return null
}

// ---- team statistics ----

private fun Parser.metricAsian(label: String, m: Metric): Selection? {
    val r = Regex("""^(\{[ha]\}) $SIGNED$""").matchEntire(label.trim()) ?: return null
    val team = idx(r.groupValues[1])
    val hcp = num(r.groupValues[2])
    return legs(
        quarterSplit(hcp).map { h ->
            { g: Game ->
                val s = sim(g)
                val d = m.count(s, team, p) - m.count(s, 1 - team, p) + h
                when {
                    d > 0 -> Res.WIN
                    d < 0 -> Res.LOSE
                    else -> Res.VOID
                }
            }
        },
    )
}

private fun Parser.metricEuropean(label: String, m: Metric): Pred? {
    val s = label.trim()
    Regex("""^dontetlen - \((\{[ha]\}) ([+-]?\d+)\).*$""").matchEntire(s)?.let { r ->
        val team = idx(r.groupValues[1])
        val h = r.groupValues[2].toInt()
        return { g -> val x = sim(g); m.count(x, team, p) - m.count(x, 1 - team, p) + h == 0 }
    }
    Regex("""^(\{[ha]\}) \(([+-]?\d+)\).*$""").matchEntire(s)?.let { r ->
        val team = idx(r.groupValues[1])
        val h = r.groupValues[2].toInt()
        return { g -> val x = sim(g); m.count(x, team, p) - m.count(x, 1 - team, p) + h > 0 }
    }
    return null
}

/** "{h}" / "dontetlen" / "{a}" compared on two counts. */
private fun outcome(label: String): ((Int, Int) -> Boolean)? = when (label.trim()) {
    H, "hazai", "1" -> { h, a -> h > a }
    A, "vendeg", "2" -> { h, a -> a > h }
    "dontetlen", "x" -> { h, a -> h == a }
    else -> null
}

private fun doubleOutcome(label: String): ((Int, Int) -> Boolean)? {
    val parts = label.trim().split(" vagy ")
    if (parts.size != 2) return null
    val a = outcome(parts[0]) ?: return null
    val b = outcome(parts[1]) ?: return null
    return { h, w -> a(h, w) || b(h, w) }
}

private fun Parser.metricComparison(body: String, label: String): Selection? {
    val m = metricIn(body) ?: return null
    // "Melyik félidőben lesz több szöglet?"
    if (body.startsWith("melyik felidoben lesz tobb")) {
        uses(*m.stats.toTypedArray()).halves()
        return when (label.trim()) {
            "1. felido" -> sel { g -> m.count(sim(g), null, Period.FIRST) > m.count(sim(g), null, Period.SECOND) }
            "2. felido" -> sel { g -> m.count(sim(g), null, Period.SECOND) > m.count(sim(g), null, Period.FIRST) }
            "dontetlen" -> sel { g -> m.count(sim(g), null, Period.FIRST) == m.count(sim(g), null, Period.SECOND) }
            else -> null
        }
    }
    if (!body.startsWith("melyik csapat")) return null
    uses(*m.stats.toTypedArray())

    // Race: "Melyik csapat végez el előbb 5 szögletet?"
    Regex("""elobb (\d+)""").find(body)?.let { r ->
        if (m != Metric.CORNERS) return null
        val n = r.groupValues[1].toInt()
        return raceLabel(label) { s -> raceWinner(s.corners.map { it.team }, n) }
    }
    // "Melyik csapat végzi el az X. szögletet?" — label "szoglet 3: {h}"
    if (body.contains("az x. szogletet") || body.contains("az x szogletet")) {
        val r = Regex("""^szoglet (\d+): (\{[ha]\})$""").matchEntire(label.trim()) ?: return null
        val n = r.groupValues[1].toInt()
        val team = idx(r.groupValues[2])
        return sel { g -> sim(g).corners.getOrNull(n - 1)?.team == team }
    }
    if (body.contains("utolso szogletet")) {
        val team = team(label)?.let(::idx) ?: return null
        return sel { g -> sim(g).corners.lastOrNull()?.team == team }
    }
    // Plain comparison: "Melyik csapat végez el több szögletet?"
    if (body.contains("tobb")) {
        val o = outcome(label) ?: return null
        return sel { g -> val s = sim(g); o(m.count(s, 0, p), m.count(s, 1, p)) }
    }
    return null
}

// ---- minute windows ----

private fun Parser.windowMarket(body: String, label: String): Selection? {
    if (!body.contains("az adott idoszak") && !body.contains("az adott idopont")) return null

    Regex("""^golszam $NUM az adott idoszakaszban \(perc\):$""").matchEntire(body)?.let { r ->
        return timing().windowCount(label, num(r.groupValues[1])) { m, w -> m.goals.count { it.minute in w } }
    }
    if (body == "lesz gol az adott idoszakaszban (perc):?") {
        val r = Regex("""^(igen|nem) (.+)$""").matchEntire(label.trim()) ?: return null
        val w = window(r.groupValues[2]) ?: return null
        val y = r.groupValues[1] == "igen"
        timing().liveIfAfter(w.start)
        return sel { g -> sim(g).goals.any { it.minute in w } == y }
    }
    Regex("""^lesz gol az adott idopont (elott|utan) \(perc\): (\d+):(\d{2})\?$""").matchEntire(body)?.let { r ->
        val minute = r.groupValues[2].toInt() + r.groupValues[3].toInt() / 60.0
        val before = r.groupValues[1] == "elott"
        timing()
        if (!before) liveIfAfter(minute)
        return yesNo(label)?.let { y -> sel { g -> sim(g).goals.any { if (before) it.minute < minute else it.minute >= minute } == y } }
    }
    Regex("""^az adott idoszakasz eredmenye \(perc\): (.+)$""").matchEntire(body)?.let { r ->
        val w = window(r.groupValues[1]) ?: return null
        val o = outcome(label) ?: return null
        timing()
        return sel { g -> val s = sim(g); o(s.goals.count { it.team == 0 && it.minute in w }, s.goals.count { it.team == 1 && it.minute in w }) }
    }
    Regex("""^ketesely az adott idoszakaszban: (.+)$""").matchEntire(body)?.let { r ->
        val w = window(r.groupValues[1]) ?: return null
        val o = doubleOutcome(label) ?: return null
        timing()
        return sel { g -> val s = sim(g); o(s.goals.count { it.team == 0 && it.minute in w }, s.goals.count { it.team == 1 && it.minute in w }) }
    }
    Regex("""^mindket csapat szerez golt az adott idoszakaszban: (.+)$""").matchEntire(body)?.let { r ->
        val w = window(r.groupValues[1]) ?: return null
        timing()
        return yesNo(label)?.let { y -> sel { g -> val s = sim(g); (s.goals.any { it.team == 0 && it.minute in w } && s.goals.any { it.team == 1 && it.minute in w }) == y } }
    }
    Regex("""^szogletszam $NUM az adott idoszakaszban: (.+)$""").matchEntire(body)?.let { r ->
        val w = window(r.groupValues[2]) ?: return null
        return uses(Stat.CORNERS).countSelection(label) { g -> sim(g).corners.count { it.minute in w } }
    }
    Regex("""^a\(z\) (\d+)\. szoglet az adott idoszakaszban lesz: (.+)\?$""").matchEntire(body)?.let { r ->
        val n = r.groupValues[1].toInt()
        val w = window(r.groupValues[2]) ?: return null
        uses(Stat.CORNERS)
        if (label.trim() == "nincs") return sel { g -> sim(g).corners.getOrNull(n - 1)?.minute !in w }
        val team = team(label)?.let(::idx) ?: return null
        return sel { g -> sim(g).corners.getOrNull(n - 1)?.let { it.team == team && it.minute in w } == true }
    }
    Regex("""^melyik csapat vegez el tobb szogletet az adott idoszakaszban: (.+)$""").matchEntire(body)?.let { r ->
        val w = window(r.groupValues[1]) ?: return null
        val o = outcome(label) ?: return null
        uses(Stat.CORNERS)
        return sel { g -> val s = sim(g); o(s.corners.count { it.team == 0 && it.minute in w }, s.corners.count { it.team == 1 && it.minute in w }) }
    }
    return null
}

/** Over/under a [line] where the label carries the window: "Több, mint 0:00-14:59". */
private fun Parser.windowCount(label: String, line: Double, count: (SimMatch, ClosedFloatingPointRange<Double>) -> Int): Selection? {
    val r = Regex("""^(tobb|kevesebb), mint (.+)$""").matchEntire(label.trim()) ?: return null
    val w = window(r.groupValues[2]) ?: return null
    val over = r.groupValues[1] == "tobb"
    liveIfAfter(w.start)
    return sel { g -> val c = count(sim(g), w); if (over) c > line else c < line }
}

// ---- goal order and timing ----

/** Team scoring the [n]th goal ("{h}" / "{a}" / "nincs"). Used by combos too ("gol 1: {h}"). */
internal fun Parser.nthGoalTeam(label: String, n: Int, per: Period = Period.FULL): Pred? {
    timing()
    ctx.liveGoals?.let { if (per == Period.FULL && n > it) liveOk = true }
    val s = label.trim().removePrefix("-").trim()
    if (s == "nincs" || s == "nem" || s.startsWith("nem lesz")) return { g -> sim(g).goals.count { SimMatch.inPeriod(it.minute, per) } < n }
    val team = team(s)?.let(::idx) ?: return null
    return { g -> sim(g).goals.filter { SimMatch.inPeriod(it.minute, per) }.getOrNull(n - 1)?.team == team }
}

private fun Parser.goalTimeline(body: String, label: String): Selection? {
    Regex("""^melyik csapat szerzi a\(z\) (\d+)\. golt\?$""").matchEntire(body)?.let { r ->
        return nthGoalTeam(label, r.groupValues[1].toInt(), p)?.let(::sel)
    }
    if (body == "melyik csapat szerzi az utolso golt?") {
        timing()
        if (ctx.liveGoals == 0) liveOk = true
        if (label.trim() == "nincs") return sel { g -> sim(g).goals.isEmpty() }
        val team = team(label)?.let(::idx) ?: return null
        return sel { g -> sim(g).goals.lastOrNull()?.team == team }
    }
    Regex("""^ki er el eloszor (\d+) golt\?$""").matchEntire(body)?.let { r ->
        val n = r.groupValues[1].toInt()
        timing()
        return raceLabel(label) { s -> raceWinner(s.goals.map { it.team }, n) }
    }
    Regex("""^a\(z\) (\d+)\. gol megszerzesenek ideje$""").matchEntire(body)?.let { r ->
        val n = r.groupValues[1].toInt()
        timing()
        if (label.trim() == "nincs") return sel { g -> sim(g).goals.size < n }
        val w = window(label) ?: return null
        return sel { g -> sim(g).goals.getOrNull(n - 1)?.minute in w }
    }
    Regex("""^(\d+)\. gol x perc elott lesz\?$""").matchEntire(body)?.let { r ->
        val n = r.groupValues[1].toInt()
        val l = Regex("""^(igen|nem) (\d+)$""").matchEntire(label.trim()) ?: return null
        val minute = l.groupValues[2].toDouble()
        val y = l.groupValues[1] == "igen"
        timing()
        return sel { g -> ((sim(g).goals.getOrNull(n - 1)?.minute ?: 999.0) < minute) == y }
    }
    Regex("""^(\d+)\. gol megszerzesenek modja$""").matchEntire(body)?.let { r ->
        val n = r.groupValues[1].toInt()
        timing()
        val method = when (label.trim()) {
            "loves" -> GoalMethod.SHOT
            "fejes" -> GoalMethod.HEADER
            "bunteto", "buntetobol" -> GoalMethod.PENALTY
            "ongol" -> GoalMethod.OWN_GOAL
            "szabadrugas" -> GoalMethod.FREE_KICK
            "nem lesz gol", "nincs" -> return sel { g -> sim(g).goals.size < n }
            else -> return null
        }
        return sel { g -> sim(g).goals.getOrNull(n - 1)?.method == method }
    }
    if (body == "lesz ongol?") return timing().yesNo(label)?.let { y -> sel { g -> sim(g).goals.any { it.method == GoalMethod.OWN_GOAL } == y } }
    if (body == "hatranybol nyer") {
        val team = team(label)?.let(::idx) ?: return null
        timing()
        return sel { g -> comeback(sim(g), team) }
    }
    if (body.startsWith("merkozes alakulasa")) {
        timing()
        val parts = label.split("/").map { it.trim() }
        if (parts.size != 2) return null
        val first = nthGoalTeam(parts[0], 1) ?: return null
        val final = result(parts[1]) ?: return null
        return sel { g -> first(g) && final(g) }
    }
    return null
}

private fun Parser.raceLabel(label: String, winner: (SimMatch) -> Int?): Selection? {
    val s = label.trim()
    if (s == "egyik sem" || s == "nincs") return sel { g -> winner(sim(g)) == null }
    val team = team(s)?.let(::idx) ?: return null
    return sel { g -> winner(sim(g)) == team }
}

/** Team (0/1) that is first to [n] events in the ordered sequence, or null if nobody gets there. */
private fun raceWinner(teams: List<Int>, n: Int): Int? {
    val c = IntArray(2)
    for (t in teams) if (++c[t] == n) return t
    return null
}

private fun consecutive(s: SimMatch, team: Int, n: Int): Boolean {
    var run = 0
    for (goal in s.goals) {
        run = if (goal.team == team) run + 1 else 0
        if (run >= n) return true
    }
    return false
}

private fun comeback(s: SimMatch, team: Int): Boolean {
    var own = 0
    var opp = 0
    var trailed = false
    for (goal in s.goals) {
        if (goal.team == team) own++ else opp++
        if (opp > own) trailed = true
    }
    return trailed && own > opp
}

// ---- cards, red cards and penalties ----

private fun Parser.cardsAndPenalties(body: String, label: String): Selection? {
    when (body) {
        "mindket csapat kap buntetolapot" ->
            return uses(Stat.YELLOW, Stat.RED).yesNo(label)?.let { y -> sel { g -> (sim(g).cards(0, p) > 0 && sim(g).cards(1, p) > 0) == y } }
        "mindket csapat kap piros lapot" ->
            return uses(Stat.RED).yesNo(label)?.let { y -> sel { g -> (sim(g).count(Stat.RED, 0, p) > 0 && sim(g).count(Stat.RED, 1, p) > 0) == y } }
        "lesz kiallitas?" -> return uses(Stat.RED).yesNo(label)?.let { y -> sel { g -> (sim(g).count(Stat.RED, null, p) > 0) == y } }
        "lesz kiallitas + lesz 11-es" -> return uses(Stat.RED, Stat.PENALTIES).yesNo(label)?.let { y ->
            sel { g -> (sim(g).count(Stat.RED, null, p) > 0 && sim(g).count(Stat.PENALTIES, null, p) > 0) == y }
        }
        "lesz 11-es?" -> return uses(Stat.PENALTIES).yesNo(label)?.let { y -> sel { g -> (sim(g).count(Stat.PENALTIES, null, p) > 0) == y } }
        "melyik csapat jatekosa kapja az elso buntetolapot?", "melyik csapat kapja az elso buntetolapot?" -> {
            uses(Stat.YELLOW, Stat.RED)
            if (label.trim() == "nincs") return sel { g -> sim(g).cards.none { SimMatch.inPeriod(it.minute, p) } }
            val team = team(label)?.let(::idx) ?: return null
            return sel { g -> sim(g).cards.firstOrNull { SimMatch.inPeriod(it.minute, p) }?.team == team }
        }
    }
    Regex("""^mindket csapat legalabb (\d+) buntetolapot kap\?$""").matchEntire(body)?.let { r ->
        val n = r.groupValues[1].toInt()
        return uses(Stat.YELLOW, Stat.RED).yesNo(label)?.let { y -> sel { g -> (sim(g).cards(0, p) >= n && sim(g).cards(1, p) >= n) == y } }
    }
    return null
}

// ---- players ----

/** Resolves a player named in a label; the column header (or [teamHint]) tells which team to look in. */
private fun Parser.playerRef(name: String, teamHint: Int?, stat: PlayerStat): PlayerRef? {
    val dir = ctx.players ?: return null
    val ref = dir.find(name, teamHint ?: columnTeam) ?: return null
    players += ref
    playerStats += stat
    liveOk = false
    return ref
}

private fun Parser.playerMarket(body: String, label: String): Selection? {
    PLAYER_STAT_TITLE.matchEntire(body)?.let { r ->
        val stat = PLAYER_STAT_NOUNS.getValue(r.groupValues[2])
        val ref = playerRef(r.groupValues[1], null, stat) ?: return null
        val line = if (label.contains(": ")) label.substringAfter(": ") else label
        return countSelection(line) { g -> sim(g).playerCount(ref, stat) }
    }
    val name = label.trim()
    val nobody = name == "nincs" || name.startsWith("nem lesz")
    when (body) {
        "szerez golt?" -> {
            if (nobody) return timing().sel { g -> sim(g).goals.none { it.scorer >= 0 && SimMatch.inPeriod(it.minute, p) } }
            val ref = playerRef(name, null, PlayerStat.GOALS) ?: return null
            return sel { g -> sim(g).playerCount(ref, PlayerStat.GOALS, p) > 0 }
        }
        "ki szerzi a(z) 1. golt?", "ki szerzi az elso golt?", "ki szerzi az utolso golt?" -> {
            val last = body.contains("utolso")
            if (nobody) return timing().sel { g -> sim(g).goals.none { SimMatch.inPeriod(it.minute, p) } }
            val ref = playerRef(name, null, PlayerStat.GOALS) ?: return null
            return sel { g ->
                val inPeriod = sim(g).goals.filter { SimMatch.inPeriod(it.minute, p) }
                val goal = if (last) inPeriod.lastOrNull() else inPeriod.firstOrNull()
                goal != null && goal.team == ref.team && goal.scorer == ref.index
            }
        }
        "golpasszt ad" -> {
            val ref = playerRef(name, null, PlayerStat.ASSISTS) ?: return null
            return sel { g -> sim(g).playerCount(ref, PlayerStat.ASSISTS, p) > 0 }
        }
        "szerez golt vagy golpasszt ad" -> {
            val ref = playerRef(name, null, PlayerStat.GOALS) ?: return null
            playerStats += PlayerStat.ASSISTS
            return sel { g -> sim(g).playerCount(ref, PlayerStat.GOALS, p) + sim(g).playerCount(ref, PlayerStat.ASSISTS, p) > 0 }
        }
        "mindket felidoben szerez golt?" -> {
            val ref = playerRef(name, null, PlayerStat.GOALS) ?: return null
            halves()
            return sel { g -> sim(g).playerCount(ref, PlayerStat.GOALS, Period.FIRST) > 0 && sim(g).playerCount(ref, PlayerStat.GOALS, Period.SECOND) > 0 }
        }
        "tobb golt szerez, mint az ellenfel csapata osszesen?" -> {
            val ref = playerRef(name, null, PlayerStat.GOALS) ?: return null
            return sel { g -> sim(g).playerCount(ref, PlayerStat.GOALS, p) > (if (ref.team == 0) g.away(p) else g.home(p)) }
        }
        "buntetolapot kap?" -> {
            val ref = playerRef(name, null, PlayerStat.YELLOW) ?: return null
            uses(Stat.YELLOW, Stat.RED)
            return sel { g -> sim(g).playerCount(ref, PlayerStat.YELLOW, p) + sim(g).playerCount(ref, PlayerStat.RED, p) > 0 }
        }
        "piros lapot kap?" -> {
            val ref = playerRef(name, null, PlayerStat.RED) ?: return null
            uses(Stat.RED)
            return sel { g -> sim(g).playerCount(ref, PlayerStat.RED, p) > 0 }
        }
        "ki kapja az elso buntetolapot?" -> {
            uses(Stat.YELLOW, Stat.RED)
            if (name == "nincs") return sel { g -> sim(g).cards.isEmpty() }
            val ref = playerRef(name, null, PlayerStat.YELLOW) ?: return null
            return sel { g -> sim(g).cards.firstOrNull()?.let { it.team == ref.team && it.player == ref.index } == true }
        }
        "golt szerez + 1x2" -> {
            val cut = name.lastIndexOf(" es ")
            if (cut < 0) return null
            val final = result(name.substring(cut + 4)) ?: return null
            val ref = playerRef(name.substring(0, cut), null, PlayerStat.GOALS) ?: return null
            return sel { g -> sim(g).playerCount(ref, PlayerStat.GOALS) > 0 && final(g) }
        }
    }
    Regex("""^(\d+) vagy tobb golt szerez$""").matchEntire(body)?.let { r ->
        val n = r.groupValues[1].toInt()
        val who = Regex("""^(.+) \d+ vagy tobb$""").matchEntire(name)?.groupValues?.get(1) ?: name
        val ref = playerRef(who, null, PlayerStat.GOALS) ?: return null
        return sel { g -> sim(g).playerCount(ref, PlayerStat.GOALS, p) >= n }
    }
    return null
}

// ---- live helpers ----

/** Goal-timing markets depend on when earlier goals fell — unknown in play — unless noted by [liveIfAfter]. */
private fun Parser.timing(): Parser = also { liveOk = false }

/** A window starting after the current minute is fully in the future, so it can be judged live. */
private fun Parser.liveIfAfter(minute: Double) {
    val now = ctx.liveMinute ?: return
    if (minute >= now && stats == mutableSetOf(Stat.GOALS) && players.isEmpty()) liveOk = true
}
