package io.adroit.resultguesser

import io.adroit.resultguesser.i18n.Glossary
import io.adroit.resultguesser.predict.PoissonModel
import io.adroit.resultguesser.predict.Recommender
import io.adroit.resultguesser.scraper.Market
import io.adroit.resultguesser.scraper.Outcome
import io.adroit.resultguesser.stats.TeamForm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RecommendationTest {

    private val home = "Norvégia"
    private val away = "Anglia"

    @Test
    fun `poisson probabilities form a valid distribution`() {
        val hf = TeamForm(home, 6, avgScored = 2.0, avgConceded = 1.0)
        val af = TeamForm(away, 6, avgScored = 1.5, avgConceded = 1.2)
        val matrix = PoissonModel.scoreMatrix(hf, af)

        val total = matrix.probabilityOf { _, _ -> true }
        assertTrue(total > 0.98, "probability mass should be ~1 but was $total")

        val homeWin = matrix.probabilityOf { h, a -> h > a }
        val draw = matrix.probabilityOf { h, a -> h == a }
        val awayWin = matrix.probabilityOf { h, a -> a > h }
        assertEquals(1.0, homeWin + draw + awayWin, 0.02)
        // Stronger home side should be favourite.
        assertTrue(homeWin > awayWin)
    }

    @Test
    fun `glossary parses the combo market from the brief`() {
        // "Hazai és Több, mint 3,5": home win AND total goals over 3.5
        val pred = Glossary.predicateFor("1x2 + Gólszám 3,5 - Rendes játékidő", "Hazai és Több, mint 3,5", home, away)
        assertNotNull(pred)
        assertTrue(pred(4, 0))   // home win, 4 goals > 3.5
        assertTrue(!pred(2, 0))  // home win but only 2 goals
        assertTrue(!pred(2, 3))  // 5 goals but away win
    }

    @Test
    fun `glossary maps team names to home and away for 1x2`() {
        val homePred = Glossary.predicateFor("1X2 - Rendes játékidő", "Norvégia", home, away)!!
        val awayPred = Glossary.predicateFor("1X2 - Rendes játékidő", "Anglia", home, away)!!
        assertTrue(homePred(2, 1))
        assertTrue(awayPred(1, 2))
    }

    @Test
    fun `glossary understands BTTS and rejects unsupported markets`() {
        val btts = Glossary.predicateFor("Mindkét csapat szerez gólt - Rendes játékidő", "Igen", home, away)!!
        assertTrue(btts(1, 1))
        assertTrue(!btts(2, 0))
        assertTrue(!Glossary.isSupportedMarket("1. félidő - Szögletek száma"))
    }

    @Test
    fun `recommender ranks selections by value edge`() {
        val hf = TeamForm(home, 6, avgScored = 2.5, avgConceded = 0.8)
        val af = TeamForm(away, 6, avgScored = 0.9, avgConceded = 1.8)
        val matrix = PoissonModel.scoreMatrix(hf, af)

        val markets = listOf(
            Market(
                "1X2 - Rendes játékidő",
                listOf(Outcome("Norvégia", 1.50), Outcome("Döntetlen", 4.0), Outcome("Anglia", 7.0)),
            ),
            Market(
                "Mindkét csapat szerez gólt - Rendes játékidő",
                listOf(Outcome("Igen", 2.0), Outcome("Nem", 1.8)),
            ),
        )

        val ranked = Recommender.rank(markets, matrix, home, away)
        assertTrue(ranked.isNotEmpty())
        // Sorted descending by edge
        for (i in 1 until ranked.size) {
            assertTrue(ranked[i - 1].valueEdge >= ranked[i].valueEdge)
        }
    }
}
