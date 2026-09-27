package io.resultguesser.i18n

import io.resultguesser.predict.Game
import io.resultguesser.predict.Leg
import io.resultguesser.predict.Period
import io.resultguesser.predict.PlayerDirectory
import io.resultguesser.predict.PlayerRef
import io.resultguesser.predict.PlayerStat
import io.resultguesser.predict.Res
import io.resultguesser.predict.Selection
import io.resultguesser.predict.Stat
import java.text.Normalizer
import kotlin.math.roundToInt

internal typealias Pred = (Game) -> Boolean
internal typealias Count = (Game) -> Int

/**
 * Understands the Hungarian betting vocabulary used by TippmixPRO and turns a
 * scraped market title + outcome label into a [Selection] the simulation can
 * settle.
 *
 * It is a whitelist of market *families*: goals (result, totals, team totals,
 * BTTS, handicaps, half-time/full-time, exact scores, the "+" combos, …) are
 * handled here; corners, cards, fouls, shots, offsides, penalties, goal timing
 * and player markets in [extraMarket]. Anything unknown parses to `null` and
 * is reported rather than guessed.
 *
 * Titles look like "<body> - <period>" where period is "Rendes játékidő",
 * "1. félidő" or "2. félidő". Team names appear verbatim in titles and labels
 * (e.g. "Anglia gólszám", "Anglia vagy Döntetlen"); they're replaced by the
 * tokens `{h}` / `{a}` before matching, and diacritics are stripped.
 */
object Glossary {

    /**
     * @param column the column header the option sits under (for player markets: the player's team).
     * @param context players and live state; without players, player markets don't parse.
     */
    fun parse(
        marketTitle: String,
        outcomeLabel: String,
        homeTeam: String,
        awayTeam: String,
        column: String? = null,
        context: ParseContext = ParseContext(),
    ): Selection? {
        val teams = listOf(norm(homeTeam) to H, norm(awayTeam) to A).sortedByDescending { it.first.length }
        fun tokenize(s: String) = teams.fold(norm(s)) { acc, (name, token) -> if (name.isEmpty()) acc else acc.replace(name, token) }

        val title = tokenize(marketTitle)
        val m = PERIOD_SUFFIX.find(title)
        // "1X2 - Szuper odds" is the plain market with boosted odds.
        val body = (m?.groupValues?.get(1)?.trim() ?: title).removeSuffix(" - szuper odds")
        val period = when (m?.groupValues?.get(2)) {
            "1. felido" -> Period.FIRST
            "2. felido" -> Period.SECOND
            else -> Period.FULL
        }
        val columnTeam = column?.let { tokenize(it) }?.let { if (it == H) 0 else if (it == A) 1 else null }
        return runCatching { Parser(period, context, columnTeam).parse(body, tokenize(outcomeLabel)) }.getOrNull()
    }
}

/** What a parse may consult besides the text: the player pool and, for live matches, the current state. */
class ParseContext(
    val players: PlayerDirectory? = null,
    /** Goals scored so far (live only). */
    val liveGoals: Int? = null,
    /** Current minute (live only). */
    val liveMinute: Double? = null,
)

internal const val H = "{h}"
internal const val A = "{a}"
private val PERIOD_SUFFIX = Regex("""^(.*?)\s*-\s*(rendes jatekido|1\. felido|2\. felido)$""")
internal const val NUM = """(\d+(?:[.,]\d+)?)"""
internal const val SIGNED = """([+-]?\d+(?:[.,]\d+)?)"""

/**
 * One parse. Tracks what the resulting selection depends on: the half-time
 * split, whether it can be judged live, and which statistics/players it uses.
 */
internal class Parser(val p: Period, val ctx: ParseContext, val columnTeam: Int?) {
        var usesHalves = p != Period.FULL
        var liveOk = true
        val stats = mutableSetOf(Stat.GOALS)
        val players = mutableSetOf<PlayerRef>()
        val playerStats = mutableSetOf<PlayerStat>()

        fun halves(): Parser = also { usesHalves = true }

