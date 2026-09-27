package io.resultguesser

import io.resultguesser.i18n.Glossary
import io.resultguesser.i18n.ParseContext
import io.resultguesser.predict.Game
import io.resultguesser.predict.Period
import io.resultguesser.predict.PlayerDirectory
import io.resultguesser.predict.PlayerProfile
import io.resultguesser.predict.PlayerStat
import io.resultguesser.predict.PoissonModel
import io.resultguesser.predict.Recommender
import io.resultguesser.predict.Res
import io.resultguesser.predict.Role
import io.resultguesser.predict.Selection
import io.resultguesser.predict.Simulation
import io.resultguesser.predict.Simulator
import io.resultguesser.predict.Stat
import io.resultguesser.scraper.LiveState
import io.resultguesser.scraper.Market
import io.resultguesser.scraper.Outcome
import io.resultguesser.stats.TeamForm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecommendationTest {

    private val home = "Anglia"
    private val away = "Spanyolország"

    private fun parse(title: String, label: String): Selection =
        assertNotNull(Glossary.parse(title, label, home, away), "should parse: $title / $label")

    /** Settles a single-leg selection on a full-time score split as (1st half, rest in 2nd half). */
    private fun Selection.on(h: Int, a: Int, h1: Int = 0, a1: Int = 0): Res = legs.single()(Game(h1, a1, h - h1, a - a1))

    private fun noPlayers() = PlayerDirectory(emptyList(), emptyList()).also { it.recording = false }

    /** Simulates with goal rates [hg]/[ag] and baseline values for every other statistic unless given in [extra]. */
    private fun simulate(
        hg: Double,
        ag: Double,
        players: PlayerDirectory = noPlayers(),
        live: LiveState? = null,
        extra: Map<Stat, Pair<Double, Double>> = emptyMap(),
    ): Simulation {
        val rates = listOf(hg, ag).mapIndexed { i, g ->
            Stat.entries.associateWith { s -> extra[s]?.let { if (i == 0) it.first else it.second } ?: s.baseline } + (Stat.GOALS to g)
        }
        return Simulator.run(rates, players, live, seed = 42)
    }

    @Test
    fun `simulation reproduces its goal rates`() {
        val sim = simulate(1.8, 1.1)
        assertEquals(1.8, sim.matches.map { it.home(Period.FULL) }.average(), 0.06)
        assertEquals(1.1, sim.matches.map { it.away(Period.FULL) }.average(), 0.06)
        val homeWin = sim.probabilityOf { it.home(Period.FULL) > it.away(Period.FULL) }
        val awayWin = sim.probabilityOf { it.away(Period.FULL) > it.home(Period.FULL) }
        assertTrue(homeWin > awayWin, "stronger home side should be favourite")
        // Same seed, same answer.
        assertEquals(homeWin, simulate(1.8, 1.1).probabilityOf { it.home(Period.FULL) > it.away(Period.FULL) })
    }

    @Test
    fun `parses result, double chance and team-name labels`() {
        assertEquals(Res.WIN, parse("1X2 - Rendes játékidő", "Anglia").on(2, 1))
        assertEquals(Res.WIN, parse("1X2 - Rendes játékidő", "Spanyolország").on(0, 1))
        assertEquals(Res.WIN, parse("Kétesély - Rendes játékidő", "Anglia vagy Döntetlen").on(1, 1))
        assertEquals(Res.LOSE, parse("Kétesély - Rendes játékidő", "Anglia vagy Döntetlen").on(0, 1))
        assertEquals(Res.VOID, parse("Döntetlennél a tét visszajár - Rendes játékidő", "Anglia").on(1, 1))
    }

    @Test
    fun `parses totals including Asian lines`() {
        assertEquals(Res.WIN, parse("Gólszám - Rendes játékidő", "Több, mint 2,5").on(2, 1))
        assertEquals(Res.LOSE, parse("Gólszám - Rendes játékidő", "Több, mint 2,5").on(1, 1))
        assertEquals(Res.VOID, parse("Gólszám - Rendes játékidő", "Kevesebb, mint 2").on(1, 1))
        assertEquals(2, parse("Gólszám - Rendes játékidő", "Több, mint 2,25").legs.size)
        assertEquals(Res.WIN, parse("Gólszám - Rendes játékidő", "2-3 Tartomány").on(2, 0))
        assertEquals(Res.WIN, parse("Gólszám - Rendes játékidő", "4 X vagy Több").on(3, 1))
        assertEquals(Res.WIN, parse("Gólszám - Rendes játékidő", "Páratlan").on(2, 1))
        assertEquals(Res.WIN, parse("Anglia gólszám - Rendes játékidő", "Anglia: Páros").on(2, 1))
        assertEquals(Res.LOSE, parse("Spanyolország gólszám - Rendes játékidő", "Több, mint 1,5").on(3, 1))
    }

    @Test
    fun `parses combos from the brief`() {
        val sel = parse("1X2 + Gólszám 3,5 - Rendes játékidő", "Hazai és Több, mint 3,5")
        assertEquals(Res.WIN, sel.on(4, 0))
        assertEquals(Res.LOSE, sel.on(2, 0))
        assertEquals(Res.LOSE, sel.on(2, 3))
        assertEquals(Res.WIN, parse("Kétesély + Mindkét csapat szerez gólt - Rendes játékidő", "Anglia vagy Döntelen és Igen").on(1, 1))
        assertEquals(Res.WIN, parse("Játékrész eredménye vagy mindkét csapat szerez gólt - Rendes játékidő", "Vendég  vagy Igen").on(1, 1))
        assertEquals(Res.WIN, parse("Játékrész eredménye + csapat góljainak száma - Rendes játékidő", "Anglia és Spanyolország Kevesebb, mint 1,5").on(2, 1))
    }

    @Test
    fun `parses handicaps, margins and exact scores`() {
        assertEquals(Res.WIN, parse("Ázsiai hendikep - Rendes játékidő", "Anglia -1,5").on(2, 0))
        assertEquals(Res.VOID, parse("Ázsiai hendikep - Rendes játékidő", "Spanyolország +1").on(2, 1))
        assertEquals(Res.WIN, parse("Hendikep - Rendes játékidő", "Döntetlen - (Anglia -1) 0:1").on(2, 1))
        assertEquals(Res.WIN, parse("Hendikep - Rendes játékidő", "Spanyolország (+1) 0:1").on(1, 1))
        assertEquals(Res.WIN, parse("Nyertes különbség - Rendes játékidő", "Spanyolország 3+").on(0, 3))
        assertEquals(Res.WIN, parse("Pontos eredmény - Rendes játékidő", "0:1").on(0, 1))
        assertEquals(Res.WIN, parse("Az eredmény a felsoroltak között található - Rendes játékidő", "Spanyolország 2-1, 3-1, 4-1").on(1, 2))
    }

    @Test
    fun `parses half-time markets`() {
        val htft = parse("Félidő/végeredmény - Rendes játékidő", "Döntetlen / Anglia")
        assertTrue(htft.usesHalves)
        assertEquals(Res.WIN, htft.on(2, 1, h1 = 1, a1 = 1))
        assertEquals(Res.LOSE, htft.on(2, 1, h1 = 1, a1 = 0))
        assertEquals(Res.WIN, parse("Gólszám - 1. félidő", "Kevesebb, mint 0,5").on(2, 1))
        assertEquals(Res.WIN, parse("Melyik félidőben lesz több gól? - Rendes játékidő", "2. félidő").on(3, 0, h1 = 1))
    }

    @Test
    fun `parses non-goal team markets`() {
        for ((title, label) in listOf(
            "Szögletszám - Rendes játékidő" to "Több, mint 9,5",
            "Anglia szögletszám - 1. félidő" to "Kevesebb, mint 2,5",
            "Szögletszám - Ázsiai hendikep - Rendes játékidő" to "Anglia -1,5",
            "Szöglet hendikep - Rendes játékidő" to "Döntetlen - (Anglia +1) 1:0",
            "Melyik csapat végez el több szögletet? - Rendes játékidő" to "Spanyolország",
            "Melyik csapat végez el előbb 5 szögletet? - Rendes játékidő" to "Egyik sem",
            "Melyik csapat végzi el az „X”. szögletet? - Rendes játékidő" to "Szöglet 1: Anglia",
            "Büntetőlap-szám - Rendes játékidő" to "Több, mint 3,5",
            "Spanyolország büntetőlapok száma - Rendes játékidő" to "0-1 Tartomány",
            "Szabálytalanságok száma - Rendes játékidő" to "Több, mint 22,5",
            "Lesszám - Rendes játékidő" to "Kevesebb, mint 3,5",
            "Kaput eltaláló gólszerzési kísérletek száma - Rendes játékidő" to "Több, mint 8,5",
            "Anglia kapura tartó gólszerzési kísérletek száma - Rendes játékidő" to "Több, mint 12,5",
            "Bedobások száma - Rendes játékidő" to "Több, mint 40,5",
            "Lesz 11-es? - Rendes játékidő" to "Igen",
            "Lesz kiállítás? - Rendes játékidő" to "Nem",
            "Anglia piros lap - Rendes játékidő" to "Anglia: Igen",
            "Mindkét csapat legalább 2 büntetőlapot kap? - Rendes játékidő" to "Igen",
            "Melyik csapat játékosa kapja az első büntetőlapot? - Rendes játékidő" to "Nincs",
            "Melyik csapat szerzi a(z) 2. gólt? - Rendes játékidő" to "Anglia",
            "Ki ér el először 2 gólt? - Rendes játékidő" to "Egyik sem",
            "A(z) 1. gól megszerzésének ideje - Rendes játékidő" to "10:00-19:59",
            "1. gól „X” perc előtt lesz? - Rendes játékidő" to "Igen 20",
            "Lesz gól az adott időszakaszban (perc):? - Rendes játékidő" to "Nem 0:00-14:59",
            "Az adott időszakasz eredménye (perc): 0:00-79:59 - Rendes játékidő" to "Döntetlen",
            "Gólszám 0,5 az adott időszakaszban (perc): - Rendes játékidő" to "Több, mint 15:00-29:59",
            "Anglia szerez 2 egymást követő gólt - Rendes játékidő" to "Anglia: Igen",
            "Hátrányból nyer - Rendes játékidő" to "Spanyolország",
            "1. gól megszerzésének módja - Rendes játékidő" to "Fejes",
            "1X2 + Melyik csapat szerzi a(z) 1. gólt - Rendes játékidő" to "Anglia és Gól 1: Spanyolország",
        )) {
            assertFalse(parse(title, label).liveOk, "$title should not be judged live")
        }
    }

    @Test
    fun `parses player markets through the directory`() {
        val players = PlayerDirectory(
            listOf(PlayerProfile("Harry Kane", 0, Role.FWD, 5, 5, 1.0, emptyMap())),
            listOf(PlayerProfile("Nikola Simić", 1, Role.DEF, 4, 4, 0.8, emptyMap())),
        ).also { it.recording = false }
        val ctx = ParseContext(players)
        fun player(title: String, label: String, column: String? = null) =
            assertNotNull(Glossary.parse(title, label, home, away, column, ctx), "should parse: $title / $label")
        assertEquals(0, player("Szerez gólt? - Rendes játékidő", "Harry Kane", "Anglia").players.single().team)
        assertEquals(1, player("Büntetőlapot kap? - Rendes játékidő", "simic nikola").players.single().team)
        player("2 vagy több gólt szerez - Rendes játékidő", "Harry Kane 2 vagy Több")
        player("Gólt szerez + 1X2 - Rendes játékidő", "Harry Kane és Anglia")
        player("Harry Kane kaput eltaláló gólszerzési kísérletek száma - Rendes játékidő", "Harry Kane: Több, mint 1,5")
        player("Simic Nikola elkövetett szabálytalanságok száma - Rendes játékidő", "Simic Nikola: Több, mint 0,5")
        assertNull(Glossary.parse("Szerez gólt? - Rendes játékidő", "Nobody Known", home, away, null, ctx))
    }

    @Test
    fun `player goals follow their scoring rates`() {
        val players = PlayerDirectory(
            listOf(
                PlayerProfile("Striker", 0, Role.FWD, 5, 5, 1.0, mapOf(PlayerStat.GOALS to 4.0)),
                PlayerProfile("Defender", 0, Role.DEF, 5, 5, 1.0, mapOf(PlayerStat.GOALS to 0.0)),
            ),
            emptyList(),
        ).also { it.recording = false }
        val sim = simulate(2.0, 1.0, players)
        val ctx = ParseContext(players)
        val striker = assertNotNull(Glossary.parse("Szerez gólt? - Rendes játékidő", "Striker", home, away, "Anglia", ctx))
        val defender = assertNotNull(Glossary.parse("Szerez gólt? - Rendes játékidő", "Defender", home, away, "Anglia", ctx))
        assertTrue(sim.probabilityOf(striker)!! > 0.5)
        assertTrue(sim.probabilityOf(defender)!! < 0.2)
    }

    @Test
    fun `corner rates drive corner verdicts`() {
        val sim = simulate(1.4, 1.2, extra = mapOf(Stat.CORNERS to (7.0 to 5.0)))
        assertTrue(sim.probabilityOf(parse("Szögletszám - Rendes játékidő", "Több, mint 9,5"))!! > 0.6)
        assertTrue(sim.probabilityOf(parse("Melyik csapat végez el több szögletet? - Rendes játékidő", "Anglia"))!! > 0.5)
        assertTrue(sim.probabilityOf(parse("Szögletszám - Rendes játékidő", "Több, mint 16,5"))!! < 0.2)
    }

    @Test
    fun `leaves unknown markets unevaluated`() {
        assertNull(Glossary.parse("Valami egészen más - Rendes játékidő", "Igen", home, away))
    }

    @Test
    fun `high-scoring teams back low total lines and reject high ones`() {
        // The brief's example: England averages 2 goals, Spain 3.
        val eg = PoissonModel.expectedGoals(TeamForm(home, 5, 2.0, 1.0), TeamForm(away, 5, 3.0, 1.0))
        val sim = simulate(eg.home, eg.away)
        val markets = listOf(
            Market(
                "Gólszám - Rendes játékidő",
                listOf(
                    Outcome("Több, mint 1,5", 1.20),
                    Outcome("Több, mint 2,5", 1.70),
                    Outcome("Több, mint 6,5", 12.0),
                ),
            ),
            Market("Valami egészen más - Rendes játékidő", listOf(Outcome("Igen", 1.9))),
        )
        val result = Recommender.evaluate(markets, sim, home, away)
        val byLabel = result.verdicts.associateBy { it.selection }
        assertTrue(byLabel.getValue("Több, mint 1,5").happens)
        assertTrue(byLabel.getValue("Több, mint 2,5").happens)
        assertFalse(byLabel.getValue("Több, mint 6,5").happens)
        // ✅ first, ordered by odds descending.
        assertEquals(listOf("Több, mint 2,5", "Több, mint 1,5", "Több, mint 6,5"), result.verdicts.map { it.selection })
        assertEquals(1, result.skipped.size)
    }

    @Test
    fun `live matches condition on the current score and remaining time`() {
        val eg = PoissonModel.expectedGoals(TeamForm(home, 5, 2.0, 1.0), TeamForm(away, 5, 2.0, 1.0))
        val late = simulate(eg.home, eg.away, live = LiveState(homeGoals = 0, awayGoals = 1, minute = 88, period = "2. félidő"))
        val over = parse("Gólszám - Rendes játékidő", "Több, mint 2,5")
        assertTrue(late.probabilityOf(over)!! < 0.05, "0-1 at 88' should rarely end with 3+ goals")
        assertFalse(late.halvesKnown)
        assertTrue(late.probabilityOf(parse("1X2 - Rendes játékidő", "Spanyolország"))!! > 0.8)
        // Corners so far are unknown live; the next goal is in the future, so it can be judged.
        assertFalse(parse("Szögletszám - Rendes játékidő", "Több, mint 9,5").liveOk)
        val next = Glossary.parse("Melyik csapat szerzi a(z) 2. gólt? - Rendes játékidő", "Anglia", home, away, null, ParseContext(liveGoals = 1))
        assertTrue(assertNotNull(next).liveOk)
    }
}
