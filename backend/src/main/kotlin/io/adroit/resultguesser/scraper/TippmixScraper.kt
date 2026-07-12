package io.adroit.resultguesser.scraper

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.WaitUntilState
import io.adroit.resultguesser.cache.TtlCache
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Scrapes the embedded EveryMatrix sportsbook (sports2.tippmixpro.hu) with a
 * headless Chromium. All DOM selectors live here — this is the single place to
 * fix if TippmixPRO changes its markup.
 *
 * Playwright is single-threaded, so a lock serialises page access. Results are
 * cached briefly to keep responses fast and avoid hammering the site.
 */
class TippmixScraper(
    private val baseUrl: String = "https://sports2.tippmixpro.hu",
    private val lang: String = "hu",
) : AutoCloseable {

    private val log = LoggerFactory.getLogger(javaClass)
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = ReentrantLock()

    private val popularCache = TtlCache<String, List<PopularMatch>>(ttlMillis = 60 * 1000)
    private val marketsCache = TtlCache<String, List<Market>>(ttlMillis = 60 * 1000)

    private var playwright: Playwright? = null
    private var browser: Browser? = null

    private fun page(): Page {
        if (browser == null) {
            log.info("Launching headless Chromium…")
            playwright = Playwright.create()
            browser = playwright!!.chromium().launch(
                BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setArgs(listOf("--no-sandbox", "--disable-dev-shm-usage")),
            )
        }
        val ctx = browser!!.newContext(
            Browser.NewContextOptions()
                .setUserAgent(
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/126.0 Safari/537.36",
                )
                .setLocale("hu-HU"),
        )
        return ctx.newPage()
    }

    /** Popular ("Kiemelt") football matches from the sportsbook home page. */
    fun fetchPopularMatches(): List<PopularMatch> = popularCache.getOrPut("home") {
        lock.withLock {
            val page = page()
            try {
                page.navigate(
                    "$baseUrl/$lang",
                    Page.NavigateOptions().setTimeout(60_000.0).setWaitUntil(WaitUntilState.DOMCONTENTLOADED),
                )
                page.waitForSelector(".EventItem", Page.WaitForSelectorOptions().setTimeout(30_000.0))
                val raw = page.evaluate(POPULAR_JS) as String
                json.decodeFromString<List<PopularMatch>>(raw).also {
                    log.info("Scraped {} popular football matches", it.size)
                }
            } catch (e: Exception) {
                log.error("Failed to scrape popular matches: {}", e.message)
                emptyList()
            } finally {
                page.context().close()
            }
        }
    }

    /** All betting markets for a single event, navigated via its detail href. */
    fun fetchEventMarkets(href: String): List<Market> = marketsCache.getOrPut(href) {
        lock.withLock {
            val page = page()
            try {
                val url = if (href.startsWith("http")) href else "$baseUrl$href"
                page.navigate(
                    url,
                    Page.NavigateOptions().setTimeout(60_000.0).setWaitUntil(WaitUntilState.DOMCONTENTLOADED),
                )
                page.waitForSelector(".Market", Page.WaitForSelectorOptions().setTimeout(30_000.0))
                val raw = page.evaluate(MARKETS_JS) as String
                json.decodeFromString<EventMarkets>(raw).markets.also {
                    log.info("Scraped {} markets for {}", it.size, href)
                }
            } catch (e: Exception) {
                log.error("Failed to scrape markets for {}: {}", href, e.message)
                emptyList()
            } finally {
                page.context().close()
            }
        }
    }

    override fun close() {
        browser?.close()
        playwright?.close()
    }

    @Serializable
    private data class EventMarkets(val markets: List<Market> = emptyList())

    companion object {
        // Returns JSON: [{id, homeTeam, awayTeam, startTime, tournament, href, odds:{home,draw,away}}]
        private val POPULAR_JS = """
            () => {
              const dec = s => { if(!s) return null; const n=parseFloat(String(s).replace(/\s/g,'').replace(',','.')); return isFinite(n)?n:null; };
              const seen = new Set();
              const out = [];
              for (const item of document.querySelectorAll('.EventItem')) {
                const link = item.querySelector('a[href*="/esemenyek/"]');
                if(!link) continue;
                const href = link.getAttribute('href');
                if(!href || !href.includes('/labdarugas/')) continue;
                const id = href.split('/').filter(Boolean).pop();
                if(seen.has(id)) continue; seen.add(id);
                const home = item.querySelector('.Details__Participant--Home .Details__ParticipantName')?.textContent.trim();
                const away = item.querySelector('.Details__Participant--Away .Details__ParticipantName')?.textContent.trim();
                if(!home || !away) continue;
                const date = item.querySelector('.MatchTime__InfoPart--Date')?.textContent.trim() || '';
                const time = item.querySelector('.MatchTime__InfoPart--Time')?.textContent.trim() || '';
                const startTime = (date + ' ' + time).trim();
                const group = item.closest('.MatchListGroup');
                let tournament = null;
                if(group){ const th = group.querySelector('[class*="Caption"],[class*="Title"],[class*="Name"]'); if(th) tournament = th.textContent.trim(); }
                const odds = {home:null, draw:null, away:null};
                for (const b of item.querySelectorAll('.OddsButton')) {
                  const o = dec(b.querySelector('.OddsButton__Odds')?.textContent);
                  if(o==null) continue;
                  const lbl = (b.textContent||'').trim();
                  if(lbl.startsWith('Hazai')) odds.home = o;
                  else if(lbl.startsWith('Döntetlen')) odds.draw = o;
                  else if(lbl.startsWith('Vendég')) odds.away = o;
                }
                out.push({id, homeTeam:home, awayTeam:away, startTime, tournament, href, odds});
                if(out.length >= 24) break;
              }
              return JSON.stringify(out);
            }
        """.trimIndent()

        // Returns JSON: {markets:[{title, outcomes:[{label, odds}]}]}
        private val MARKETS_JS = """
            () => {
              const dec = s => { if(!s) return null; const n=parseFloat(String(s).replace(/\s/g,'').replace(',','.')); return isFinite(n)?n:null; };
              const markets = [];
              for (const mk of document.querySelectorAll('.Market')) {
                const t = mk.querySelector('.Market__CollapseText') || mk.querySelector('.Market__CollapseInfo');
                const title = t ? t.textContent.trim() : null;
                if(!title) continue;
                const outcomes = [];
                for (const b of mk.querySelectorAll('.OddsButton')) {
                  const oddsEl = b.querySelector('.OddsButton__Odds');
                  const odds = dec(oddsEl?.textContent);
                  if(odds==null) continue;
                  const full = (b.textContent||'').trim();
                  const oddsText = oddsEl ? oddsEl.textContent.trim() : '';
                  let label = full;
                  if(oddsText && full.endsWith(oddsText)) label = full.slice(0, full.length - oddsText.length).trim();
                  else if(oddsText) label = full.split(oddsText).join('').trim();
                  if(!label) continue;
                  outcomes.push({label, odds});
                }
                if(outcomes.length) markets.push({title, outcomes});
              }
              return JSON.stringify({markets});
            }
        """.trimIndent()
    }
}
