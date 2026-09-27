# Result Guesser

A web app that lists TippmixPRO's popular ("Kiemelt") football matches and, on
click, judges **every** betting option of the match — goals, corners, cards,
shots, fouls, offsides, penalties, goal timing, player markets, … — against
both teams' recent matches: each option gets ✅ (the stats say it should
happen) or ❌, and the ✅ options are ranked by TippmixPRO odds, highest first —
e.g.:

| | Piac | Tipp | Odds | Esély |
| --- | --- | --- | --- | --- |
| ✅ | Gólszám - Rendes játékidő | Több, mint 2,5 | 1.70 | 69% |
| ✅ | Gólszám - Rendes játékidő | Több, mint 1,5 | 1.20 | 87% |
| ❌ | Gólszám - Rendes játékidő | Több, mint 6,5 | 12.00 | 7% |

**Stack:** Kotlin/Ktor backend · Vue 3 frontend · Docker Compose.

---

## How it works

```
Vue 3 (nginx) ──/api──► Ktor backend ──► Playwright/Chromium → sports2.tippmixpro.hu  (matches, odds, all markets)
                                     └─► ESPN public site API                           (last 5 matches, head-to-head,
                                     │                                                   box scores, player lines, squads)
                                     └─► Monte-Carlo match simulation → ✅/❌ per option
```

1. **Scrape** — a headless Chromium reads the embedded EveryMatrix sportsbook for
   the popular football matches (pre-match *and* in-play), their odds, and (on
   click) every market of the event's "Összes" tab with its exact Hungarian
   title and outcome labels (`Hazai`, `Több, mint 2,5`, `Anglia vagy Döntetlen`, …),
   plus the column each option sits in (for player lists: the player's team).
2. **Stats** — from ESPN (no key needed): each team's last 5 finished matches
   and the two teams' last 5 meetings, each with its box score (corners, cards,
   fouls, offsides, shots, shots on target, penalties, tackles), minute-stamped
   goals and cards, and every player's line (goals, assists, shots, fouls,
   cards, offsides); plus both squads. Hungarian team names are mapped to
   English via `team-aliases.json`; the teams are confirmed by finding the
   fixture itself in ESPN's schedule.
3. **Simulate** — per-team rates for every statistic (own average blended with
   what the opponent allows; goals also with home advantage and the
   head-to-head) drive 10 000 simulated matches: minute-stamped goals (with
   scorer, assister and type), corners, cards and penalties, per-half counts,
   and a line-up per match with player-level shots, fouls, tackles and
   offsides. Player bets are void when the player doesn't play, as bookmakers
   settle them. For live matches the simulation starts from the current score
   and only plays the remaining minutes.
4. **Judge** — each option is parsed into a rule over a simulated match (see
   [`Glossary.kt`](backend/src/main/kotlin/io/resultguesser/i18n/Glossary.kt)
   and [`ExtraMarkets.kt`](backend/src/main/kotlin/io/resultguesser/i18n/ExtraMarkets.kt));
   it's ✅ when it wins in at least 50 % of the simulations (adjustable in the
   UI or with `?threshold=0.6`), otherwise ❌. Rows marked **átlag** partly rest
   on general averages because the data has no such figure (throw-ins, goal
   kicks, player tackles, players without recent appearances).

## Quick start

```bash
# 1. Optional: choose the host port
cp .env.example .env

# 2. Build & run everything
docker compose up --build

# 3. Open the app
open http://localhost:9090
```

The host port defaults to **9090**. Change it by setting `APP_PORT` in `.env`
(e.g. `APP_PORT=9100`) — handy when 8080/8088 are already taken.

Opening a match the first time takes 15–30 s (browser scrape of ~200–450
markets, the stats requests and the simulation); the page shows a spinner with
the current step. The result is saved (under `DATA_DIR`, default `./data`; a
Docker volume in Compose), so opening the match again shows it instantly with
its calculation time. **↻ Újraszámolás** (top right) recalculates it from
fresh odds and stats.

### API endpoints (via the backend)

| Endpoint | Description |
| --- | --- |
| `GET /api/matches` | Popular football matches with 1X2 odds. |
| `GET /api/matches/{id}/analysis` | Form, head-to-head and a ✅/❌ verdict for every betting option — the saved result if the match was analysed before (`?refresh=true` recalculates; `?threshold=` optional, default 0.5). |
| `GET /health` | Liveness check. |

## Local development (without Docker)

```bash
# Backend (needs JDK 17)
cd backend
./gradlew run                                   # serves http://localhost:8080

# Frontend (needs Node 18+)
cd frontend
npm install
npm run dev                                     # serves http://localhost:5173, proxies /api → :8080
```

## Notes & caveats

- **Scraping is fragile.** All TippmixPRO selectors live in one file —
  [`TippmixScraper.kt`](backend/src/main/kotlin/io/resultguesser/scraper/TippmixScraper.kt) —
  so that's the single place to fix if the site's markup changes.
- **Stats coverage.** ESPN covers most leagues and national-team competitions,
  but some (e.g. small Oceania nations) come without box scores: their
  non-goal statistics fall back to general averages (marked **átlag**). Extend
  [`team-aliases.json`](backend/src/main/resources/team-aliases.json) when a
  Hungarian team name isn't found (see the logs).
- **Live matches.** Only goals are known in play, so options that depend on
  corners/cards/players so far, or on when earlier goals fell, are listed as
  not evaluable while a match is live.
- **Not betting advice.** This is an analysis / for-fun project. 18+.
