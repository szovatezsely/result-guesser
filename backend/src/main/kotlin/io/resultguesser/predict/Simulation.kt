package io.resultguesser.predict

import io.resultguesser.stats.GoalMethod

/** Which part of the match a market is settled on. */
enum class Period { FULL, FIRST, SECOND }

/** Goals per team per half; the part of a match every goal market is settled on. */
open class Game(val h1: Int, val a1: Int, val h2: Int, val a2: Int) {
    fun home(p: Period) = when (p) { Period.FULL -> h1 + h2; Period.FIRST -> h1; Period.SECOND -> h2 }
    fun away(p: Period) = when (p) { Period.FULL -> a1 + a2; Period.FIRST -> a1; Period.SECOND -> a2 }
    fun total(p: Period) = home(p) + away(p)
}

/** How a bet settles on a given outcome. VOID = stake returned (e.g. draw-no-bet on a draw). */
enum class Res {
    WIN, LOSE, VOID;

    companion object {
        fun of(win: Boolean) = if (win) WIN else LOSE
    }
}

/** Settles one bet on a simulated match. Goal-only legs only look at the [Game] part. */
typealias Leg = (Game) -> Res

/**
 * A betting selection the model understands. Usually one leg; Asian quarter
 * lines (e.g. over 2,25) are two half-stake legs (over 2 + over 2,5).
 *
 * @property usesHalves depends on the half-time split of goals.
 * @property liveOk can be judged in play (only goals are known live: no
 *   corners/cards/players so far, nor when the goals were scored).
 * @property stats team statistics it relies on (to report whether they came from data).
 * @property players players it relies on, and [playerStats] which of their statistics.
 */
class Selection(
    val legs: List<Leg>,
    val usesHalves: Boolean,
    val liveOk: Boolean = true,
    val stats: Set<Stat> = setOf(Stat.GOALS),
    val players: Set<PlayerRef> = emptySet(),
    val playerStats: Set<PlayerStat> = emptySet(),
)

class GoalEvent(val minute: Double, val team: Int, val scorer: Int, val assist: Int, val method: GoalMethod)
class TimedEvent(val minute: Double, val team: Int)
class CardEvent(val minute: Double, val team: Int, val player: Int, val red: Boolean)
class PenaltyEvent(val minute: Double, val team: Int, val scored: Boolean)

/** Team statistics kept only as per-half counts (no timing needed by any market). */
val COUNTED_STATS = listOf(Stat.FOULS, Stat.OFFSIDES, Stat.SHOTS, Stat.SHOTS_ON_TARGET, Stat.TACKLES, Stat.THROW_INS, Stat.GOAL_KICKS)

/** Player statistics kept as per-player counts (goals, assists and cards come from the events). */
val COUNTED_PLAYER_STATS = listOf(PlayerStat.SHOTS, PlayerStat.SHOTS_ON_TARGET, PlayerStat.FOULS, PlayerStat.OFFSIDES, PlayerStat.TACKLES)

