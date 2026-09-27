package io.resultguesser.predict

import io.resultguesser.stats.MatchRecord
import io.resultguesser.stats.SquadPlayer
import io.resultguesser.stats.TeamData
import java.text.Normalizer

/**
 * A per-team match statistic the model simulates. [espnKey] is the ESPN
 * box-score field (null = ESPN doesn't provide it, so only the [baseline] —
 * a typical per-team value in professional football — is available).
 * [firstHalfShare] is the typical share of the stat falling in the 1st half.
 */
enum class Stat(val espnKey: String?, val baseline: Double, val firstHalfShare: Double) {
    GOALS(null, 1.35, 0.45),
    CORNERS("wonCorners", 4.8, 0.48),
    YELLOW("yellowCards", 1.9, 0.38),
    RED("redCards", 0.08, 0.30),
    FOULS("foulsCommitted", 11.5, 0.48),
    OFFSIDES("offsides", 1.8, 0.50),
    SHOTS("totalShots", 12.0, 0.47),
    SHOTS_ON_TARGET("shotsOnTarget", 4.1, 0.47),
    PENALTIES("penaltyKickShots", 0.13, 0.45),
    TACKLES("totalTackles", 16.0, 0.50),
    THROW_INS(null, 21.0, 0.50),
    GOAL_KICKS(null, 7.5, 0.50),
}

/** Where a rate came from: the teams' own recent matches, or the generic baseline. */
enum class Basis { DATA, BASELINE }

data class Rate(val value: Double, val basis: Basis)

/** Averages of one team over its recent matches: what it produces and what it allows. */
class TeamProfile(val team: TeamData) {
    val matches: Int = team.recent.size

    /** Per-match average of [stat] for this team (null when no match reported it). */
    fun forAvg(stat: Stat): Double? = average(stat) { m -> value(m, team.id, stat) }

    /** Per-match average of [stat] for this team's opponents. */
    fun againstAvg(stat: Stat): Double? = average(stat) { m -> value(m, m.opponentOf(team.id), stat) }

    private fun average(stat: Stat, pick: (MatchRecord) -> Double?): Double? {
        val values = team.recent.mapNotNull(pick)
        return if (values.isEmpty()) null else values.average()
    }

    private fun value(m: MatchRecord, teamId: String, stat: Stat): Double? = when (stat) {
        Stat.GOALS -> m.goalsFor(teamId).toDouble()
        else -> stat.espnKey?.let { m.teamStats[teamId]?.get(it) }
    }
}

/** Rate of [stat] for [team] against [opponent]: own production blended with what the opponent allows. */
fun rateOf(stat: Stat, team: TeamProfile, opponent: TeamProfile): Rate {
    val own = team.forAvg(stat)
    val allowed = opponent.againstAvg(stat)
    val known = listOfNotNull(own, allowed)
    return if (known.isEmpty()) Rate(stat.baseline, Basis.BASELINE) else Rate(known.average(), Basis.DATA)
}

/** Broad position used for priors. */
enum class Role { GK, DEF, MID, FWD }

fun roleOf(position: String?): Role? {
    val p = position?.uppercase() ?: return null
    return when {
        p == "SUB" || p.isBlank() -> null
        p.startsWith("G") -> Role.GK
        p.contains('M') -> Role.MID
        p.contains('D') || p.contains('B') -> Role.DEF
        else -> Role.FWD
    }
}

/**
 * A player-level statistic. [espnKey] is the ESPN player-line field (null = not provided).
 * [prior] is a typical per-appearance value by role for an average team; [shrink] is how many
 * pseudo-appearances of that prior a player's own record is blended with (more for rare events,
 * where 5 matches say little). [teamStat] scales the prior by the team's strength in that statistic.
 */
enum class PlayerStat(val espnKey: String?, val prior: Map<Role, Double>, val shrink: Double, val teamStat: Stat?) {
    GOALS("totalGoals", mapOf(Role.FWD to 0.35, Role.MID to 0.13, Role.DEF to 0.06, Role.GK to 0.0), 4.0, Stat.GOALS),
    ASSISTS("goalAssists", mapOf(Role.FWD to 0.14, Role.MID to 0.14, Role.DEF to 0.07, Role.GK to 0.01), 4.0, Stat.GOALS),
    SHOTS("totalShots", mapOf(Role.FWD to 2.2, Role.MID to 1.2, Role.DEF to 0.6, Role.GK to 0.02), 1.5, Stat.SHOTS),
    SHOTS_ON_TARGET("shotsOnTarget", mapOf(Role.FWD to 0.9, Role.MID to 0.4, Role.DEF to 0.2, Role.GK to 0.0), 2.0, Stat.SHOTS_ON_TARGET),
    FOULS("foulsCommitted", mapOf(Role.FWD to 1.1, Role.MID to 1.3, Role.DEF to 1.2, Role.GK to 0.05), 1.5, Stat.FOULS),
    YELLOW("yellowCards", mapOf(Role.FWD to 0.12, Role.MID to 0.20, Role.DEF to 0.20, Role.GK to 0.03), 4.0, Stat.YELLOW),
    RED("redCards", mapOf(Role.FWD to 0.01, Role.MID to 0.012, Role.DEF to 0.015, Role.GK to 0.005), 10.0, Stat.RED),
    OFFSIDES("offsides", mapOf(Role.FWD to 0.45, Role.MID to 0.12, Role.DEF to 0.03, Role.GK to 0.0), 2.0, Stat.OFFSIDES),
    TACKLES(null, mapOf(Role.FWD to 0.9, Role.MID to 2.0, Role.DEF to 2.2, Role.GK to 0.05), 1.5, Stat.TACKLES),
}

