package io.resultguesser.predict

import io.resultguesser.scraper.LiveState
import io.resultguesser.stats.GoalMethod
import java.util.SplittableRandom
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Monte-Carlo match simulator. Every team statistic is an independent Poisson
 * count per half with its own 1st-half share ([Stat.firstHalfShare]); timed
 * events (goals, corners, cards, penalties) get uniform minutes inside their
 * half. Each simulated match first draws who plays (from how often each
 * player appeared recently); goals are then attributed among those players in
 * proportion to their scoring rates, assists and cards likewise, and player
 * shot/foul/offside/tackle counts are drawn from their own rates. Player bets
 * are void in matches where the player didn't play, as bookmakers settle them.
 *
 * A fixed seed makes results reproducible, so options near the threshold
 * don't flip between page loads.
 */
object Simulator {

    const val DEFAULT_RUNS = 10_000

    private const val PENALTY_CONVERSION = 0.77
    private const val OWN_GOAL_SHARE = 0.035
    private const val HEADER_SHARE = 0.17
    private const val FREE_KICK_SHARE = 0.03
    private const val ASSISTED_SHARE = 0.72

    /**
     * @param rates per team (0 home, 1 away) expected per-match value of each [Stat]; goals already include
     *   home advantage and head-to-head.
     */
    fun run(
        rates: List<Map<Stat, Double>>,
        players: PlayerDirectory,
        live: LiveState? = null,
        seed: Long = 1L,
        runs: Int = DEFAULT_RUNS,
    ): Simulation {
        val rnd = SplittableRandom(seed)
        val phase = live?.let { PoissonModel.livePhase(it) }
        val now = when (phase) {
            null -> 0.0
            PoissonModel.LivePhase.FIRST_HALF -> (live.minute ?: 0).toDouble().coerceIn(0.0, 44.0)
            PoissonModel.LivePhase.HALF_TIME -> 45.0
            PoissonModel.LivePhase.SECOND_HALF -> (live.minute ?: 46).toDouble().coerceIn(45.0, 89.0)
        }
        // Remaining part of each half: [start, 45) and [start, 90).
        val h1Start = now.coerceAtMost(45.0)
        val h2Start = now.coerceAtLeast(45.0)
        val frac1 = (45.0 - h1Start) / 45.0
        val frac2 = (90.0 - h2Start) / 45.0

        val pools = listOf(players.pool(0), players.pool(1))
        val participation = pools.map { pool -> pool.map { it.participation }.toDoubleArray() }
        val startShare = pools.map { pool -> pool.map { it.startShare }.toDoubleArray() }
        val goalRates = pools.map { pool -> pool.map { it.rate(PlayerStat.GOALS) }.toDoubleArray() }
        val assistRates = pools.map { pool -> pool.map { it.rate(PlayerStat.ASSISTS) }.toDoubleArray() }
        val yellowRates = pools.map { pool -> pool.map { it.rate(PlayerStat.YELLOW) }.toDoubleArray() }
        val redRates = pools.map { pool -> pool.map { it.rate(PlayerStat.RED) }.toDoubleArray() }
        val tackleScale = (0..1).map { t -> (rates[t][Stat.TACKLES] ?: Stat.TACKLES.baseline) / Stat.TACKLES.baseline }
        val playerRates = pools.mapIndexed { t, pool ->
            pool.map { p ->
                doubleArrayOf(
                    p.rate(PlayerStat.GOALS), p.rate(PlayerStat.SHOTS_ON_TARGET), p.rate(PlayerStat.SHOTS),
                    p.rate(PlayerStat.FOULS), p.rate(PlayerStat.OFFSIDES), p.rate(PlayerStat.TACKLES) * tackleScale[t],
                )
            }
        }

        fun rate(team: Int, stat: Stat) = rates[team][stat] ?: stat.baseline

        val matches = ArrayList<SimMatch>(runs)
        repeat(runs) {
            val goals = ArrayList<GoalEvent>()
            val corners = ArrayList<TimedEvent>()
            val cards = ArrayList<CardEvent>()
            val penalties = ArrayList<PenaltyEvent>()
            val halfCounts = Array(COUNTED_STATS.size) { Array(2) { IntArray(2) } }
            val playerCounts = Array(2) { t -> ByteArray(pools[t].size * COUNTED_PLAYER_STATS.size) }
            // Line-ups: who plays in this match, and for how long (a full match, or a substitute's share).
            val played = Array(2) { t -> BooleanArray(pools[t].size) { i -> rnd.nextDouble() < participation[t][i] } }
            val minutes = Array(2) { t ->
                DoubleArray(pools[t].size) { i ->
                    when {
                        !played[t][i] -> 0.0
                        rnd.nextDouble() < startShare[t][i] -> 1.0
                        else -> PlayerProfile.SUB_SHARE
                    }
                }
            }
            val goalWeights = List(2) { t -> Weights(goalRates[t], minutes[t]) }
            val assistWeights = List(2) { t -> Weights(assistRates[t], minutes[t]) }
            val yellowWeights = List(2) { t -> Weights(yellowRates[t], minutes[t]) }
            val redWeights = List(2) { t -> Weights(redRates[t], minutes[t]) }

            // Goals already scored in a live match: known count, unknown minute/scorer.
            if (live != null) {
                repeat(live.homeGoals) { goals += GoalEvent(rnd.nextDouble() * now, 0, -1, -1, GoalMethod.SHOT) }
                repeat(live.awayGoals) { goals += GoalEvent(rnd.nextDouble() * now, 1, -1, -1, GoalMethod.SHOT) }
            }

            for (team in 0..1) {
                fun perHalf(stat: Stat, total: Double, emit: (minute: Double) -> Unit) {
                    val share = stat.firstHalfShare
                    repeat(poisson(rnd, total * share * frac1)) { emit(h1Start + rnd.nextDouble() * (45.0 - h1Start)) }
                    repeat(poisson(rnd, total * (1 - share) * frac2)) { emit(h2Start + rnd.nextDouble() * (90.0 - h2Start)) }
                }

                val penRate = rate(team, Stat.PENALTIES)
                perHalf(Stat.PENALTIES, penRate) { minute ->
                    val scored = rnd.nextDouble() < PENALTY_CONVERSION
                    penalties += PenaltyEvent(minute, team, scored)
                    if (scored) goals += GoalEvent(minute, team, goalWeights[team].pick(rnd), -1, GoalMethod.PENALTY)
                }
                val openPlay = max(0.05, rate(team, Stat.GOALS) - penRate * PENALTY_CONVERSION)
                perHalf(Stat.GOALS, openPlay) { minute ->
                    val u = rnd.nextDouble()
                    if (u < OWN_GOAL_SHARE) {
                        goals += GoalEvent(minute, team, -1, -1, GoalMethod.OWN_GOAL)
                    } else {
                        val scorer = goalWeights[team].pick(rnd)
                        val assist = if (rnd.nextDouble() < ASSISTED_SHARE) assistWeights[team].pick(rnd, exclude = scorer) else -1
                        val method = when {
                            u < OWN_GOAL_SHARE + HEADER_SHARE -> GoalMethod.HEADER
                            u < OWN_GOAL_SHARE + HEADER_SHARE + FREE_KICK_SHARE -> GoalMethod.FREE_KICK
                            else -> GoalMethod.SHOT
                        }
                        goals += GoalEvent(minute, team, scorer, assist, method)
                    }
                }
                perHalf(Stat.CORNERS, rate(team, Stat.CORNERS)) { corners += TimedEvent(it, team) }
                perHalf(Stat.YELLOW, rate(team, Stat.YELLOW)) { cards += CardEvent(it, team, yellowWeights[team].pick(rnd), red = false) }
                perHalf(Stat.RED, rate(team, Stat.RED)) { cards += CardEvent(it, team, redWeights[team].pick(rnd), red = true) }

                // Per-half counts. Shots on target include the team's non-own goals; shots include shots on target.
                val ownGoalsBy = { half: Int -> goals.count { it.team == team && it.method != GoalMethod.OWN_GOAL && (it.minute < 45) == (half == 0) && it.minute >= now } }
                for ((i, stat) in COUNTED_STATS.withIndex()) {
                    val total = rate(team, stat)
                    val share = stat.firstHalfShare
                    for (half in 0..1) {
                        val f = if (half == 0) share * frac1 else (1 - share) * frac2
                        halfCounts[i][team][half] = when (stat) {
                            Stat.SHOTS_ON_TARGET -> ownGoalsBy(half) + poisson(rnd, max(0.2, total - rate(team, Stat.GOALS)) * f)
                            Stat.SHOTS -> 0 // filled below from shots on target
                            else -> poisson(rnd, total * f)
                        }
                    }
                }
                val sot = COUNTED_STATS.indexOf(Stat.SHOTS_ON_TARGET)
                val shots = COUNTED_STATS.indexOf(Stat.SHOTS)
                for (half in 0..1) {
                    val f = if (half == 0) Stat.SHOTS.firstHalfShare * frac1 else (1 - Stat.SHOTS.firstHalfShare) * frac2
                    val extra = max(0.5, rate(team, Stat.SHOTS) - rate(team, Stat.SHOTS_ON_TARGET))
                    halfCounts[shots][team][half] = halfCounts[sot][team][half] + poisson(rnd, extra * f)
                }

                // Player counts (conditional on the player playing). Shots on target ≥ goals, shots ≥ shots on target.
                val k = COUNTED_PLAYER_STATS.size
                val pc = playerCounts[team]
                for ((i, r) in playerRates[team].withIndex()) {
                    val f = minutes[team][i]
                    if (f == 0.0) continue
                    val scored = goals.count { it.team == team && it.scorer == i && it.method != GoalMethod.PENALTY }
                    val onTarget = scored + poisson(rnd, max(0.0, r[1] - r[0]) * f)
                    val shotCount = onTarget + poisson(rnd, max(0.0, r[2] - r[1]) * f)
                    pc[i * k + 0] = clampByte(shotCount)
                    pc[i * k + 1] = clampByte(onTarget)
                    pc[i * k + 2] = clampByte(poisson(rnd, r[3] * f))
                    pc[i * k + 3] = clampByte(poisson(rnd, r[4] * f))
                    pc[i * k + 4] = clampByte(poisson(rnd, r[5] * f))
                }
            }

            goals.sortBy { it.minute }
            corners.sortBy { it.minute }
            cards.sortBy { it.minute }
            penalties.sortBy { it.minute }
            matches += SimMatch(goals, corners, cards, penalties, halfCounts, playerCounts, played, now)
        }
        return Simulation(matches, halvesKnown = phase != PoissonModel.LivePhase.SECOND_HALF, live = live != null)
    }

