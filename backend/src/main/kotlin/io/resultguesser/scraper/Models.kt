package io.resultguesser.scraper

import kotlinx.serialization.Serializable

/** A single selectable outcome within a market, e.g. "Hazai" @ 3.90. */
@Serializable
data class Outcome(
    val label: String,
    val odds: Double,
    /** Header of the column the option sits in (e.g. the team name above a player list), if any. */
    val column: String? = null,
)

/** A betting market (a group of outcomes), e.g. "1X2 - Rendes játékidő". */
@Serializable
data class Market(
    val title: String,
    val outcomes: List<Outcome>,
)

/** Quick 1X2 odds shown on a match card in the popular list. */
@Serializable
data class MatchOdds(
    val home: Double? = null,
    val draw: Double? = null,
    val away: Double? = null,
)

/** Current state of an in-play match, as shown on its card. */
@Serializable
data class LiveState(
    val homeGoals: Int,
    val awayGoals: Int,
    /** Elapsed minute (e.g. 21 for "21'"), if shown. */
    val minute: Int? = null,
    /** Raw period label, e.g. "1. félidő", "2. félidő", "Félidő". */
    val period: String? = null,
)

/** A popular ("Kiemelt") match as scraped from the sportsbook home page. */
@Serializable
data class PopularMatch(
    val id: String,
    val homeTeam: String,
    val awayTeam: String,
    val startTime: String? = null,
    val tournament: String? = null,
    val odds: MatchOdds = MatchOdds(),
    /** Relative href of the event detail page, used to scrape full markets. */
    val href: String? = null,
    /** Non-null while the match is in play (live cards link to `/elo-esemenyek/`). */
    val live: LiveState? = null,
)

/** Everything scraped from an event page in one load, so odds and live score agree. */
@Serializable
data class EventMarkets(
    val markets: List<Market> = emptyList(),
    /** Current score/minute read from the event page (in-play events only). */
    val live: LiveState? = null,
)
