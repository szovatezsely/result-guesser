package io.adroit.resultguesser.routes

import io.adroit.resultguesser.predict.PoissonModel
import io.adroit.resultguesser.predict.Recommender
import io.adroit.resultguesser.predict.ScoredSelection
import io.adroit.resultguesser.scraper.PopularMatch
import io.adroit.resultguesser.scraper.TippmixScraper
import io.adroit.resultguesser.stats.FootballDataClient
import io.adroit.resultguesser.stats.TeamForm
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

@Serializable
data class ExpectedGoalsDto(val home: Double, val away: Double)

@Serializable
data class RecommendationDto(
    val marketTitle: String,
    val selection: String,
    val odds: Double,
    val modelProbability: Double,
    val impliedProbability: Double,
    val valueEdge: Double,
    val rationale: String,
)

@Serializable
data class RecommendationResponse(
    val match: PopularMatch,
    val insufficientData: Boolean,
    val message: String? = null,
    val homeForm: TeamForm? = null,
    val awayForm: TeamForm? = null,
    val expectedGoals: ExpectedGoalsDto? = null,
    val recommendation: RecommendationDto? = null,
    val topMarkets: List<ScoredSelection> = emptyList(),
)

/** Minimum model probability for a selection to count as "likely" enough to recommend. */
private const val LIKELY_THRESHOLD = 0.30

fun Route.apiRoutes(
    scraper: TippmixScraper,
    stats: FootballDataClient,
) {
    route("/api") {

        get("/matches") {
            val matches = withContext(Dispatchers.IO) { scraper.fetchPopularMatches() }
            call.respond(matches)
        }

        get("/matches/{id}/recommendation") {
            val id = call.parameters["id"]
            if (id.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing id"))
                return@get
            }

            val matches = withContext(Dispatchers.IO) { scraper.fetchPopularMatches() }
            val match = matches.find { it.id == id }
            if (match == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "match not found or no longer popular"))
                return@get
            }

            val href = match.href
            if (href == null) {
                call.respond(RecommendationResponse(match, insufficientData = true, message = "Nincs elérhető esemény oldal."))
                return@get
            }

            val markets = withContext(Dispatchers.IO) { scraper.fetchEventMarkets(href) }
            val homeForm = stats.getTeamForm(match.homeTeam)
            val awayForm = stats.getTeamForm(match.awayTeam)

            if (homeForm == null || awayForm == null) {
                call.respond(
                    RecommendationResponse(
                        match = match,
                        insufficientData = true,
                        message = "Nincs elég friss statisztika a csapatok elemzéséhez (a football-data.org ingyenes csomagja nem fedi le mindkét csapatot).",
                        homeForm = homeForm,
                        awayForm = awayForm,
                    ),
                )
                return@get
            }

            val eg = PoissonModel.expectedGoals(homeForm, awayForm)
            val matrix = PoissonModel.scoreMatrix(homeForm, awayForm)
            val ranked = Recommender.rank(markets, matrix, match.homeTeam, match.awayTeam)

            if (ranked.isEmpty()) {
                call.respond(
                    RecommendationResponse(
                        match = match,
                        insufficientData = true,
                        message = "Nem sikerült értelmezhető fogadási piacot kinyerni az eseményhez.",
                        homeForm = homeForm,
                        awayForm = awayForm,
                        expectedGoals = ExpectedGoalsDto(eg.home, eg.away),
                    ),
                )
                return@get
            }

            // Prefer the best-value selection among reasonably likely ones;
            // fall back to the single most probable selection.
            val pick = ranked.firstOrNull { it.modelProbability >= LIKELY_THRESHOLD }
                ?: ranked.maxByOrNull { it.modelProbability }!!

            val rationale = buildRationale(match, eg, pick)

            call.respond(
                RecommendationResponse(
                    match = match,
                    insufficientData = false,
                    homeForm = homeForm,
                    awayForm = awayForm,
                    expectedGoals = ExpectedGoalsDto(eg.home, eg.away),
                    recommendation = RecommendationDto(
                        marketTitle = pick.marketTitle,
                        selection = pick.selection,
                        odds = pick.odds,
                        modelProbability = pick.modelProbability,
                        impliedProbability = pick.impliedProbability,
                        valueEdge = pick.valueEdge,
                        rationale = rationale,
                    ),
                    topMarkets = ranked.take(8),
                ),
            )
        }
    }
}

private fun pct(x: Double) = (x * 100).roundToInt()

private fun buildRationale(
    match: PopularMatch,
    eg: PoissonModel.ExpectedGoals,
    pick: ScoredSelection,
): String {
    val lh = (eg.home * 100).roundToInt() / 100.0
    val la = (eg.away * 100).roundToInt() / 100.0
    val edgeSign = if (pick.valueEdge >= 0) "+" else ""
    return "A modell szerint várható gólok: ${match.homeTeam} $lh – ${match.awayTeam} $la. " +
        "A(z) „${pick.selection}\" kimenetel modell szerinti valószínűsége ${pct(pick.modelProbability)}%, " +
        "míg az odds (${pick.odds}) által beárazott valószínűség ${pct(pick.impliedProbability)}%. " +
        "Ez $edgeSign${pct(pick.valueEdge)}% értékelőnyt jelent."
}
