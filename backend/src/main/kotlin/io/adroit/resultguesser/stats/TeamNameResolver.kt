package io.adroit.resultguesser.stats

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.text.Normalizer

/**
 * Maps the Hungarian team names shown on TippmixPRO to the English names
 * football-data.org uses. Backed by `resources/team-aliases.json`, with a
 * diacritics-insensitive fallback so partial coverage still works. Unknown
 * names are logged so the alias file can be grown over time.
 */
class TeamNameResolver {

    private val log = LoggerFactory.getLogger(javaClass)
    private val aliases: Map<String, String> = loadAliases()

    /** Returns the English name to query the stats API with. */
    fun toEnglish(hungarianName: String): String {
        val key = normalize(hungarianName)
        aliases[key]?.let { return it }
        log.info("No alias for team '{}' (normalized '{}'); using name as-is", hungarianName, key)
        return hungarianName
    }

    private fun loadAliases(): Map<String, String> {
        val stream = javaClass.classLoader.getResourceAsStream("team-aliases.json")
        if (stream == null) {
            log.warn("team-aliases.json not found on classpath")
            return emptyMap()
        }
        val text = stream.bufferedReader().use { it.readText() }
        val obj = Json.parseToJsonElement(text).jsonObject
        return obj.entries.associate { (hu, en) -> normalize(hu) to en.jsonPrimitive.content }
    }

    companion object {
        /** Lowercase + strip diacritics so "Malmö"/"Norvégia" match reliably. */
        fun normalize(s: String): String {
            val decomposed = Normalizer.normalize(s.trim().lowercase(), Normalizer.Form.NFD)
            return decomposed.replace(Regex("\\p{Mn}+"), "")
        }
    }
}
