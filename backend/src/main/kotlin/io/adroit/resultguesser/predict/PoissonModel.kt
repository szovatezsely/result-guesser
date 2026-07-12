package io.adroit.resultguesser.predict

import io.adroit.resultguesser.stats.TeamForm
import kotlin.math.exp
import kotlin.math.pow

/**
 * A lightweight independent-Poisson model for football scorelines.
 *
 * Expected goals for each side are derived from recent form: a team's own
 * scoring rate blended with the opponent's conceding rate, nudged by a small
 * home advantage. Home and away goal counts are then treated as independent
 * Poisson variables to build the full scoreline distribution.
 */
object PoissonModel {

    private const val HOME_ADVANTAGE = 1.10
    private const val AWAY_FACTOR = 0.95
    private const val MAX_GOALS = 8
    private const val MIN_LAMBDA = 0.2
    private const val MAX_LAMBDA = 5.0

    data class ExpectedGoals(val home: Double, val away: Double)

    fun expectedGoals(home: TeamForm, away: TeamForm): ExpectedGoals {
        val lambdaHome = ((home.avgScored + away.avgConceded) / 2.0) * HOME_ADVANTAGE
        val lambdaAway = ((away.avgScored + home.avgConceded) / 2.0) * AWAY_FACTOR
        return ExpectedGoals(lambdaHome.coerceIn(MIN_LAMBDA, MAX_LAMBDA), lambdaAway.coerceIn(MIN_LAMBDA, MAX_LAMBDA))
    }

    fun scoreMatrix(home: TeamForm, away: TeamForm): ScoreMatrix {
        val (lh, la) = expectedGoals(home, away)
        val homeProbs = DoubleArray(MAX_GOALS + 1) { poisson(it, lh) }
        val awayProbs = DoubleArray(MAX_GOALS + 1) { poisson(it, la) }

        val matrix = Array(MAX_GOALS + 1) { h ->
            DoubleArray(MAX_GOALS + 1) { a -> homeProbs[h] * awayProbs[a] }
        }
        return ScoreMatrix(matrix)
    }

    /** Poisson probability mass P(k; lambda). */
    private fun poisson(k: Int, lambda: Double): Double {
        return exp(-lambda) * lambda.pow(k) / factorial(k)
    }

    private fun factorial(n: Int): Double {
        var f = 1.0
        for (i in 2..n) f *= i
        return f
    }
}
