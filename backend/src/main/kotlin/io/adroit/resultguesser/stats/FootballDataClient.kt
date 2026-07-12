package io.adroit.resultguesser.stats

import io.adroit.resultguesser.cache.TtlCache
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Reads recent finished matches from football-data.org (v4) and derives each
 * team's recent-form goal rates.
 *
 * The global `/v4/matches` endpoint rejects broad date ranges on the free tier
 * (HTTP 400), so instead we pull finished matches per competition
 * (`/v4/competitions/{code}/matches?status=FINISHED`) — no date-range limit —
 * and pool them. Everything is cached to stay within the free-tier rate limit
 * (10 req/min), and unknown/uncovered teams degrade gracefully to `null`.
 */
class FootballDataClient(
    private val apiKey: String,
    private val lastN: Int = 6,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val resolver = TeamNameResolver()

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
    }

    // Assembled cross-competition pool (30 min) + per-competition results (6 h).
    private val poolCache = TtlCache<String, List<FdMatch>>(ttlMillis = 30 * 60 * 1000)
    private val competitionCache = TtlCache<String, List<FdMatch>>(ttlMillis = 6 * 60 * 60 * 1000)

    suspend fun getTeamForm(hungarianName: String): TeamForm? {
        if (apiKey.isBlank()) {
            log.warn("FOOTBALL_DATA_API_KEY is not set; cannot compute team form")
            return null
        }
        val english = resolver.toEnglish(hungarianName)
        val target = TeamNameResolver.normalize(english)

        val pool = recentMatches()
        val relevant = pool.filter { m ->
            m.score?.fullTime?.home != null &&
                (matches(m.homeTeam?.name, target) || matches(m.awayTeam?.name, target))
        }.sortedByDescending { it.utcDate }.take(lastN)

        if (relevant.size < 2) {
            log.info("Insufficient recent matches for '{}' ({} found in pool of {})", english, relevant.size, pool.size)
            return null
        }

        var scored = 0
        var conceded = 0
        for (m in relevant) {
            val isHome = matches(m.homeTeam?.name, target)
            val ft = m.score!!.fullTime!!
            if (isHome) {
                scored += ft.home!!; conceded += ft.away!!
            } else {
                scored += ft.away!!; conceded += ft.home!!
            }
        }
        val n = relevant.size
        return TeamForm(
            teamName = english,
            matches = n,
            avgScored = scored.toDouble() / n,
            avgConceded = conceded.toDouble() / n,
        )
    }

    /** Finished matches pooled across the covered competitions. */
    private suspend fun recentMatches(): List<FdMatch> {
        poolCache.get("pool")?.let { return it }
        val all = mutableListOf<FdMatch>()
        for (code in COMPETITIONS) {
            val cached = competitionCache.get(code)
            if (cached != null) {
                all += cached
                continue
            }
            val fetched = fetchCompetitionMatches(code)
            if (fetched != null) {
                competitionCache.put(code, fetched)
                all += fetched
            }
        }
        log.info("Assembled pool of {} finished matches across {} competitions", all.size, COMPETITIONS.size)
        poolCache.put("pool", all)
        return all
    }

    /** Returns the competition's finished matches, or null on error (so it retries later). */
    private suspend fun fetchCompetitionMatches(code: String): List<FdMatch>? {
        return try {
            val resp: HttpResponse = client.get("https://api.football-data.org/v4/competitions/$code/matches") {
                header("X-Auth-Token", apiKey)
                url { parameters.append("status", "FINISHED") }
            }
            if (!resp.status.isSuccess()) {
                // 403 = not in your plan, 429 = rate limited: skip and retry next cycle.
                log.warn("football-data.org {} for competition {}", resp.status, code)
                return null
            }
            val matches = resp.body<FdMatchesResponse>().matches
            log.debug("Competition {} → {} finished matches", code, matches.size)
            matches
        } catch (e: Exception) {
            log.warn("Failed to fetch competition {}: {}", code, e.message)
            null
        }
    }

    private fun matches(apiName: String?, normalizedTarget: String): Boolean {
        if (apiName == null) return false
        val n = TeamNameResolver.normalize(apiName)
        return n == normalizedTarget || n.contains(normalizedTarget) || normalizedTarget.contains(n)
    }

    companion object {
        // Free-tier competitions, most-relevant first (so they're fetched before
        // any rate-limit kicks in). Codes per football-data.org v4.
        private val COMPETITIONS = listOf(
            "WC",  // FIFA World Cup
            "CL",  // UEFA Champions League
            "PL",  // Premier League
            "PD",  // La Liga (Primera División)
            "BL1", // Bundesliga
            "SA",  // Serie A
            "FL1", // Ligue 1
            "DED", // Eredivisie
            "PPL", // Primeira Liga
            "ELC", // Championship
            "BSA", // Brazil Série A
            "EC",  // European Championship
        )
    }

    // ---- football-data.org v4 response subset ----

    @Serializable
    private data class FdMatchesResponse(val matches: List<FdMatch> = emptyList())

    @Serializable
    private data class FdMatch(
        val utcDate: String = "",
        val status: String = "",
        val homeTeam: FdTeam? = null,
        val awayTeam: FdTeam? = null,
        val score: FdScore? = null,
    )

    @Serializable
    private data class FdTeam(val id: Int? = null, val name: String? = null)

    @Serializable
    private data class FdScore(@SerialName("fullTime") val fullTime: FdGoals? = null)

    @Serializable
    private data class FdGoals(val home: Int? = null, val away: Int? = null)
}