        /** Marks the selection as relying on non-goal [used] statistics (unknown so far in a live match). */
        fun uses(vararg used: Stat): Parser = also { stats += used; liveOk = false }

        fun settle(leg: Leg) = Selection(listOf(leg), usesHalves, liveOk, stats.toSet(), players.toSet(), playerStats.toSet())
        fun sel(pred: Pred) = settle { g -> Res.of(pred(g)) }
        fun legs(legs: List<Leg>) = Selection(legs, usesHalves, liveOk, stats.toSet(), players.toSet(), playerStats.toSet())

        fun parse(body: String, label: String): Selection? {
            restOfMatch(body, label)?.let { return it }
            // Per-team markets: "{h} gólszám", "{a} szerez gólt?", … Labels may carry a "{h}: " prefix.
            teamMarket(body)?.let { (team, rest) -> return parseTeamMarket(team, rest, stripTeamPrefix(label, team)) }

            return when (body) {
                "1x2" -> result(label)?.let(::sel)
                "ketesely" -> doubleChance(label)?.let(::sel)
                "dontetlennel a tet visszajar" -> team(label)?.let { t -> settle { g -> if (draw(g)) Res.VOID else Res.of(wins(t, g)) } }
                "hazai csapat gyozelmenel a tet visszajar" -> result(label)?.let { r -> settle { g -> if (wins(H, g)) Res.VOID else Res.of(r(g)) } }
                "vendegcsapat gyozelmenel a tet visszajar" -> result(label)?.let { r -> settle { g -> if (wins(A, g)) Res.VOID else Res.of(r(g)) } }
                "golszam" -> countSelection(label) { it.total(p) }
                "mindket csapat szerez golt" -> yesNo(label)?.let { y -> sel { g -> btts(g) == y } }
                "pontos eredmeny" -> exactScore(label)?.let(::sel)
                "felido/vegeredmeny" -> htft(label)?.let(::sel)
                "azsiai hendikep" -> asianHandicap(label)
                "hendikep" -> europeanHandicap(label)?.let(::sel)
                "nyertes kulonbseg" -> winningMargin(label)?.let(::sel)
                "hany csapat szerez golt?" -> label.toIntOrNull()?.let { n -> sel { g -> scorers(g) == n } }
                "melyik csapat szerez golt?" -> whichTeamScores(label)?.let(::sel)
                "mindket csapat szerez legalabb 2 golt?" -> yesNo(label)?.let { y -> sel { g -> (home(g) >= 2 && away(g) >= 2) == y } }
                "mindket csapat szerez golt vagy tobb, mint 2,5 gol lesz" ->
                    yesNo(label)?.let { y -> sel { g -> (btts(g) || g.total(p) > 2.5) == y } }
                "felido eredmenye vagy vegeredmeny" -> halves().resultIn(label, Period.FIRST)?.let { r1 ->
                    resultIn(label, Period.FULL)?.let { rf -> sel { g -> r1(g) || rf(g) } }
                }
                "az eredmeny a felsoroltak kozott talalhato" -> scoreList(label)?.let(::sel)

                // Half-split markets (always full match, but need both halves).
                "mindket felidoben lesz gol" -> halves().yesNo(label)?.let { y -> sel { g -> (g.total(Period.FIRST) >= 1 && g.total(Period.SECOND) >= 1) == y } }
                "melyik felidoben lesz tobb gol?" -> halves().moreGoalsHalf(label) { g, per -> g.total(per) }?.let(::sel)
                "megnyeri mindket felidot?" -> halves().team(label)?.let { t -> sel { g -> winsBothHalves(t, g) } }
                "nyer legalabb egy felidot?" -> halves().team(label)?.let { t -> sel { g -> winsAHalf(t, g) } }
                "mindket felidoben szerez golt" -> halves().team(label)?.let { t -> sel { g -> scoresBothHalves(t, g) } }
                "megnyeri mindket felidot es 0-ra nyer" -> halves().team(label)?.let { t -> sel { g -> winsBothHalves(t, g) && conceded(t, g) == 0 } }
                "0-ra nyeri" -> team(label)?.let { t -> sel { g -> wins(t, g) && conceded(t, g) == 0 } }
                "nem kap golt" -> team(label)?.let { t -> sel { g -> conceded(t, g) == 0 } }
                "golszam 1. felido/golszam 2. felido" -> halves().halfGoalRanges(label)?.let(::sel)
                "felido golszam kombinacio" -> halves().halfTotalsCombo(label)?.let(::sel)
                "pontos eredmeny - 1. felido/vegeredmeny" -> halves().htftExact(label)?.let(::sel)
                "1. felido/2. felido - mindket csapat szerez golt" -> halves().bttsPerHalf(label)?.let(::sel)

                else -> extraMarket(body, label) ?: combo(body, label)
            }
        }

