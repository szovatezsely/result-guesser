package io.adroit.resultguesser.predict

/** Predicate over a final scoreline (home goals, away goals). */
typealias GoalPredicate = (home: Int, away: Int) -> Boolean

/**
 * Joint probability distribution over final scorelines, indexed
 * as matrix[homeGoals][awayGoals]. Goals are capped at [maxGoals].
 */
class ScoreMatrix(val matrix: Array<DoubleArray>) {

    val maxGoals: Int get() = matrix.size - 1

    /** Total probability mass of all scorelines satisfying [predicate]. */
    fun probabilityOf(predicate: GoalPredicate): Double {
        var sum = 0.0
        for (h in matrix.indices) {
            for (a in matrix[h].indices) {
                if (predicate(h, a)) sum += matrix[h][a]
            }
        }
        return sum
    }
}
