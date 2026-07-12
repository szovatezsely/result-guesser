package io.adroit.resultguesser.i18n

import io.adroit.resultguesser.predict.GoalPredicate
import java.text.Normalizer

/**
 * Understands the Hungarian betting vocabulary used by TippmixPRO and turns a
 * scraped market title + outcome label into a [GoalPredicate] the Poisson model
 * can score. Markets we don't model (handicaps, exact score, corners, halves…)
 * parse to `null` and are simply ignored by the recommender.
 *
 * Note: on the event page the 1X2 outcomes are the *team names* (e.g. "Norvégia"
 * / "Anglia"), while on match cards they are "Hazai" / "Vendég" — both are handled.
 */
object Glossary {

    // Markets that describe something other than full-time total/result goals.
    private val UNSUPPORTED_MARKET_TOKENS = listOf(
        "félidő", "hendikep", "hendik", "pontos", "szöglet", "lap",
        "szett", "sarok", "büntető", "első gól", "melyik", "hosszabbítás",
        "tizenegyes", "kiállítás", "negyed", "sárga", "piros",
    )

    /** True if this market is about full-time goals/result and worth modelling. */
    fun isSupportedMarket(marketTitle: String): Boolean {
        val t = marketTitle.lowercase()
        return UNSUPPORTED_MARKET_TOKENS.none { t.contains(it) }
    }

    /**
     * Build a scoreline predicate for a selection. Combined selections joined by
     * " és " (and) — e.g. "Hazai és Több, mint 3,5" — are AND-ed together.
     * Returns null if any component can't be understood.
     */
    fun predicateFor(
        marketTitle: String,
        outcomeLabel: String,
        homeTeam: String,
        awayTeam: String,
    ): GoalPredicate? {
        val title = marketTitle.lowercase()
        val label = outcomeLabel.lowercase()
        val home = norm(homeTeam)
        val away = norm(awayTeam)

        // Line (e.g. 2,5 / 3.5) may live in the label or in the market title.
        val line = extractLine(label) ?: extractLine(title)

        val parts = label.split(" és ", ", és ").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val predicates = parts.map { part -> componentPredicate(part, line, home, away) ?: return null }

        return { h, a -> predicates.all { it(h, a) } }
    }

    private fun componentPredicate(part: String, line: Double?, home: String, away: String): GoalPredicate? {
        val p = norm(part)
        return when {
            // Over / Under total goals
            part.contains("több") || part.contains("felett") -> line?.let { l -> { h: Int, a: Int -> (h + a) > l } }
            part.contains("kevesebb") || part.contains("alatt") -> line?.let { l -> { h: Int, a: Int -> (h + a) < l } }

            // Double chance
            p == "1x" || p == "1 vagy x" -> { h, a -> h >= a }
            p == "x2" || p == "x vagy 2" -> { h, a -> a >= h }
            p == "12" || p == "1 vagy 2" -> { h, a -> h != a }

            // 1X2 result — either the generic word or the actual team name
            part.startsWith("hazai") || p == "1" || p == home -> { h, a -> h > a }
            part.startsWith("vendég") || p == "2" || p == away -> { h, a -> a > h }
            part.startsWith("döntetlen") || p == "x" -> { h, a -> h == a }

            // Both teams to score (BTTS). Unsupported markets are filtered upstream,
            // so within supported markets Igen/Nem reliably mean BTTS.
            p == "igen" -> { h, a -> h >= 1 && a >= 1 }
            p == "nem" -> { h, a -> h == 0 || a == 0 }

            else -> null
        }
    }

    /** Extracts a decimal line like "2,5" or "3.5" from text. */
    private fun extractLine(text: String): Double? {
        val m = Regex("""(\d+)[.,](\d+)""").find(text) ?: return null
        return "${m.groupValues[1]}.${m.groupValues[2]}".toDoubleOrNull()
    }

    /** Lowercase + strip diacritics for robust label/team comparison. */
    private fun norm(s: String): String {
        val d = Normalizer.normalize(s.trim().lowercase(), Normalizer.Form.NFD)
        return d.replace(Regex("\\p{Mn}+"), "")
    }
}
