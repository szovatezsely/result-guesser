# Result Guesser

A web app that lists TippmixPRO's popular ("Kiemelt") football matches and, on
click, analyzes both teams' recent form against the match's real betting options
to recommend the bet with the best value — e.g.:

> **1x2 + Gólszám 3,5 - Rendes játékidő** → **Hazai és Több, mint 3,5**

**Stack:** Kotlin/Ktor backend · Vue 3 frontend · Docker Compose.

---

## How it works

```
Vue 3 (nginx) ──/api──► Ktor backend ──► Playwright/Chromium → sports2.tippmixpro.hu  (matches, odds, markets)
                                     └─► football-data.org API                          (recent team form)
                                     └─► Poisson value model → recommendation
```

1. **Scrape** — a headless Chromium reads the embedded EveryMatrix sportsbook for
   the popular football matches, their odds, and (on click) every market with its
   exact Hungarian title (`Hazai`, `Vendég`, `Igen`, `Nem`, `Rendes játékidő`, …).
2. **Stats** — recent finished matches for both teams come from football-data.org;
   Hungarian team names are mapped to English via `team-aliases.json`.
3. **Predict** — a Poisson model turns recent goal rates into expected goals and a
   full scoreline distribution, giving a probability for each market outcome.
4. **Recommend** — for every scraped selection it computes
   `valueEdge = modelProbability × odds − 1` and recommends the best-value,
   reasonably-likely pick.

## Quick start

```bash
# 1. Get a free API key: https://www.football-data.org/client/register
cp .env.example .env
#   then edit .env and set FOOTBALL_DATA_API_KEY=...

# 2. Build & run everything
docker compose up --build

# 3. Open the app
open http://localhost:9090
```

The host port defaults to **9090**. Change it by setting `APP_PORT` in `.env`
(e.g. `APP_PORT=9100`) — handy when 8080/8088 are already taken.

The app works without a key too — match listing and odds still load; only the
statistical recommendation reports "insufficient data" until a key is provided.

### API endpoints (via the backend)

| Endpoint | Description |
| --- | --- |
| `GET /api/matches` | Popular football matches with 1X2 odds. |
| `GET /api/matches/{id}/recommendation` | Full analysis + recommended bet for a match. |
| `GET /health` | Liveness check. |

## Local development (without Docker)

```bash
# Backend (needs JDK 17)
cd backend
FOOTBALL_DATA_API_KEY=... ./gradlew run        # serves http://localhost:8080

# Frontend (needs Node 18+)
cd frontend
npm install
npm run dev                                     # serves http://localhost:5173, proxies /api → :8080
```

## Notes & caveats

- **Scraping is fragile.** All TippmixPRO selectors live in one file —
  [`TippmixScraper.kt`](backend/src/main/kotlin/io/adroit/resultguesser/scraper/TippmixScraper.kt) —
  so that's the single place to fix if the site's markup changes.
- **Free-tier coverage.** football-data.org's free plan only covers major
  competitions; matches whose teams aren't covered return an honest
  "insufficient data" response rather than a fabricated pick. Extend
  [`team-aliases.json`](backend/src/main/resources/team-aliases.json) as new
  teams appear in the logs.
- **Not betting advice.** This is an analysis / for-fun project. 18+.
