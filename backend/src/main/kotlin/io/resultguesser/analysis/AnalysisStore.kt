package io.resultguesser.analysis

import io.resultguesser.routes.AnalysisResponse
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps every finished analysis so opening a match again shows the earlier
 * result instead of re-running the 15–30 s calculation. Results live in
 * memory and as one JSON file per match under [dir], so they survive restarts;
 * only an explicit recalculation replaces them.
 */
class AnalysisStore(private val dir: File) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val memory = ConcurrentHashMap<String, AnalysisResponse>()

    init {
        dir.mkdirs()
    }

    fun get(matchId: String): AnalysisResponse? = memory[matchId] ?: load(matchId)?.also { memory[matchId] = it }

    fun put(matchId: String, response: AnalysisResponse) {
        memory[matchId] = response
        runCatching { file(matchId).writeText(json.encodeToString(AnalysisResponse.serializer(), response)) }
            .onFailure { log.warn("Could not save analysis {}: {}", matchId, it.message) }
    }

    private fun load(matchId: String): AnalysisResponse? {
        val f = file(matchId)
        if (!f.isFile) return null
        return runCatching { json.decodeFromString(AnalysisResponse.serializer(), f.readText()) }
            .onFailure { log.warn("Ignoring unreadable saved analysis {}: {}", matchId, it.message) }
            .getOrNull()
    }

    /** Match ids are numeric on TippmixPRO; anything else is stripped so the id can't escape [dir]. */
    private fun file(matchId: String) = File(dir, matchId.filter { it.isLetterOrDigit() } + ".json")
}
