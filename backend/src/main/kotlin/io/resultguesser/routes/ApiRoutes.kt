package io.resultguesser.routes

import io.resultguesser.analysis.AnalysisStore
import io.resultguesser.analysis.Analyzer
import io.resultguesser.predict.Recommender
import io.resultguesser.predict.SkippedOption
import io.resultguesser.predict.Verdict
import io.resultguesser.scraper.PopularMatch
import io.resultguesser.scraper.TippmixScraper
import io.resultguesser.stats.EspnClient
import io.resultguesser.stats.HeadToHead
import io.resultguesser.stats.TeamForm
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class ExpectedGoalsDto(val home: Double, val away: Double)

@Serializable
data class AnalysisResponse(
    val match: PopularMatch,
    val insufficientData: Boolean,
    val message: String? = null,
    val homeForm: TeamForm? = null,
    val awayForm: TeamForm? = null,
    val headToHead: HeadToHead? = null,
    val expectedGoals: ExpectedGoalsDto? = null,
    /** Probability an option needs for ✅. */
    val threshold: Double = Recommender.DEFAULT_THRESHOLD,
    /** Every judged option: ✅ first, then ❌, each ordered by odds (highest first). */
    val verdicts: List<Verdict> = emptyList(),
    /** Options that couldn't be judged, with the reason. */
    val skipped: List<SkippedOption> = emptyList(),
    /** When this analysis was calculated (ISO-8601, UTC). */
    val computedAt: String? = null,
    /** True when served from an earlier calculation rather than calculated for this request. */
    val cached: Boolean = false,
)

@Serializable
data class MatchListResponse(
    val matches: List<PopularMatch>,
    /** When the list was scraped from TippmixPRO (ISO-8601, UTC); null if never. */
    val updatedAt: String?,
    /** A scrape is running right now (e.g. the periodic background refresh). */
    val refreshing: Boolean,
    /** How often the list is refreshed in the background, in minutes. */
    val autoRefreshMinutes: Long,
)

fun Route.apiRoutes(
    scraper: TippmixScraper,
    stats: EspnClient,
    store: AnalysisStore,
    autoRefreshMinutes: Long,
) {
    route("/api") {

        // Served from the last scrape; only ?refresh=true (the "Meccsek frissítése" button) scrapes on demand.
        get("/matches") {
            val refresh = call.request.queryParameters["refresh"]?.toBooleanStrictOrNull() == true
            val list = withContext(Dispatchers.IO) {
                if (refresh) scraper.refreshPopularMatches() else scraper.popularMatches()
            }
            call.respond(
                MatchListResponse(
                    matches = list.matches,
                    updatedAt = list.updatedAt?.toString(),
                    refreshing = scraper.isRefreshing,
                    autoRefreshMinutes = autoRefreshMinutes,
                ),
            )
        }

        get("/matches/{id}/analysis") {
            val id = call.parameters["id"]
            if (id.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing id"))
                return@get
            }
            val threshold = call.request.queryParameters["threshold"]?.toDoubleOrNull()?.coerceIn(0.05, 0.95)
                ?: Recommender.DEFAULT_THRESHOLD

            // A match analysed before is served as calculated then; only an explicit refresh recalculates it.
            val refresh = call.request.queryParameters["refresh"]?.toBooleanStrictOrNull() == true
            if (!refresh) {
                store.get(id)?.let { saved ->
                    call.respond(saved.withThreshold(threshold).copy(cached = true))
                    return@get
                }
            }

            val listed = withContext(Dispatchers.IO) { scraper.findPopularMatch(id) }
            if (listed == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "match not found or no longer popular"))
                return@get
            }

            val href = listed.href
            if (href == null) {
                call.respond(AnalysisResponse(listed, insufficientData = true, message = "Nincs elérhető esemény oldal."))
                return@get
            }

            // The browser scrape and the stats requests are independent — run them side by side.
            val (event, fixture) = coroutineScope {
                val event = async(Dispatchers.IO) { scraper.fetchEventMarkets(href) }
                val fixture = async { stats.fixtureData(listed.homeTeam, listed.awayTeam) }
                event.await() to fixture.await()
            }
            // Prefer the live state read together with the odds; the list card may be a minute old.
            val match = if (event.live != null) listed.copy(live = event.live) else listed

            if (fixture == null || fixture.home.recent.size < 2 || fixture.away.recent.size < 2) {
                call.respond(
                    AnalysisResponse(
                        match = match,
                        insufficientData = true,
                        message = "Nem található elég friss statisztika a csapatokról (ESPN).",
                    ),
                )
                return@get
            }

            val analysis = withContext(Dispatchers.Default) {
                Analyzer.analyze(fixture, event.markets, match.homeTeam, match.awayTeam, match.live, id.hashCode().toLong(), threshold)
            }
            val evaluation = analysis.evaluation
            val response = AnalysisResponse(
                    match = match,
                    insufficientData = evaluation.verdicts.isEmpty(),
                    message = if (evaluation.verdicts.isEmpty()) "Nem sikerült értelmezhető fogadási piacot kinyerni az eseményhez." else null,
                    homeForm = analysis.homeForm,
                    awayForm = analysis.awayForm,
                    headToHead = analysis.headToHead,
                    expectedGoals = ExpectedGoalsDto(analysis.expectedGoals.home, analysis.expectedGoals.away),
                    threshold = threshold,
                    verdicts = evaluation.verdicts,
                    skipped = evaluation.skipped,
                    computedAt = Instant.now().toString(),
                )
            // Keep only real results; a stats/markets failure should be retried on the next visit.
            if (!response.insufficientData) store.put(id, response)
            call.respond(response)
        }
    }
}

/** Re-judges stored verdicts against another ✅ threshold (probabilities don't change). */
private fun AnalysisResponse.withThreshold(t: Double): AnalysisResponse {
    if (t == threshold) return this
    val rejudged = verdicts.map { it.copy(happens = it.probability >= t) }
        .sortedWith(compareByDescending<Verdict> { it.happens }.thenByDescending { it.odds })
    return copy(threshold = t, verdicts = rejudged)
}
