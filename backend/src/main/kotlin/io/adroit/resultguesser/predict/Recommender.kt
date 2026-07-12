package io.adroit.resultguesser.predict

import io.adroit.resultguesser.i18n.Glossary
import io.adroit.resultguesser.scraper.Market
import kotlinx.serialization.Serializable

/** One scored betting selection: model probability vs. the bookmaker's odds. */
@Serializable
data class ScoredSelection(
    val marketTitle: String,
    val selection: String,
    val odds: Double,
    val modelProbability: Double,
    val impliedProbability: Double,
    /** valueEdge = modelProbability * odds - 1. Positive means model-favourable. */
    val valueEdge: Double,
)

object Recommender {

    /**
     * Scores every understood selection across the scraped markets and returns
     * them ranked by value edge (best first). Selections whose market or label
     * we can't model are skipped.
     */
    fun rank(
        markets: List<Market>,
        matrix: ScoreMatrix,
        homeTeam: String,
        awayTeam: String,
    ): List<ScoredSelection> {
        val scored = mutableListOf<ScoredSelection>()
        for (market in markets) {
            if (!Glossary.isSupportedMarket(market.title)) continue
            for (outcome in market.outcomes) {
                if (outcome.odds <= 1.01) continue
                val predicate = Glossary.predicateFor(market.title, outcome.label, homeTeam, awayTeam) ?: continue
                val modelProb = matrix.probabilityOf(predicate)
                if (modelProb <= 0.0) continue
                val implied = 1.0 / outcome.odds
                val edge = modelProb * outcome.odds - 1.0
                scored += ScoredSelection(
                    marketTitle = market.title,
                    selection = outcome.label,
                    odds = outcome.odds,
                    modelProbability = modelProb,
                    impliedProbability = implied,
                    valueEdge = edge,
                )
            }
        }
        return scored.sortedByDescending { it.valueEdge }
    }
}
