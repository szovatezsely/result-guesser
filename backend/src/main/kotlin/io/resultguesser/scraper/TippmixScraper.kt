package io.resultguesser.scraper

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.WaitUntilState
import io.resultguesser.cache.TtlCache
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
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
    private val marketsCache = TtlCache<String, EventMarkets>(ttlMillis = 60 * 1000)

    /** Every match seen in a popular list, so opening one doesn't re-scrape the list (≈10 s). */
    private val known = ConcurrentHashMap<String, PopularMatch>()

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
                page.waitForTimeout(LIVE_SETTLE_MS)
                val raw = page.evaluate(POPULAR_JS) as String
                json.decodeFromString<List<PopularMatch>>(raw).also {
                    log.info("Scraped {} popular football matches", it.size)
                    it.forEach { m -> known[m.id] = m }
                }
            } catch (e: Exception) {
                log.error("Failed to scrape popular matches: {}", e.message)
                emptyList()
            } finally {
                page.context().close()
            }
        }
    }

    /**
     * A popular match by id: from the last list scrape when it's known (its
     * live score is refreshed from the event page anyway), else a fresh scrape.
     */
    fun findPopularMatch(id: String): PopularMatch? = known[id] ?: fetchPopularMatches().find { it.id == id }

    /**
     * All betting markets for a single event, navigated via its detail href.
     * Uses the event's "Összes" (`/all`) tab: the default "Népszerű" tab only
     * renders odds for a handful of expanded markets, `/all` renders every one.
     *
     * For in-play events the score/minute is read from the same page load, so
     * it is consistent with the odds (the popular-list card can be a minute old).
     */
    fun fetchEventMarkets(href: String): EventMarkets = marketsCache.getOrPut(href) {
        lock.withLock {
            val page = page()
            try {
                val base = if (href.startsWith("http")) href else "$baseUrl$href"
                page.navigate(
                    "${base.trimEnd('/')}/all",
                    Page.NavigateOptions().setTimeout(60_000.0).setWaitUntil(WaitUntilState.DOMCONTENTLOADED),
                )
                page.waitForSelector(".Market", Page.WaitForSelectorOptions().setTimeout(30_000.0))
                page.waitForTimeout(LIVE_SETTLE_MS)
                // Markets stream in; wait until the count stops growing (max ~6 s).
                var last = -1
                for (i in 0 until 12) {
                    val count = (page.evaluate("() => document.querySelectorAll('.Market').length") as Number).toInt()
                    if (count == last) break
                    last = count
                    page.waitForTimeout(500.0)
                }
                val eventId = href.trimEnd('/').substringAfterLast('/')
                val raw = page.evaluate(MARKETS_JS, eventId) as String
                json.decodeFromString<EventMarkets>(raw).also {
                    log.info("Scraped {} markets for {} (live: {})", it.markets.size, href, it.live)
                }
            } catch (e: Exception) {
                log.error("Failed to scrape markets for {}: {}", href, e.message)
                EventMarkets()
            } finally {
                page.context().close()
            }
        }
    }

    override fun close() {
        browser?.close()
        playwright?.close()
    }

    companion object {
        /**
         * The first render shows a stale snapshot (live scores/odds can be many
         * minutes old); the websocket corrects it within ~1 s, so wait before reading.
         */
        private const val LIVE_SETTLE_MS = 2_500.0

        // JS helper: live state {homeGoals, awayGoals, minute, period} of an `.EventItem` card.
        // The total is the largest value per side across the score part groups.
        private val LIVE_OF_JS = """
            const liveOf = item => {
              const int = s => { const m = String(s||'').match(/\d+/); return m ? parseInt(m[0], 10) : null; };
              let hg = 0, ag = 0;
              for (const g of item.querySelectorAll('.Score__PartGroup')) {
                hg = Math.max(hg, int(g.querySelector('.Score__Part--Home')?.textContent) ?? 0);
                ag = Math.max(ag, int(g.querySelector('.Score__Part--Away')?.textContent) ?? 0);
              }
              return {
                homeGoals: hg, awayGoals: ag,
                minute: int(item.querySelector('.MatchTime__InfoPart--MatchTime')?.textContent),
                period: item.querySelector('.MatchTime__InfoPart--EventPartName')?.textContent.trim() || null,
              };
            };
        """.trimIndent()

        // Returns JSON: [{id, homeTeam, awayTeam, startTime, tournament, href, odds:{home,draw,away}, live}]
        // Pre-match cards link to /esemenyek/…, in-play cards to /elo-esemenyek/… — both are accepted.
        private val POPULAR_JS = """
            () => {
              const dec = s => { if(!s) return null; const n=parseFloat(String(s).replace(/\s/g,'').replace(',','.')); return isFinite(n)?n:null; };
              $LIVE_OF_JS
              const seen = new Set();
              const out = [];
              for (const item of document.querySelectorAll('.EventItem')) {
                const link = item.querySelector('a[href*="/esemenyek/"], a[href*="/elo-esemenyek/"]');
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
                const isLive = href.includes('/elo-esemenyek/') || item.classList.contains('EventItem--isLive');
                const live = isLive ? liveOf(item) : null;
                out.push({id, homeTeam:home, awayTeam:away, startTime, tournament, href, odds, live});
                if(out.length >= 24) break;
              }
              return JSON.stringify(out);
            }
        """.trimIndent()

        // Returns JSON: {markets:[{title, outcomes:[{label, odds}]}], live}. Argument: the event id.
        //
        // Many markets are tables: column headers (`.Market__Header`, e.g.
        // "Több, mint" / "Gólszám" / "Kevesebb, mint") and one row per line with
        // a row title (`.Market__OddsGroupTitle`, e.g. "2,5") whose buttons hold
        // only the odds. Labels are rebuilt from header + row title, e.g.
        // "Több, mint 2,5". With `Market--label-middle` the row title occupies
        // the middle header column; otherwise the first.
        private val MARKETS_JS = """
            (eventId) => {
              $LIVE_OF_JS
              const dec = s => { if(!s) return null; const n=parseFloat(String(s).replace(/\s/g,'').replace(',','.')); return isFinite(n)?n:null; };
              const clean = s => (s||'').replace(/\s+/g,' ').trim();
              const markets = [];
              for (const mk of document.querySelectorAll('.Market')) {
                const title = clean((mk.querySelector('.Market__CollapseText') || mk.querySelector('.Market__CollapseInfo'))?.textContent);
                if(!title) continue;
                const headers = [...mk.querySelectorAll('.Market__Header')].map(h => clean(h.textContent));
                const outcomes = [];
                [...mk.querySelectorAll('.Market__OddsGroup')].forEach((g, gi) => {
                  const rowTitle = clean(g.querySelector('.Market__OddsGroupTitle')?.textContent);
                  const items = [...g.querySelectorAll('.Market__OddsGroupItem')];
                  // Column-layout groups (e.g. player lists) are whole columns: group i sits under header i.
                  const columnGroup = g.className.includes('layout-column') ? (headers[gi] || '') : null;
                  const cols = headers.slice();
                  if(rowTitle && cols.length === items.length + 1) {
                    cols.splice(mk.className.includes('label-middle') ? Math.floor(cols.length / 2) : 0, 1);
                  }
                  items.forEach((it, i) => {
                    const b = it.querySelector('.OddsButton');
                    const oddsEl = b?.querySelector('.OddsButton__Odds');
                    const odds = dec(oddsEl?.textContent);
                    if(odds == null) return;
                    const col = columnGroup ?? (cols.length === items.length ? cols[i] : '');
                    let label = clean(b.textContent.replace(oddsEl.textContent, ''));
                    if(!label) label = clean([col, rowTitle].filter(Boolean).join(' '));
                    else {
                      // Bare handicap like "-1,5" under a team column → "Team -1,5".
                      if(col && /^[+-]?\d+([.,]\d+)?$/.test(label)) label = col + ' ' + label;
                      if(rowTitle && !label.includes(rowTitle)) label = label + ' ' + rowTitle;
                    }
                    if(label) outcomes.push(col ? {label, odds, column: col} : {label, odds});
                  });
                });
                if(outcomes.length) markets.push({title, outcomes});
              }
              // In-play pages list live events in a sidebar (`.LiveEventItem`); this event's own card has the current score.
              const card = [...document.querySelectorAll('.EventItem')]
                .find(i => (i.querySelector('a[href*="/elo-esemenyek/"]')?.getAttribute('href') || '').split('/').filter(Boolean).pop() === eventId);
              return JSON.stringify({markets, live: card ? liveOf(card) : null});
            }
        """.trimIndent()
    }
}