/**
 * One player the simulation knows about: from recent line-ups, the squad
 * list, or a bookmaker-listed name we couldn't find ([placeholder]).
 */
class PlayerProfile(
    val name: String,
    val team: Int,
    val role: Role,
    /** Appearances in the team's recent matches. */
    val apps: Int,
    /** Of those, starts (the rest were substitute appearances). */
    val starts: Int,
    /** Probability of playing, from the share of the team's recent matches this player appeared in. */
    val participation: Double,
    private val totals: Map<PlayerStat, Double>,
    val placeholder: Boolean = false,
) {
    val key: String = normalizeName(name)

    /** Team-strength multipliers for the priors (team rate ÷ baseline), set once team rates are known. */
    var priorScale: Map<Stat, Double> = emptyMap()

    /** Full-match equivalents played: a start counts 1, a substitute appearance [SUB_SHARE]. */
    private val fullMatches: Double = starts + (apps - starts) * SUB_SHARE

    /** Chance of starting when playing (unknown players: even odds). */
    val startShare: Double = if (apps > 0) starts.toDouble() / apps else 0.5

    /** Per-full-match rate, shrunk towards the (team-scaled) positional prior. */
    fun rate(stat: PlayerStat): Double {
        val prior = stat.prior.getValue(role) * (stat.teamStat?.let { priorScale[it] } ?: 1.0)
        val total = if (stat.espnKey == null) null else totals[stat]
        if (total == null || apps == 0) return prior
        return (total + stat.shrink * prior) / (fullMatches + stat.shrink)
    }

    /** True when the rate for [stat] rests on the player's own data. */
    fun hasData(stat: PlayerStat) = !placeholder && apps > 0 && stat.espnKey != null

    companion object {
        /** Share of a match a substitute typically plays. */
        const val SUB_SHARE = 0.35

        /** Squad members who didn't appear in any recent match rarely play. */
        const val UNSEEN_PARTICIPATION = 0.05

        /** A bookmaker-listed player we can't find: odds are offered, so assume a fair chance of playing. */
        const val PLACEHOLDER_PARTICIPATION = 0.5

        fun placeholder(name: String, team: Int) =
            PlayerProfile(name, team, Role.MID, apps = 0, starts = 0, participation = PLACEHOLDER_PARTICIPATION, totals = emptyMap(), placeholder = true)
    }
}

/** Builds the player pool of one team (index [team]: 0 home, 1 away) from recent line-ups plus the squad. */
fun playerPool(team: Int, data: TeamData): List<PlayerProfile> {
    val matches = data.recent.size.coerceAtLeast(1)
    val lines = data.recent.flatMap { m -> m.players[data.id].orEmpty() }.filter { it.appeared }
    val byId = lines.groupBy { it.id }
    val squadById = data.squad.associateBy { it.id }
    val seen = byId.map { (id, ls) ->
        val role = ls.mapNotNull { roleOf(it.position) }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            ?: roleOf(squadById[id]?.position) ?: Role.MID
        val totals = PlayerStat.entries.filter { it.espnKey != null }.associateWith { s -> ls.sumOf { it.stats[s.espnKey] ?: 0.0 } }
        PlayerProfile(ls.first().name, team, role, ls.size, ls.count { it.started }, ls.size.toDouble() / matches, totals)
    }
    val seenIds = byId.keys
    val unseen = data.squad.filter { it.id !in seenIds }.map { s: SquadPlayer ->
        PlayerProfile(s.name, team, roleOf(s.position) ?: Role.MID, 0, 0, PlayerProfile.UNSEEN_PARTICIPATION, emptyMap())
    }
    return seen + unseen
}

/** Lowercase, no diacritics, punctuation → spaces: "Milinković-Savić" → "milinkovic savic". */
fun normalizeName(s: String): String {
    val d = Normalizer.normalize(s.trim().lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
    return d.replace("ø", "o").replace("ß", "ss").replace("ł", "l").replace("đ", "d")
        .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
}