        // ---- per-team markets ----

        fun teamMarket(body: String): Pair<String, String>? {
            for (t in listOf(H, A)) if (body.startsWith("$t ")) return t to body.removePrefix("$t ").trim()
            return null
        }

        fun parseTeamMarket(t: String, rest: String, label: String): Selection? = when (rest) {
            "golszam" -> countSelection(label) { g -> goals(t, g) }
            "szerez golt?" -> yesNo(label)?.let { y -> sel { g -> (goals(t, g) >= 1) == y } }
            "0-ra nyeri" -> yesNo(label)?.let { y -> sel { g -> (wins(t, g) && conceded(t, g) == 0) == y } }
            "kapott gol nelkul jatssza le" -> yesNo(label)?.let { y -> sel { g -> (conceded(t, g) == 0) == y } }
            "nyeri mindket felidot" -> halves().yesNo(label)?.let { y -> sel { g -> winsBothHalves(t, g) == y } }
            "nyer legalabb egy felidot" -> halves().yesNo(label)?.let { y -> sel { g -> winsAHalf(t, g) == y } }
            "mindket felidoben szerez golt?" -> halves().yesNo(label)?.let { y -> sel { g -> scoresBothHalves(t, g) == y } }
            "melyik felidoben szerez tobb golt?" -> halves().moreGoalsHalf(label) { g, per -> goalsIn(t, g, per) }?.let(::sel)
            else -> extraTeamMarket(t, rest, label)
        }

        fun stripTeamPrefix(label: String, t: String) = label.removePrefix("$t:").removePrefix(t).trim()

        // ---- "A + B" combos (AND) and "A vagy B" (OR) ----

        /** Kinds of components that "+"-combined markets are made of. */
        enum class Part { RESULT, DOUBLE_CHANCE, TOTAL, BTTS, HTFT, WIN_BOTH_HALVES, MORE_GOALS_HALF, TEAM_TOTAL, FIRST_GOAL }

        fun part(descriptor: String): Part? = when {
            descriptor == "1x2" || descriptor == "jatekresz eredmenye" -> Part.RESULT
            descriptor == "ketesely" -> Part.DOUBLE_CHANCE
            descriptor == "golszam" || Regex("""^golszam $NUM$""").matches(descriptor) -> Part.TOTAL
            descriptor == "mindket csapat szerez golt" -> Part.BTTS
            descriptor == "felido/vegeredmeny" -> Part.HTFT
            descriptor == "megnyeri mindket felidot" -> Part.WIN_BOTH_HALVES
            descriptor == "melyik felidoben lesz tobb gol?" -> Part.MORE_GOALS_HALF
            descriptor == "csapat goljainak szama" -> Part.TEAM_TOTAL
            descriptor == "melyik csapat szerzi a(z) 1. golt" -> Part.FIRST_GOAL
            else -> null
        }

