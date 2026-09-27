package io.resultguesser.stats

import kotlinx.serialization.Serializable

/** One finished match seen from a given team's perspective. */
@Serializable
data class RecentMatch(
    val date: String,
    val competition: String? = null,
    val opponent: String,
    val home: Boolean,
    val goalsFor: Int,
    val goalsAgainst: Int,
) {
    /** "W" / "D" / "L". */
    val result: String get() = when {
        goalsFor > goalsAgainst -> "W"
        goalsFor < goalsAgainst -> "L"
        else -> "D"
    }
}

/** A team statistic's per-match average in its recent matches: its own, and its opponents'. */
@Serializable
data class StatLine(
    val label: String,
    val forAvg: Double?,
    val againstAvg: Double?,
)

/** Aggregated recent form of a team (its last N finished matches). */
@Serializable
data class TeamForm(
    val teamName: String,
    val matches: Int,
    val avgScored: Double,
    val avgConceded: Double,
    val recent: List<RecentMatch> = emptyList(),
    val stats: List<StatLine> = emptyList(),
)

/** A previous meeting of the two teams, in actual home/away order. */
@Serializable
data class H2hMatch(
    val date: String,
    val competition: String? = null,
    val homeTeam: String,
    val awayTeam: String,
    val homeGoals: Int,
    val awayGoals: Int,
)

/**
 * Last meetings of the analysed match's two teams, with goal averages from the
 * perspective of *this* fixture's home and away side (regardless of venue then).
 */
@Serializable
data class HeadToHead(
    val matches: List<H2hMatch>,
    val homeAvgGoals: Double,
    val awayAvgGoals: Double,
)
