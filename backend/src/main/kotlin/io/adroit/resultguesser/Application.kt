package io.adroit.resultguesser

import io.adroit.resultguesser.routes.apiRoutes
import io.adroit.resultguesser.scraper.TippmixScraper
import io.adroit.resultguesser.stats.FootballDataClient
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
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module).start(wait = true)
}

fun Application.module() {
    val log = LoggerFactory.getLogger("Application")

    val apiKey = System.getenv("FOOTBALL_DATA_API_KEY") ?: ""
    if (apiKey.isBlank()) {
        log.warn("FOOTBALL_DATA_API_KEY not set — recommendations will report insufficient data.")
    }

    val scraper = TippmixScraper()
    val stats = FootballDataClient(apiKey)

    // Clean up the browser on shutdown.
    Runtime.getRuntime().addShutdownHook(Thread { runCatching { scraper.close() } })

    install(ContentNegotiation) {
        json(Json { prettyPrint = false; ignoreUnknownKeys = true })
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
        apiRoutes(scraper, stats)
    }
}