        fun component(kind: Part, s: String): Pred? = when (kind) {
            Part.RESULT -> result(s)
            Part.DOUBLE_CHANCE -> doubleChance(s)
            Part.TOTAL -> countPred(s) { it.total(p) }
            Part.BTTS -> yesNo(s)?.let { y -> { g: Game -> btts(g) == y } }
            Part.HTFT -> halves().htft(s)
            Part.WIN_BOTH_HALVES -> halves().team(s)?.let { t -> { g: Game -> winsBothHalves(t, g) } }
            Part.MORE_GOALS_HALF -> halves().moreGoalsHalf(s) { g, per -> g.total(per) }
            Part.TEAM_TOTAL -> teamTotal(s)
            Part.FIRST_GOAL -> nthGoalTeam(s.removePrefix("gol 1:").trim(), 1)
        }

        fun combo(body: String, label: String): Selection? {
            val and = body.split(" + ")
            if (and.size > 1) {
                val kinds = and.map { part(it.trim()) ?: return null }
                val parts = label.split(" es ").map { it.trim() }
                if (parts.size != kinds.size) return null
                val preds = kinds.zip(parts).map { (k, s) -> component(k, s) ?: return null }
                return sel { g -> preds.all { it(g) } }
            }
            val or = body.split(" vagy ")
            if (or.size == 2) {
                val kinds = or.map { part(it.trim()) ?: return null }
                val parts = label.split(" vagy ").map { it.trim() }
                if (parts.size != 2) return null
                val preds = kinds.zip(parts).map { (k, s) -> component(k, s) ?: return null }
                return sel { g -> preds.any { it(g) } }
            }
            return null
        }

        // ---- building blocks ----

        fun home(g: Game) = g.home(p)
        fun away(g: Game) = g.away(p)
        fun goals(t: String, g: Game) = goalsIn(t, g, p)
        fun goalsIn(t: String, g: Game, per: Period) = if (t == H) g.home(per) else g.away(per)
        fun conceded(t: String, g: Game) = if (t == H) away(g) else home(g)
        fun wins(t: String, g: Game) = goals(t, g) > conceded(t, g)
        fun draw(g: Game) = home(g) == away(g)
        fun btts(g: Game) = home(g) >= 1 && away(g) >= 1
        fun scorers(g: Game) = (if (home(g) > 0) 1 else 0) + (if (away(g) > 0) 1 else 0)
        fun other(t: String) = if (t == H) A else H

        fun winsHalf(t: String, g: Game, per: Period) = goalsIn(t, g, per) > goalsIn(other(t), g, per)
        fun winsBothHalves(t: String, g: Game) = winsHalf(t, g, Period.FIRST) && winsHalf(t, g, Period.SECOND)
        fun winsAHalf(t: String, g: Game) = winsHalf(t, g, Period.FIRST) || winsHalf(t, g, Period.SECOND)
        fun scoresBothHalves(t: String, g: Game) = goalsIn(t, g, Period.FIRST) >= 1 && goalsIn(t, g, Period.SECOND) >= 1

        fun team(s: String): String? = when (s.trim()) {
            H, "hazai", "1" -> H
            A, "vendeg", "2" -> A
            else -> null
        }

        fun yesNo(s: String): Boolean? = when (s.trim()) {
            "igen" -> true
            "nem" -> false
            else -> null
        }

        /** 1X2 outcome in the market's own period. */
        fun result(s: String): Pred? = resultIn(s, p)

        fun resultIn(s: String, per: Period): Pred? = when (s.trim()) {
            H, "hazai", "1" -> { g -> g.home(per) > g.away(per) }
            A, "vendeg", "2" -> { g -> g.away(per) > g.home(per) }
            "dontetlen", "x" -> { g -> g.home(per) == g.away(per) }
            else -> null
        }

        fun doubleChance(s: String): Pred? {
            val t = s.trim()
            when (t) {
                "1x" -> return resultIn("1", p).or(resultIn("x", p))
                "x2" -> return resultIn("x", p).or(resultIn("2", p))
                "12" -> return resultIn("1", p).or(resultIn("2", p))
            }
            val parts = t.split(" vagy ")
            if (parts.size != 2) return null
            return result(parts[0]).or(result(parts[1]))
        }

        fun Pred?.or(other: Pred?): Pred? {
            val a = this ?: return null
            val b = other ?: return null
            return { g -> a(g) || b(g) }
        }

