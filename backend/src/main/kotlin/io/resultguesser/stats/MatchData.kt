package io.resultguesser.stats

/** How a goal was scored (ESPN key-event types). */
enum class GoalMethod { SHOT, HEADER, FREE_KICK, PENALTY, OWN_GOAL }

/** A timed event of a finished match. [minute] is match time (0–90+). */
sealed interface KeyEvent {
    val minute: Double
    val teamId: String

    data class Goal(
        override val minute: Double,
        override val teamId: String,
        val scorerId: String?,
        val assistId: String?,
        val method: GoalMethod,
    ) : KeyEvent

    data class Card(
        override val minute: Double,
        override val teamId: String,
        val playerId: String?,
        val red: Boolean,
    ) : KeyEvent
}

/** One player's line in a finished match. [stats] uses ESPN names (totalGoals, totalShots, foulsCommitted, …). */
data class PlayerLine(
    val id: String,
    val name: String,
    /** ESPN position abbreviation (G, CD-L, CM, F, … or SUB for substitutes). */
    val position: String?,
    val appeared: Boolean,
    /** In the starting eleven (false for substitutes who came on). */
    val started: Boolean,
    val stats: Map<String, Double>,
)

/** A finished match with everything the model needs, as read from ESPN's match summary. */
data class MatchRecord(
    val id: String,
    val date: String,
    val league: String,
    val competition: String?,
    val homeId: String,
    val awayId: String,
    val homeName: String,
    val awayName: String,
    val homeGoals: Int,
    val awayGoals: Int,
    /** Half-time score, when ESPN has line scores. */
    val homeHt: Int?,
    val awayHt: Int?,
    /** Box-score team statistics per team id (wonCorners, yellowCards, foulsCommitted, …). */
    val teamStats: Map<String, Map<String, Double>>,
    val events: List<KeyEvent>,
    /** Player lines per team id. */
    val players: Map<String, List<PlayerLine>>,
) {
    fun isHome(teamId: String) = homeId == teamId
    fun opponentOf(teamId: String) = if (isHome(teamId)) awayId else homeId
    fun goalsFor(teamId: String) = if (isHome(teamId)) homeGoals else awayGoals
    fun goalsAgainst(teamId: String) = if (isHome(teamId)) awayGoals else homeGoals
}

/** A squad member (from the team roster), used for players without recent appearances. */
data class SquadPlayer(val id: String, val name: String, val position: String?)

/** A team as identified on ESPN, with its recent matches and squad. */
data class TeamData(
    val id: String,
    val name: String,
    /** Last finished matches, newest first. */
    val recent: List<MatchRecord>,
    val squad: List<SquadPlayer>,
)

/** Everything fetched for one fixture. */
data class FixtureData(
    val home: TeamData,
    val away: TeamData,
    /** Previous meetings of the two teams, newest first. */
    val headToHead: List<MatchRecord>,
)