    private fun clampByte(n: Int): Byte = n.coerceIn(0, 127).toByte()

    /** Poisson draw: Knuth's method for small means, normal approximation for large ones. */
    fun poisson(rnd: SplittableRandom, lambda: Double): Int {
        if (lambda <= 0.0) return 0
        if (lambda > 30.0) {
            val g = rnd.nextGaussian()
            return (lambda + sqrt(lambda) * g).roundToInt().coerceAtLeast(0)
        }
        val limit = exp(-lambda)
        var k = 0
        var p = rnd.nextDouble()
        while (p > limit) {
            k++
            p *= rnd.nextDouble()
        }
        return k
    }

    /** Weighted index picker: per-match [rates] × share of the match each player is on the pitch (-1 if all zero). */
    private class Weights(rates: DoubleArray, onPitch: DoubleArray) {
        private val cumulative = DoubleArray(rates.size).also { c ->
            var acc = 0.0
            for (i in rates.indices) {
                acc += max(0.0, rates[i]) * onPitch[i]
                c[i] = acc
            }
        }
        private val total = cumulative.lastOrNull() ?: 0.0

        fun pick(rnd: SplittableRandom, exclude: Int = -1): Int {
            if (total <= 0.0) return -1
            repeat(3) {
                val u = rnd.nextDouble() * total
                var lo = 0
                var hi = cumulative.size - 1
                while (lo < hi) {
                    val mid = (lo + hi) / 2
                    if (cumulative[mid] < u) lo = mid + 1 else hi = mid
                }
                if (lo != exclude) return lo
            }
            return -1
        }
    }
}