        /** "1. félidő" / "2. félidő" / "Döntetlen" — which half has more (team) goals. */
        fun moreGoalsHalf(s: String, value: (Game, Period) -> Int): Pred? = when (s.trim()) {
            "1. felido" -> { g -> value(g, Period.FIRST) > value(g, Period.SECOND) }
            "2. felido" -> { g -> value(g, Period.SECOND) > value(g, Period.FIRST) }
            "dontetlen" -> { g -> value(g, Period.FIRST) == value(g, Period.SECOND) }
            else -> null
        }

        /** "Anglia / Döntetlen" — half-time result / full-time result. */
        fun htft(s: String): Pred? {
            val parts = s.split("/").map { it.trim() }
            if (parts.size != 2) return null
            val ht = resultIn(parts[0], Period.FIRST) ?: return null
            val ft = resultIn(parts[1], Period.FULL) ?: return null
            usesHalves = true
            return { g -> ht(g) && ft(g) }
        }

        /** "{h} Több, mint 1,5" — a team's goal total inside a combo. */
        fun teamTotal(s: String): Pred? {
            val (t, rest) = teamMarket(s.trim()) ?: return null
            return countPred(rest) { g -> goals(t, g) }
        }

        // ---- counts: over/under (incl. Asian lines), exact, range, parity ----

        /** A full selection over a count (goals total or a team's goals). Asian lines may void or split. */
        fun countSelection(label: String, value: Count): Selection? {
            OVER_UNDER.matchEntire(label.trim())?.let { m ->
                return legs(lineLegs(num(m.groupValues[2]), m.groupValues[1] == "tobb", value))
            }
            return countPred(label, value)?.let(::sel)
        }

        /** A yes/no condition over a count; only half-goal (x,5) lines here since they can't push. */
        fun countPred(label: String, value: Count): Pred? {
            val s = label.trim()
            OVER_UNDER.matchEntire(s)?.let { m ->
                val line = num(m.groupValues[2])
                if (line % 1.0 != 0.5) return null
                return if (m.groupValues[1] == "tobb") { g -> value(g) > line } else { g -> value(g) < line }
            }
            Regex("""^(\d+) pontosan$""").matchEntire(s)?.let { m -> val n = m.groupValues[1].toInt(); return { g -> value(g) == n } }
            Regex("""^(\d+)-(\d+)(?: tartomany)?$""").matchEntire(s)?.let { m -> val r = m.range(); return { g -> value(g) in r } }
            Regex("""^(\d+)(?: x)? vagy tobb$""").matchEntire(s)?.let { m -> val n = m.groupValues[1].toInt(); return { g -> value(g) >= n } }
            Regex("""^(\d+)\+$""").matchEntire(s)?.let { m -> val n = m.groupValues[1].toInt(); return { g -> value(g) >= n } }
            Regex("""^nem (\d+)$""").matchEntire(s)?.let { m -> val n = m.groupValues[1].toInt(); return { g -> value(g) != n } }
            Regex("""^nem (\d+)-(\d+)$""").matchEntire(s)?.let { m -> val r = m.range(); return { g -> value(g) !in r } }
            return when (s) {
                "paratlan" -> { g -> value(g) % 2 == 1 }
                "paros" -> { g -> value(g) % 2 == 0 }
                else -> null
            }
        }

        fun MatchResult.range() = groupValues[1].toInt()..groupValues[2].toInt()

        /**
         * Settlement legs for an over/under line. Whole lines push (VOID) on the
         * exact number; quarter lines (2,25 / 2,75) are split into two half
         * stakes on the neighbouring lines.
         */
        fun lineLegs(line: Double, over: Boolean, value: Count): List<Leg> =
            quarterSplit(line).map { l ->
                { g: Game ->
                    val v = value(g).toDouble()
                    when {
                        v > l -> Res.of(over)
                        v < l -> Res.of(!over)
                        else -> Res.VOID
                    }
                }
            }

