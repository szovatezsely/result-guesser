package io.resultguesser.stats

import io.resultguesser.cache.TtlCache
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Reads team form, head-to-head and full box scores from ESPN's public site
 * API (no key needed). It covers most leagues and national-team
 * competitions, and every finished match comes with
 * team statistics (corners, cards, fouls, offsides, shots, …), minute-stamped
 * goals and cards, and per-player lines.
 *
 * Teams are found by name search, then disambiguated by the fixture itself:
 * the home candidate whose schedule contains a match against the away side
 * around now wins (so "Netherlands" doesn't pick the women's or U21 team).
 */
class EspnClient(
    private val lastN: Int = 5,
    private val h2hN: Int = 5,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val resolver = TeamNameResolver()
    private val json = Json { ignoreUnknownKeys = true }

    private val client = HttpClient(CIO) {
        install(ContentEncoding) { gzip(); deflate() }
        install(HttpTimeout) { requestTimeoutMillis = 20_000 }
        // ESPN rejects library default agents ("ktor-client"); identify the app with a contact URL instead.
        install(UserAgent) { agent = "result-guesser/0.1 (+https://github.com/szovatezsely/result-guesser)" }
        expectSuccess = false
    }

    /** Be polite: at most a few requests in flight. */
    private val permits = Semaphore(4)

    private val searchCache = TtlCache<String, List<Candidate>>(ttlMillis = 24 * 60 * 60 * 1000)
    private val scheduleCache = TtlCache<String, List<ScheduleEvent>>(ttlMillis = 30 * 60 * 1000)
    private val summaryCache = TtlCache<String, MatchRecord>(ttlMillis = 24 * 60 * 60 * 1000)
    private val rosterCache = TtlCache<String, List<SquadPlayer>>(ttlMillis = 24 * 60 * 60 * 1000)

    private data class Candidate(val id: String, val name: String)

    private data class ScheduleEvent(
        val id: String,
        val date: Instant,
        val league: String,
        val homeId: String,
        val awayId: String,
        val homeName: String,
        val awayName: String,
        val completed: Boolean,
        val season: Int?,
    ) {
        fun opponentOf(teamId: String) = if (homeId == teamId) awayId to awayName else homeId to homeName
    }

    /** Recent form, head-to-head and squads for a TippmixPRO fixture (Hungarian team names), or null if not found. */
    suspend fun fixtureData(homeHungarian: String, awayHungarian: String): FixtureData? = coroutineScope {
        val homeEn = resolver.toEnglish(homeHungarian)
        val awayEn = resolver.toEnglish(awayHungarian)
        val homeCands = search(homeEn)
        val awayCands = search(awayEn)
        if (homeCands.isEmpty() || awayCands.isEmpty()) {
            log.info("ESPN: no team found for '{}' ({}) or '{}' ({})", homeEn, homeCands.size, awayEn, awayCands.size)
            return@coroutineScope null
        }

        val (homeId, awayId, fixture) = findFixture(homeCands, awayCands, awayEn)
            ?: Triple(homeCands.first().id, awayCands.first().id, null)
        val homeName = homeCands.firstOrNull { it.id == homeId }?.name ?: homeEn
        val awayName = awayCands.firstOrNull { it.id == awayId }?.name ?: fixture?.opponentOf(homeId)?.second ?: awayEn
        log.info("ESPN: {} -> {} ({}), {} -> {} ({}), fixture {}", homeHungarian, homeName, homeId, awayHungarian, awayName, awayId, fixture?.id)

        val homeRecent = async { recentMatches(homeId) }
        val awayRecent = async { recentMatches(awayId) }
        val h2h = async { headToHead(homeId, awayId) }
        val league = fixture?.league
        val homeSquad = async { squad(league, homeId, homeRecent.await()) }
        val awaySquad = async { squad(league, awayId, awayRecent.await()) }

        FixtureData(
            home = TeamData(homeId, homeName, homeRecent.await(), homeSquad.await()),
            away = TeamData(awayId, awayName, awayRecent.await(), awaySquad.await()),
            headToHead = h2h.await(),
        )
    }

    // ---- team identification ----

    private suspend fun search(query: String): List<Candidate> {
        val key = TeamNameResolver.normalize(query)
        searchCache.get(key)?.let { return it }
        val q = URLEncoder.encode(query, Charsets.UTF_8)
        val root = getJson("https://site.web.api.espn.com/apis/common/v3/search?query=$q&type=team&sport=soccer&limit=8") ?: return emptyList()
        val all = root.arr("items").mapNotNull { it.obj() }.mapNotNull { item ->
            val id = item.str("id") ?: return@mapNotNull null
            Candidate(id, item.str("displayName") ?: return@mapNotNull null)
        }
        // Senior teams first: youth/reserve sides only if the query asks for them.
        val youth = Regex("""\b(u\d{2}|ii|iii|b|reserves?|jong|women|femenino|feminine|youth)\b""")
        val ranked = all
            .filter { youth.containsMatchIn(it.name.lowercase()) == youth.containsMatchIn(query.lowercase()) }
            .sortedBy { nameDistance(it.name, query) }
            .ifEmpty { all }
        searchCache.put(key, ranked)
        return ranked
    }

    /** 0 = same name, 1 = one contains the other, 2 = otherwise (ESPN's own ranking breaks ties). */
    private fun nameDistance(name: String, query: String): Int {
        val a = TeamNameResolver.normalize(name)
        val b = TeamNameResolver.normalize(query)
        return when {
            a == b -> 0
            a.contains(b) || b.contains(a) -> 1
            else -> 2
        }
    }

    private suspend fun findFixture(
        homeCands: List<Candidate>,
        awayCands: List<Candidate>,
        awayEnglish: String,
    ): Triple<String, String, ScheduleEvent?>? {
        val awayIds = awayCands.map { it.id }.toSet()
        val now = Instant.now()
        for (home in homeCands.take(3)) {
            val events = schedule(home.id, fixtures = true) + schedule(home.id, fixtures = false)
            val match = events
                .filter { abs(ChronoUnit.HOURS.between(now, it.date)) <= 48 }
                .firstOrNull { e ->
                    val (oppId, oppName) = e.opponentOf(home.id)
                    oppId in awayIds || nameDistance(oppName, awayEnglish) <= 1
                }
            if (match != null) return Triple(home.id, match.opponentOf(home.id).first, match)
        }
        return null
    }

    // ---- matches ----

    private suspend fun recentMatches(teamId: String): List<MatchRecord> {
        val current = schedule(teamId, fixtures = false)
        var finished = current.filter { it.completed }
        if (finished.size < lastN) {
            val season = current.mapNotNull { it.season }.maxOrNull()
            if (season != null) finished = finished + schedule(teamId, fixtures = false, season = season - 1).filter { it.completed }
        }
        val picked = finished.distinctBy { it.id }.sortedByDescending { it.date }.take(lastN)
        return summaries(picked)
    }

    private suspend fun headToHead(homeId: String, awayId: String): List<MatchRecord> {
        val current = schedule(homeId, fixtures = false)
        val season = current.mapNotNull { it.season }.maxOrNull()
        val older = if (season == null) emptyList() else (1..3).flatMap { schedule(homeId, fixtures = false, season = season - it) }
        val meetings = (current + older)
            .filter { it.completed && (it.homeId == awayId || it.awayId == awayId) }
            .distinctBy { it.id }
            .sortedByDescending { it.date }
            .take(h2hN)
        return summaries(meetings)
    }

    private suspend fun summaries(events: List<ScheduleEvent>): List<MatchRecord> = coroutineScope {
        events.map { e -> async { summary(e) } }.awaitAll().filterNotNull()
    }

    private suspend fun squad(league: String?, teamId: String, recent: List<MatchRecord>): List<SquadPlayer> {
        val lg = league ?: recent.firstOrNull()?.league ?: return emptyList()
        val key = "$lg/$teamId"
        rosterCache.get(key)?.let { return it }
        val root = getJson("https://site.api.espn.com/apis/site/v2/sports/soccer/$lg/teams/$teamId/roster") ?: return emptyList()
        val squad = root.arr("athletes").mapNotNull { it.obj() }.mapNotNull { a ->
            SquadPlayer(
                id = a.str("id") ?: return@mapNotNull null,
                name = a.str("displayName") ?: return@mapNotNull null,
                position = a.obj("position")?.str("abbreviation"),
            )
        }
        rosterCache.put(key, squad)
        return squad
    }

    private suspend fun schedule(teamId: String, fixtures: Boolean, season: Int? = null): List<ScheduleEvent> {
        val params = listOfNotNull(season?.let { "season=$it" }, if (fixtures) "fixture=true" else null).joinToString("&")
        val url = "https://site.api.espn.com/apis/site/v2/sports/soccer/all/teams/$teamId/schedule" + if (params.isEmpty()) "" else "?$params"
        scheduleCache.get(url)?.let { return it }
        val root = getJson(url) ?: return emptyList()
        val defaultSeason = root.obj("requestedSeason")?.int("year") ?: root.obj("season")?.int("year")
        val events = root.arr("events").mapNotNull { it.obj() }.mapNotNull { e ->
            val comp = e.arr("competitions").firstOrNull()?.obj() ?: return@mapNotNull null
            val competitors = comp.arr("competitors").mapNotNull { it.obj() }
            val home = competitors.firstOrNull { it.str("homeAway") == "home" } ?: return@mapNotNull null
            val away = competitors.firstOrNull { it.str("homeAway") == "away" } ?: return@mapNotNull null
            ScheduleEvent(
                id = e.str("id") ?: return@mapNotNull null,
                date = parseInstant(e.str("date")) ?: return@mapNotNull null,
                league = e.obj("league")?.str("slug") ?: comp.obj("league")?.str("slug") ?: return@mapNotNull null,
                homeId = home.str("id") ?: home.obj("team")?.str("id") ?: return@mapNotNull null,
                awayId = away.str("id") ?: away.obj("team")?.str("id") ?: return@mapNotNull null,
                homeName = home.obj("team")?.str("displayName") ?: "?",
                awayName = away.obj("team")?.str("displayName") ?: "?",
                completed = comp.obj("status")?.obj("type")?.bool("completed") == true,
                season = e.obj("season")?.int("year") ?: defaultSeason,
            )
        }
        scheduleCache.put(url, events)
        return events
    }

    private suspend fun summary(e: ScheduleEvent): MatchRecord? {
        summaryCache.get(e.id)?.let { return it }
        val root = getJson("https://site.api.espn.com/apis/site/v2/sports/soccer/${e.league}/summary?event=${e.id}") ?: return null
        val record = runCatching { parseSummary(root, e) }
            .onFailure { log.warn("ESPN: could not parse summary {}: {}", e.id, it.message) }
            .getOrNull() ?: return null
        summaryCache.put(e.id, record)
        return record
    }

    private fun parseSummary(root: JsonObject, e: ScheduleEvent): MatchRecord {
        val header = root.obj("header")
        val comp = header?.arr("competitions")?.firstOrNull()?.obj()
        val competitors = comp?.arr("competitors").orEmpty().mapNotNull { it.obj() }
        fun side(homeAway: String) = competitors.firstOrNull { it.str("homeAway") == homeAway }
        fun score(c: JsonObject?) = c?.str("score")?.toIntOrNull()
        fun halfTime(c: JsonObject?) = c?.arr("linescores")?.firstOrNull()?.obj()?.str("displayValue")?.toDoubleOrNull()?.toInt()
        val home = side("home")
        val away = side("away")

        val teamStats = root.obj("boxscore")?.arr("teams").orEmpty().mapNotNull { it.obj() }.associate { t ->
            val id = t.obj("team")?.str("id") ?: ""
            id to t.arr("statistics").mapNotNull { it.obj() }.mapNotNull { s ->
                val v = s.str("displayValue")?.toDoubleOrNull() ?: return@mapNotNull null
                (s.str("name") ?: return@mapNotNull null) to v
            }.toMap()
        }

        val events = root.arr("keyEvents").mapNotNull { it.obj() }.mapNotNull { k ->
            val text = k.obj("type")?.str("text") ?: return@mapNotNull null
            val minute = (k.obj("clock")?.dbl("value") ?: return@mapNotNull null) / 60.0
            val teamId = k.obj("team")?.str("id") ?: return@mapNotNull null
            val people = k.arr("participants").mapNotNull { it.obj()?.obj("athlete")?.str("id") }
            when {
                text.contains("Own Goal", ignoreCase = true) ->
                    KeyEvent.Goal(minute, teamId, people.firstOrNull(), null, GoalMethod.OWN_GOAL)
                text.startsWith("Penalty - Scored", ignoreCase = true) ->
                    KeyEvent.Goal(minute, teamId, people.firstOrNull(), null, GoalMethod.PENALTY)
                text.startsWith("Goal", ignoreCase = true) -> KeyEvent.Goal(
                    minute, teamId, people.firstOrNull(), people.getOrNull(1),
                    when {
                        text.contains("Header", ignoreCase = true) -> GoalMethod.HEADER
                        text.contains("Free", ignoreCase = true) -> GoalMethod.FREE_KICK
                        else -> GoalMethod.SHOT
                    },
                )
                text.contains("Yellow Card", ignoreCase = true) -> KeyEvent.Card(minute, teamId, people.firstOrNull(), red = false)
                text.contains("Red Card", ignoreCase = true) -> KeyEvent.Card(minute, teamId, people.firstOrNull(), red = true)
                else -> null
            }
        }

        val players = root.arr("rosters").mapNotNull { it.obj() }.associate { r ->
            val id = r.obj("team")?.str("id") ?: ""
            id to r.arr("roster").mapNotNull { it.obj() }.mapNotNull { p ->
                val athlete = p.obj("athlete") ?: return@mapNotNull null
                val stats = p.arr("stats").mapNotNull { it.obj() }.mapNotNull { s ->
                    (s.str("name") ?: return@mapNotNull null) to (s.dbl("value") ?: return@mapNotNull null)
                }.toMap()
                PlayerLine(
                    id = athlete.str("id") ?: return@mapNotNull null,
                    name = athlete.str("displayName") ?: return@mapNotNull null,
                    position = p.obj("position")?.str("abbreviation"),
                    appeared = p.bool("starter") == true || p.bool("subbedIn") == true || (stats["appearances"] ?: 0.0) > 0,
                    started = p.bool("starter") == true,
                    stats = stats,
                )
            }
        }

        return MatchRecord(
            id = e.id,
            date = e.date.toString().take(10),
            league = e.league,
            competition = header?.obj("league")?.str("name"),
            homeId = e.homeId,
            awayId = e.awayId,
            homeName = e.homeName,
            awayName = e.awayName,
            homeGoals = score(home) ?: error("no home score"),
            awayGoals = score(away) ?: error("no away score"),
            homeHt = halfTime(home),
            awayHt = halfTime(away),
            teamStats = teamStats,
            events = events.sortedBy { it.minute },
            players = players,
        )
    }

    // ---- HTTP / JSON plumbing ----

    private suspend fun getJson(url: String): JsonObject? = permits.withPermit {
        try {
            val resp = client.get(url) { header("Accept", "application/json") }
            if (!resp.status.isSuccess()) {
                log.warn("ESPN {} for {}", resp.status, url)
                return@withPermit null
            }
            json.parseToJsonElement(resp.bodyAsText()) as? JsonObject
        } catch (ex: Exception) {
            log.warn("ESPN request failed for {}: {}", url, ex.message)
            null
        }
    }

    private fun parseInstant(s: String?): Instant? {
        if (s == null) return null
        val iso = if (Regex("""T\d{2}:\d{2}Z$""").containsMatchIn(s)) s.replace("Z", ":00Z") else s
        return runCatching { Instant.parse(iso) }.getOrNull()
    }
}

private fun JsonElement.obj(): JsonObject? = this as? JsonObject
private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.arr(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()
private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.int(key: String): Int? = str(key)?.toDoubleOrNull()?.toInt()
private fun JsonObject.dbl(key: String): Double? = str(key)?.toDoubleOrNull()
private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
