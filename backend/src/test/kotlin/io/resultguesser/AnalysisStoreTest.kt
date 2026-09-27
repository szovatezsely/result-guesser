package io.resultguesser

import io.resultguesser.analysis.AnalysisStore
import io.resultguesser.predict.Category
import io.resultguesser.predict.Verdict
import io.resultguesser.routes.AnalysisResponse
import io.resultguesser.scraper.PopularMatch
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnalysisStoreTest {

    @Test
    fun `saved analyses survive a restart`() {
        val dir = Files.createTempDirectory("analyses").toFile()
        val response = AnalysisResponse(
            match = PopularMatch("315", "Anglia", "Spanyolország"),
            insufficientData = false,
            verdicts = listOf(Verdict("Szögletszám - Rendes játékidő", "Több, mint 9,5", 1.9, 0.55, true, category = Category.CORNERS)),
            computedAt = "2026-09-27T10:00:00Z",
        )
        AnalysisStore(dir).put("315", response)

        // A fresh store (as after a restart) reads it back from disk.
        val reloaded = AnalysisStore(dir).get("315")
        assertEquals(response, reloaded)
        assertNull(AnalysisStore(dir).get("999"))
        dir.deleteRecursively()
    }
}