/** One simulated match. Minutes run 0–90; the 1st half is [0, 45). */
class SimMatch(
    val goals: List<GoalEvent>,
    val corners: List<TimedEvent>,
    val cards: List<CardEvent>,
    val penalties: List<PenaltyEvent>,
    /** [statIndex][team][half] for [COUNTED_STATS]. */
    private val halfCounts: Array<Array<IntArray>>,
    /** Per team: [playerIndex * COUNTED_PLAYER_STATS.size + statIndex]. */
    private val playerCounts: Array<ByteArray>,
    /** Per team: whether each player of the pool took part. */
    private val played: Array<BooleanArray>,
    /** Minute the simulation started from (0 pre-match, the current minute live). */
    val now: Double,
) : Game(
    goals.count { it.team == 0 && it.minute < 45 }, goals.count { it.team == 1 && it.minute < 45 },
    goals.count { it.team == 0 && it.minute >= 45 }, goals.count { it.team == 1 && it.minute >= 45 },
) {
    /** Goals after [now] only — for "rest of the match" markets. */
    val rest: Game by lazy {
        val later = goals.filter { it.minute >= now }
        Game(
            later.count { it.team == 0 && it.minute < 45 }, later.count { it.team == 1 && it.minute < 45 },
            later.count { it.team == 0 && it.minute >= 45 }, later.count { it.team == 1 && it.minute >= 45 },
        )
    }

    /** Count of [stat] for [team] (null = both teams) in period [p]. Cards count yellow + red via [cards]. */
    fun count(stat: Stat, team: Int?, p: Period): Int = when (stat) {
        Stat.GOALS -> goals.count { (team == null || it.team == team) && inPeriod(it.minute, p) }
        Stat.CORNERS -> corners.count { (team == null || it.team == team) && inPeriod(it.minute, p) }
        Stat.YELLOW -> cards.count { !it.red && (team == null || it.team == team) && inPeriod(it.minute, p) }
        Stat.RED -> cards.count { it.red && (team == null || it.team == team) && inPeriod(it.minute, p) }
        Stat.PENALTIES -> penalties.count { (team == null || it.team == team) && inPeriod(it.minute, p) }
        else -> {
            val c = halfCounts[COUNTED_STATS.indexOf(stat)]
            val teams = if (team == null) listOf(0, 1) else listOf(team)
            teams.sumOf { t -> when (p) { Period.FULL -> c[t][0] + c[t][1]; Period.FIRST -> c[t][0]; Period.SECOND -> c[t][1] } }
        }
    }

    fun played(ref: PlayerRef): Boolean = played[ref.team][ref.index]

    /** Bookings (yellow + red cards). */
    fun cards(team: Int?, p: Period) = count(Stat.YELLOW, team, p) + count(Stat.RED, team, p)

    fun playerCount(ref: PlayerRef, stat: PlayerStat, p: Period = Period.FULL): Int = when (stat) {
        PlayerStat.GOALS -> goals.count { it.team == ref.team && it.scorer == ref.index && inPeriod(it.minute, p) }
        PlayerStat.ASSISTS -> goals.count { it.team == ref.team && it.assist == ref.index && inPeriod(it.minute, p) }
        PlayerStat.YELLOW -> cards.count { !it.red && it.team == ref.team && it.player == ref.index && inPeriod(it.minute, p) }
        PlayerStat.RED -> cards.count { it.red && it.team == ref.team && it.player == ref.index && inPeriod(it.minute, p) }
        else -> playerCounts[ref.team][ref.index * COUNTED_PLAYER_STATS.size + COUNTED_PLAYER_STATS.indexOf(stat)].toInt()
    }

    companion object {
        fun inPeriod(minute: Double, p: Period) = when (p) {
            Period.FULL -> true
            Period.FIRST -> minute < 45
            Period.SECOND -> minute >= 45
        }
    }
}

/** Equally weighted simulated matches. */
class Simulation(
    val matches: List<SimMatch>,
    /** False when the half-time split is unknown (live, 2nd half): skip [Selection.usesHalves]. */
    val halvesKnown: Boolean,
    val live: Boolean,
) {
    fun probabilityOf(predicate: (SimMatch) -> Boolean): Double = matches.count(predicate).toDouble() / matches.size

    /**
     * Probability that [selection] wins, ignoring voided outcomes (a returned
     * stake is neither a hit nor a miss) — including matches where a player
     * the bet is about didn't play. Multi-leg selections average their legs.
     * Null if the selection can never settle.
     */
    fun probabilityOf(selection: Selection): Double? {
        val refs = selection.players
        val perLeg = selection.legs.map { leg ->
            var win = 0
            var lose = 0
            for (m in matches) {
                if (refs.isNotEmpty() && refs.any { !m.played(it) }) continue
                when (leg(m)) {
                    Res.WIN -> win++
                    Res.LOSE -> lose++
                    Res.VOID -> {}
                }
            }
            if (win + lose == 0) return null
            win.toDouble() / (win + lose)
        }
        return perLeg.average()
    }
}