        fun quarterSplit(line: Double): List<Double> =
            if (Math.floorMod((line * 4).roundToInt(), 2) == 1) listOf(line - 0.25, line + 0.25) else listOf(line)

        // ---- handicaps & margins ----

        /** "{h} -1,5" / "{a} +0,25": Asian handicap on the named team. */
        fun asianHandicap(label: String): Selection? {
            val m = Regex("""^(\{[ha]\}) $SIGNED$""").matchEntire(label.trim()) ?: return null
            val t = m.groupValues[1]
            val hcp = num(m.groupValues[2])
            return legs(
                quarterSplit(hcp).map { h ->
                    { g: Game ->
                        val d = goals(t, g) - conceded(t, g) + h
                        when {
                            d > 0 -> Res.WIN
                            d < 0 -> Res.LOSE
                            else -> Res.VOID
                        }
                    }
                },
            )
        }

        /** "{h} (-2) 0:2", "Döntetlen - ({h} -2) 0:2", "{a} (+2) 0:2": three-way handicap. */
        fun europeanHandicap(label: String): Pred? {
            val s = label.trim()
            Regex("""^dontetlen - \((\{[ha]\}) ([+-]\d+)\).*$""").matchEntire(s)?.let { m ->
                val t = m.groupValues[1]
                val h = m.groupValues[2].toInt()
                return { g -> goals(t, g) - conceded(t, g) + h == 0 }
            }
            Regex("""^(\{[ha]\}) \(([+-]\d+)\).*$""").matchEntire(s)?.let { m ->
                val t = m.groupValues[1]
                val h = m.groupValues[2].toInt()
                return { g -> goals(t, g) - conceded(t, g) + h > 0 }
            }
            return null
        }

        /** "{h} 2", "{h} 3+", "Döntetlen, kivéve 0:0", "Nem lesz gól". */
        fun winningMargin(label: String): Pred? {
            val s = label.trim()
            if (s == "nem lesz gol") return { g -> g.total(p) == 0 }
            if (s.startsWith("dontetlen")) return { g -> draw(g) && g.total(p) > 0 }
            val m = Regex("""^(\{[ha]\}) (\d+)(\+| vagy tobb)?$""").matchEntire(s) ?: return null
            val t = m.groupValues[1]
            val n = m.groupValues[2].toInt()
            val atLeast = m.groupValues[3].isNotEmpty()
            return { g -> val d = goals(t, g) - conceded(t, g); if (atLeast) d >= n else d == n }
        }

        fun whichTeamScores(label: String): Pred? = when (label.trim()) {
            "mindketto" -> { g -> btts(g) }
            "csak hazai" -> { g -> home(g) > 0 && away(g) == 0 }
            "csak vendeg" -> { g -> away(g) > 0 && home(g) == 0 }
            "egyik sem" -> { g -> g.total(p) == 0 }
            else -> null
        }

        // ---- exact scores ----

        /** "2:1" (home:away) in the market's period. */
        fun exactScore(label: String): Pred? {
            val m = Regex("""^(\d+)[:-](\d+)$""").matchEntire(label.trim()) ?: return null
            val h = m.groupValues[1].toInt()
            val a = m.groupValues[2].toInt()
            return { g -> home(g) == h && away(g) == a }
        }

        /**
         * Winner-first score notation used in listed-score markets:
         * "{a} 2-1" means the away side won 2-1, "Döntetlen 1-1" a 1-1 draw.
         * Returns (home, away).
         */
        fun winnerFirst(team: String, score: String): Pair<Int, Int>? {
            val m = Regex("""^(\d+)-(\d+)$""").matchEntire(score.trim()) ?: return null
            val x = m.groupValues[1].toInt()
            val y = m.groupValues[2].toInt()
            return when (team) {
                H, "dontetlen" -> x to y
                A -> y to x
                else -> null
            }
        }

