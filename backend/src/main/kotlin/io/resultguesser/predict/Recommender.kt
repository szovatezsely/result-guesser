package io.resultguesser.predict

import io.resultguesser.i18n.Glossary
import io.resultguesser.i18n.ParseContext
import io.resultguesser.scraper.Market
import io.resultguesser.scraper.Outcome
import kotlinx.serialization.Serializable

/** One betting option judged by the model: will it happen (✅) or not (❌)? */
@Serializable
data class Verdict(
    val marketTitle: String,
    val selection: String,
    val odds: Double,
    /** Model probability that the option wins (voided outcomes excluded). */
    val probability: Double,
    /** True (✅) when [probability] reaches the threshold. */
    val happens: Boolean,
    /** False when part of it rests on generic averages rather than these teams'/players' own data. */
    val fromData: Boolean = true,
    /** What the option is about, for grouping in the UI. */
    val category: Category = Category.GOALS,
)

/** Groups of options, by what they are settled on. */
@Serializable
enum class Category { GOALS, CORNERS, CARDS, SHOTS, OTHER_STATS, PLAYERS }

/** An option the model couldn't judge, and why. */
@Serializable
data class SkippedOption(
    val marketTitle: String,
    val selection: String,
    val odds: Double,
    val reason: String,
)

data class Evaluation(val verdicts: List<Verdict>, val skipped: List<SkippedOption>)

object Recommender {

    /** An option "should happen" when the model gives it at least this probability. */
    const val DEFAULT_THRESHOLD = 0.5

    private const val REASON_UNKNOWN = "Ismeretlen piac"
    private const val REASON_LIVE = "Élő meccsen nem értékelhető (a szögletek, lapok, játékosesemények és gólidőpontok eddigi alakulása nem ismert)"
    private const val REASON_HALVES = "A félidős eredmény nem ismert"

    /**
     * Collects the players the markets mention so they are part of the
     * simulated squads (pass 1: unknown names under a team column become
     * placeholders). Call before simulating.
     */
    fun registerPlayers(markets: List<Market>, homeTeam: String, awayTeam: String, players: PlayerDirectory) {
        players.recording = true
        val ctx = ParseContext(players)
        for (market in markets) for (o in market.outcomes) {
            Glossary.parse(market.title, o.label, homeTeam, awayTeam, o.column, ctx)
        }
        players.recording = false
    }

    /**
     * Judges every scraped option. Returns ✅ options first, each group ordered
     * by TippmixPRO odds, highest first — so the top ✅ rows are the biggest
     * payouts the stats still back.
     *
     * @param dataBasis which team statistics came from the teams' own matches.
     */
    fun evaluate(
        markets: List<Market>,
        sim: Simulation,
        homeTeam: String,
        awayTeam: String,
        context: ParseContext = ParseContext(),
        dataBasis: Map<Stat, Basis> = emptyMap(),
        threshold: Double = DEFAULT_THRESHOLD,
    ): Evaluation {
        val skipped = mutableListOf<SkippedOption>()
        val judged = mutableListOf<Triple<Market, Outcome, Selection>>()
        val seen = HashSet<Pair<String, String>>()
        for (market in markets) {
            for (outcome in market.outcomes) {
                if (outcome.odds <= 1.0) continue
                // The /all tab can list the same market twice (e.g. in "Népszerű" and its own group).
                if (!seen.add(market.title to outcome.label)) continue
                fun skip(reason: String) { skipped += SkippedOption(market.title, outcome.label, outcome.odds, reason) }

                val selection = Glossary.parse(market.title, outcome.label, homeTeam, awayTeam, outcome.column, context)
                when {
                    selection == null -> skip(REASON_UNKNOWN)
                    sim.live && !selection.liveOk -> skip(REASON_LIVE)
                    !sim.halvesKnown && selection.usesHalves -> skip(REASON_HALVES)
                    else -> judged += Triple(market, outcome, selection)
                }
            }
        }
        // Settling thousands of options over the simulated matches is the costly part: spread it over the cores.
        val probabilities = judged.parallelStream().map { (_, _, s) -> sim.probabilityOf(s) ?: Double.NaN }.toList()
        val verdicts = judged.zip(probabilities).mapNotNull { (j, probability) ->
            val (market, outcome, selection) = j
            if (probability.isNaN()) {
                skipped += SkippedOption(market.title, outcome.label, outcome.odds, REASON_UNKNOWN)
                return@mapNotNull null
            }
            Verdict(
                marketTitle = market.title,
                selection = outcome.label,
                odds = outcome.odds,
                probability = probability,
                happens = probability >= threshold,
                fromData = fromData(selection, dataBasis, context.players),
                category = categoryOf(selection),
            )
        }
        return Evaluation(
            verdicts = verdicts.sortedWith(compareByDescending<Verdict> { it.happens }.thenByDescending { it.odds }),
            skipped = skipped,
        )
    }

    private fun categoryOf(s: Selection): Category = when {
        s.players.isNotEmpty() -> Category.PLAYERS
        Stat.CORNERS in s.stats -> Category.CORNERS
        Stat.YELLOW in s.stats || Stat.RED in s.stats || Stat.PENALTIES in s.stats -> Category.CARDS
        Stat.SHOTS in s.stats || Stat.SHOTS_ON_TARGET in s.stats -> Category.SHOTS
        s.stats.any { it != Stat.GOALS } -> Category.OTHER_STATS
        else -> Category.GOALS
    }

    private fun fromData(s: Selection, basis: Map<Stat, Basis>, players: PlayerDirectory?): Boolean {
        if (s.stats.any { (basis[it] ?: Basis.DATA) == Basis.BASELINE }) return false
        if (players == null) return true
        return s.players.all { ref -> s.playerStats.all { players.profile(ref).hasData(it) } }
    }
}
