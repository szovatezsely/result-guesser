package io.adroit.resultguesser.scraper

import kotlinx.serialization.Serializable

/** A single selectable outcome within a market, e.g. "Hazai" @ 3.90. */
@Serializable
data class Outcome(
    val label: String,
    val odds: Double,
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
)
