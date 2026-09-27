package io.resultguesser.predict

import io.resultguesser.scraper.LiveState
import io.resultguesser.stats.HeadToHead
import io.resultguesser.stats.TeamForm
import kotlin.math.min

/**
 * Expected goals for a fixture, the one rate with extra structure: a team's
 * own scoring rate blended with the opponent's conceding rate, nudged by a
 * small home advantage, then pulled towards what the two teams scored against
 * each other in their last meetings. The [Simulator] turns these (and the
 * other statistics' rates) into match outcomes.
 */
object PoissonModel {

    private const val HOME_ADVANTAGE = 1.10
    private const val AWAY_FACTOR = 0.95
    private const val MIN_LAMBDA = 0.2
    private const val MAX_LAMBDA = 5.0

    /** Weight of the head-to-head goal averages at a full sample of 5 meetings. */
    private const val H2H_WEIGHT = 0.30
    private const val H2H_FULL_SAMPLE = 5

    data class ExpectedGoals(val home: Double, val away: Double)

    fun expectedGoals(home: TeamForm, away: TeamForm, h2h: HeadToHead? = null): ExpectedGoals {
        var lambdaHome = ((home.avgScored + away.avgConceded) / 2.0) * HOME_ADVANTAGE
        var lambdaAway = ((away.avgScored + home.avgConceded) / 2.0) * AWAY_FACTOR
        if (h2h != null && h2h.matches.isNotEmpty()) {
            val w = H2H_WEIGHT * min(h2h.matches.size, H2H_FULL_SAMPLE) / H2H_FULL_SAMPLE
            lambdaHome = (1 - w) * lambdaHome + w * h2h.homeAvgGoals
            lambdaAway = (1 - w) * lambdaAway + w * h2h.awayAvgGoals
        }
        return ExpectedGoals(lambdaHome.coerceIn(MIN_LAMBDA, MAX_LAMBDA), lambdaAway.coerceIn(MIN_LAMBDA, MAX_LAMBDA))
    }

    enum class LivePhase { FIRST_HALF, HALF_TIME, SECOND_HALF }

    fun livePhase(live: LiveState): LivePhase {
        val p = live.period?.lowercase().orEmpty()
        return when {
            p.startsWith("1.") -> LivePhase.FIRST_HALF
            p.startsWith("2.") -> LivePhase.SECOND_HALF
            p.contains("félidő") || p.contains("szünet") -> LivePhase.HALF_TIME
            (live.minute ?: 0) > 45 -> LivePhase.SECOND_HALF
            else -> LivePhase.FIRST_HALF
        }
    }
}
