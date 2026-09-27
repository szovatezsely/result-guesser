package io.resultguesser

import io.resultguesser.analysis.AnalysisStore
import io.resultguesser.routes.apiRoutes
import io.resultguesser.scraper.TippmixScraper
import io.resultguesser.stats.EspnClient
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module).start(wait = true)
}

fun Application.module() {
    val log = LoggerFactory.getLogger("Application")

    val scraper = TippmixScraper()
    val stats = EspnClient()
    val store = AnalysisStore(File(System.getenv("DATA_DIR") ?: "data", "analyses"))
    val listRefreshMinutes = System.getenv("LIST_REFRESH_MINUTES")?.toLongOrNull()?.coerceAtLeast(1) ?: 1

    // Scrape the popular list once at start-up, then keep it fresh in the background
    // (on its own browser), so neither the main page nor match analysis waits for it.
    launch(Dispatchers.IO) {
        while (isActive) {
            runCatching { scraper.refreshPopularMatches() }
                .onFailure { log.warn("Background refresh of the match list failed: {}", it.message) }
            delay(listRefreshMinutes * 60_000)
        }
    }

    // Clean up the browser on shutdown.
    Runtime.getRuntime().addShutdownHook(Thread { runCatching { scraper.close() } })

    install(ContentNegotiation) {
        json(Json { prettyPrint = false; ignoreUnknownKeys = true; encodeDefaults = true })
    }
    install(CallLogging)
    install(CORS) {
        anyHost()
        allowHeader(io.ktor.http.HttpHeaders.ContentType)
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            log.error("Unhandled error", cause)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (cause.message ?: "internal error")))
        }
    }

    routing {
        get("/health") { call.respond(mapOf("status" to "ok")) }
        apiRoutes(scraper, stats, store, listRefreshMinutes)
    }
}
