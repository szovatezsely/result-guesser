package io.resultguesser.analysis

import io.resultguesser.i18n.ParseContext
import io.resultguesser.predict.Basis
import io.resultguesser.predict.Evaluation
import io.resultguesser.predict.PlayerDirectory
import io.resultguesser.predict.PoissonModel
import io.resultguesser.predict.Recommender
import io.resultguesser.predict.Simulator
import io.resultguesser.predict.Stat
import io.resultguesser.predict.TeamProfile
import io.resultguesser.predict.playerPool
import io.resultguesser.predict.rateOf
import io.resultguesser.scraper.LiveState
import io.resultguesser.scraper.Market
import io.resultguesser.stats.FixtureData
import io.resultguesser.stats.H2hMatch
import io.resultguesser.stats.HeadToHead
import io.resultguesser.stats.RecentMatch
import io.resultguesser.stats.StatLine
import io.resultguesser.stats.TeamData
import io.resultguesser.stats.TeamForm

/** Result of analysing one fixture. */
class Analysis(
    val homeForm: TeamForm,
    val awayForm: TeamForm,
    val headToHead: HeadToHead?,
    val expectedGoals: PoissonModel.ExpectedGoals,
    val evaluation: Evaluation,
)

/**
 * Turns ESPN data + scraped markets into verdicts: per-team rates for every
 * statistic (own production blended with what the opponent allows, goals also
 * with home advantage and head-to-head), the players of both squads, a
 * seeded simulation, and a ✅/❌ for every option.
 */
object Analyzer {

    /** Statistics shown on the team cards, with their Hungarian labels. */
    private val DISPLAYED = listOf(
        Stat.CORNERS to "Szöglet",
        Stat.YELLOW to "Sárga lap",
        Stat.FOULS to "Szabálytalanság",
        Stat.OFFSIDES to "Les",
        Stat.SHOTS to "Lövés",
        Stat.SHOTS_ON_TARGET to "Kaput eltaláló",
    )

    fun analyze(
        fixture: FixtureData,
        markets: List<Market>,
        homeTeam: String,
        awayTeam: String,
        live: LiveState?,
        seed: Long,
        threshold: Double,
    ): Analysis {
        val homeProfile = TeamProfile(fixture.home)
        val awayProfile = TeamProfile(fixture.away)
        val homeForm = form(fixture.home, homeProfile)
        val awayForm = form(fixture.away, awayProfile)
        val h2h = headToHead(fixture)
        val eg = PoissonModel.expectedGoals(homeForm, awayForm, h2h)

        val rates = listOf(homeProfile to awayProfile, awayProfile to homeProfile).map { (team, opp) ->
            Stat.entries.filter { it != Stat.GOALS }.associateWith { rateOf(it, team, opp) }
        }
        val basis = Stat.entries.associateWith { stat ->
            if (stat == Stat.GOALS || rates.all { it[stat]?.basis == Basis.DATA }) Basis.DATA else Basis.BASELINE
        }
        val numericRates = rates.mapIndexed { i, r ->
            r.mapValues { it.value.value } + (Stat.GOALS to if (i == 0) eg.home else eg.away)
        }

        val players = PlayerDirectory(playerPool(0, fixture.home), playerPool(1, fixture.away))
        Recommender.registerPlayers(markets, homeTeam, awayTeam, players)
        // Player priors follow team strength: a striker of a side scoring 2.5 a game gets more than the average one.
        for (team in 0..1) {
            val scale = Stat.entries.associateWith { (numericRates[team][it] ?: it.baseline) / it.baseline }
            players.pool(team).forEach { it.priorScale = scale }
        }
        val sim = Simulator.run(numericRates, players, live, seed)
        val context = ParseContext(
            players = players,
            liveGoals = live?.let { it.homeGoals + it.awayGoals },
            liveMinute = live?.minute?.toDouble(),
        )
        val evaluation = Recommender.evaluate(markets, sim, homeTeam, awayTeam, context, basis, threshold)
        return Analysis(homeForm, awayForm, h2h, eg, evaluation)
    }

    private fun form(team: TeamData, profile: TeamProfile): TeamForm {
        val recent = team.recent.map { m ->
            RecentMatch(
                date = m.date,
                competition = m.competition,
                opponent = if (m.isHome(team.id)) m.awayName else m.homeName,
                home = m.isHome(team.id),
                goalsFor = m.goalsFor(team.id),
                goalsAgainst = m.goalsAgainst(team.id),
            )
        }
        val n = recent.size.coerceAtLeast(1)
        return TeamForm(
            teamName = team.name,
            matches = recent.size,
            avgScored = recent.sumOf { it.goalsFor }.toDouble() / n,
            avgConceded = recent.sumOf { it.goalsAgainst }.toDouble() / n,
            recent = recent,
            stats = DISPLAYED.map { (stat, label) -> StatLine(label, profile.forAvg(stat), profile.againstAvg(stat)) },
        )
    }

    private fun headToHead(fixture: FixtureData): HeadToHead? {
        val meetings = fixture.headToHead
        if (meetings.isEmpty()) return null
        val homeId = fixture.home.id
        return HeadToHead(
            matches = meetings.map { H2hMatch(it.date, it.competition, it.homeName, it.awayName, it.homeGoals, it.awayGoals) },
            homeAvgGoals = meetings.sumOf { it.goalsFor(homeId) }.toDouble() / meetings.size,
            awayAvgGoals = meetings.sumOf { it.goalsAgainst(homeId) }.toDouble() / meetings.size,
        )
    }
}
