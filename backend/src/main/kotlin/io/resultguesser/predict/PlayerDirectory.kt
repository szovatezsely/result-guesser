package io.resultguesser.predict

/** A player of the simulation: team (0 home, 1 away) and index in that team's pool. */
data class PlayerRef(val team: Int, val index: Int)

/**
 * Finds players by the names TippmixPRO prints, which differ from ESPN's in
 * order ("simic nikola"), diacritics and completeness ("jesus bueno").
 *
 * Built in two passes: while [recording], names that match nobody but come
 * with a team hint are added as placeholders (positional priors only); the
 * simulation is then run with the complete pool.
 */
class PlayerDirectory(home: List<PlayerProfile>, away: List<PlayerProfile>) {
    private val pools = arrayOf(home.toMutableList(), away.toMutableList())
    var recording = true

    fun pool(team: Int): List<PlayerProfile> = pools[team]
    fun profile(ref: PlayerRef): PlayerProfile = pools[ref.team][ref.index]

    /** Resolves [name]; [teamHint] (0/1) restricts the search and is required for placeholders. */
    fun find(name: String, teamHint: Int?): PlayerRef? {
        val key = normalizeName(name)
        if (key.isEmpty()) return null
        val teams = if (teamHint != null) listOf(teamHint) else listOf(0, 1)
        var best: PlayerRef? = null
        var bestScore = 0
        for (t in teams) pools[t].forEachIndexed { i, p ->
            val s = similarity(key, p.key)
            if (s > bestScore) { bestScore = s; best = PlayerRef(t, i) }
        }
        if (best != null) return best
        if (recording && teamHint != null) {
            pools[teamHint] += PlayerProfile.placeholder(name, teamHint)
            return PlayerRef(teamHint, pools[teamHint].lastIndex)
        }
        return null
    }

    companion object {
        /** 3 = same tokens, 2 = one's tokens contained in the other's, 1 = same surname + initial, 0 = no match. */
        fun similarity(a: String, b: String): Int {
            val ta = a.split(' ').filter { it.isNotEmpty() }
            val tb = b.split(' ').filter { it.isNotEmpty() }
            if (ta.isEmpty() || tb.isEmpty()) return 0
            val sa = ta.toSet()
            val sb = tb.toSet()
            if (sa == sb) return 3
            val (small, large) = if (sa.size <= sb.size) sa to sb else sb to sa
            if (large.containsAll(small) && small.any { it.length > 2 }) return 2
            val lastA = ta.last()
            val lastB = tb.last()
            if (lastA == lastB && ta.first().first() == tb.first().first()) return 1
            // Reversed order with one side abbreviated, e.g. "simic nikola" vs "nikola simic jr".
            if (ta.first() == lastB && tb.first() == lastA) return 2
            return 0
        }
    }
}