        /** "{h} 2-1, 3-1, 4-1" — full-time score is one of the listed ones. */
        fun scoreList(label: String): Pred? {
            val m = Regex("""^(\{[ha]\}|dontetlen) (.+)$""").matchEntire(label.trim()) ?: return null
            val scores = m.groupValues[2].split(",").map { winnerFirst(m.groupValues[1], it) ?: return null }.toSet()
            return { g -> (home(g) to away(g)) in scores }
        }

        /** "{h} 1-0 és {h} 2-0" — exact half-time and full-time scores. */
        fun htftExact(label: String): Pred? {
            val parts = label.split(" es ").map { it.trim() }
            if (parts.size != 2) return null
            val scores = parts.map { part ->
                val m = Regex("""^(\{[ha]\}|dontetlen) (\d+-\d+)$""").matchEntire(part) ?: return null
                winnerFirst(m.groupValues[1], m.groupValues[2]) ?: return null
            }
            val (ht, ft) = scores
            return { g -> g.home(Period.FIRST) == ht.first && g.away(Period.FIRST) == ht.second && g.home(Period.FULL) == ft.first && g.away(Period.FULL) == ft.second }
        }

        // ---- per-half combos ----

        /** "1. félidő 1-3 és 2. félidő 2 vagy Több" or "{h}: 0-2 és 1 vagy több". */
        fun halfGoalRanges(label: String): Pred? {
            var s = label.trim()
            var value: (Game, Period) -> Int = { g, per -> g.total(per) }
            for (t in listOf(H, A)) if (s.startsWith("$t:")) {
                s = s.removePrefix("$t:").trim()
                value = { g, per -> goalsIn(t, g, per) }
            }
            val parts = s.split(" es ").map { it.trim().removePrefix("1. felido ").removePrefix("2. felido ").trim() }
            if (parts.size != 2) return null
            val first = countPred(parts[0]) { g -> value(g, Period.FIRST) } ?: return null
            val second = countPred(parts[1]) { g -> value(g, Period.SECOND) } ?: return null
            return { g -> first(g) && second(g) }
        }

        /** "1. fi - Több, mint 1,5 és 2. fi - Több, mint 1,5". */
        fun halfTotalsCombo(label: String): Pred? {
            val preds = label.split(" es ").map { part ->
                val m = Regex("""^([12])\. fi - (.+)$""").matchEntire(part.trim()) ?: return null
                val per = if (m.groupValues[1] == "1") Period.FIRST else Period.SECOND
                countPred(m.groupValues[2]) { g -> g.total(per) } ?: return null
            }
            return { g -> preds.all { it(g) } }
        }

        /** "Igen/Nem" = BTTS in 1st half yes, 2nd half no; a single "Igen" = BTTS in both halves. */
        fun bttsPerHalf(label: String): Pred? {
            val bttsIn = { g: Game, per: Period -> g.home(per) >= 1 && g.away(per) >= 1 }
            val parts = label.split("/").map { it.trim() }
            return when (parts.size) {
                1 -> yesNo(parts[0])?.let { y -> { g: Game -> (bttsIn(g, Period.FIRST) && bttsIn(g, Period.SECOND)) == y } }
                2 -> {
                    val y1 = yesNo(parts[0]) ?: return null
                    val y2 = yesNo(parts[1]) ?: return null
                    { g -> bttsIn(g, Period.FIRST) == y1 && bttsIn(g, Period.SECOND) == y2 }
                }
                else -> null
            }
        }
    }

internal val OVER_UNDER = Regex("""^(tobb|kevesebb), mint $NUM$""")

internal fun num(s: String): Double = s.replace(',', '.').toDouble()

/** Lowercase, strip diacritics and quotes, collapse whitespace and fix the site's known quirks. */
internal fun norm(s: String): String {
    val d = Normalizer.normalize(s.trim().lowercase(), Normalizer.Form.NFD)
    return d.replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[„”“\"]"), "")
        .replace(Regex("\\s+"), " ")
        .replace("dontelen", "dontetlen") // typo on the site ("Döntelen")
        .replace(Regex("""([12])\.felido"""), "$1. felido")
        .replace(Regex("""\s*/\s*"""), "/")
        .trim()
}
