<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { api, type AnalysisResponse, type Category, type RecentMatch, type TeamForm } from '../api'
import { calculating, recalcRequests } from '../recalc'

const props = defineProps<{ id: string }>()

const data = ref<AnalysisResponse | null>(null)
const loading = ref(true)
/** Recalculating while the earlier result stays on screen. */
const refreshing = ref(false)
const error = ref<string | null>(null)

// The analysis takes a while (browser scrape + stats + simulation): show what is happening.
const STEPS = [
  'Fogadási lehetőségek beolvasása a TippmixPRO-ról…',
  'Az utolsó 5 meccs és az egymás elleni eredmények letöltése…',
  'Szögletek, lapok, lövések és játékosadatok feldolgozása…',
  'Mérkőzések szimulálása (10 000 lefutás)…',
  'Minden lehetőség kiértékelése…',
]
const step = ref(0)
let stepTimer: number | undefined

// ✅ threshold, re-applied client-side so changing it needs no new scrape.
const THRESHOLDS = [0.5, 0.55, 0.6, 0.65, 0.7, 0.75, 0.8]
const threshold = ref(0.5)
const onlyYes = ref(false)
const query = ref('')
const category = ref<Category | 'ALL'>('ALL')

const CATEGORIES: { key: Category | 'ALL'; label: string }[] = [
  { key: 'ALL', label: 'Mind' },
  { key: 'GOALS', label: 'Gólok & eredmény' },
  { key: 'CORNERS', label: 'Szögletek' },
  { key: 'CARDS', label: 'Lapok & 11-es' },
  { key: 'SHOTS', label: 'Lövések' },
  { key: 'OTHER_STATS', label: 'Egyéb statisztika' },
  { key: 'PLAYERS', label: 'Játékosok' },
]

// Thousands of rows: render in pages.
const PAGE = 200
const shown = ref(PAGE)

const pct = (n: number) => `${Math.round(n * 100)}%`
const avg = (n: number | null) => (n == null ? '–' : n.toFixed(1))
const score = (m: RecentMatch) => `${m.goalsFor}–${m.goalsAgainst}`
const result = (m: RecentMatch) => (m.goalsFor > m.goalsAgainst ? 'W' : m.goalsFor < m.goalsAgainst ? 'L' : 'D')
const fold = (s: string) => s.normalize('NFD').replace(/\p{Mn}/gu, '').toLowerCase()

const filtered = computed(() => {
  if (!data.value) return []
  const q = fold(query.value.trim())
  return data.value.verdicts
    .map((v) => ({ ...v, happens: v.probability >= threshold.value }))
    .filter((v) => category.value === 'ALL' || v.category === category.value)
    .filter((v) => !onlyYes.value || v.happens)
    .filter((v) => !q || fold(`${v.marketTitle} ${v.selection}`).includes(q))
    .sort((a, b) => Number(b.happens) - Number(a.happens) || b.odds - a.odds)
})
const rows = computed(() => filtered.value.slice(0, shown.value))
const yesCount = computed(() => filtered.value.filter((v) => v.happens).length)
const noCount = computed(() => filtered.value.length - yesCount.value)
const categoryCount = (key: Category | 'ALL') =>
  data.value?.verdicts.filter((v) => key === 'ALL' || v.category === key).length ?? 0

watch([threshold, onlyYes, query, category], () => (shown.value = PAGE))

const forms = computed(() =>
  [data.value?.homeForm, data.value?.awayForm].filter((f): f is TeamForm => f != null),
)

const computedAt = computed(() => {
  const at = data.value?.computedAt
  return at ? new Date(at).toLocaleString('hu-HU', { dateStyle: 'short', timeStyle: 'short' }) : null
})

/** Loads the saved analysis (calculating it on the first visit), or recalculates when [refresh]. */
async function load(refresh: boolean) {
  error.value = null
  calculating.value = true
  if (refresh && data.value) refreshing.value = true
  else loading.value = true
  step.value = 0
  window.clearInterval(stepTimer)
  stepTimer = window.setInterval(() => (step.value = Math.min(step.value + 1, STEPS.length - 1)), 4000)
  try {
    data.value = await api.analysis(props.id, refresh)
    threshold.value = data.value.threshold ?? 0.5
  } catch (e: any) {
    error.value = e.message ?? 'Ismeretlen hiba'
  } finally {
    loading.value = false
    refreshing.value = false
    calculating.value = false
    window.clearInterval(stepTimer)
  }
}

onMounted(() => load(false))
watch(recalcRequests, () => load(true))
onUnmounted(() => {
  window.clearInterval(stepTimer)
  calculating.value = false
})
</script>

<template>
  <div v-if="loading" class="loading">
    <div class="spinner" />
    <p class="muted">{{ STEPS[step] }}</p>
    <p class="muted small">Egy meccs elemzése 15–30 másodpercig tart.</p>
  </div>

  <div v-else-if="error && !data" class="notice error">Hiba: {{ error }}</div>

  <template v-else-if="data">
    <div v-if="refreshing" class="refresh-banner">
      <div class="spinner small-spinner" />
      <span>Újraszámolás: {{ STEPS[step] }}</span>
    </div>
    <div v-else-if="error" class="notice error" style="margin-bottom:1rem">Az újraszámolás nem sikerült: {{ error }}</div>
    <div :class="{ stale: refreshing }">
    <div class="tournament">{{ data.match.tournament ?? 'Labdarúgás' }}</div>
    <h2 style="margin:0.3rem 0 0.2rem">
      {{ data.match.homeTeam }} – {{ data.match.awayTeam }}
      <span v-if="data.match.live" class="live-badge">
        ÉLŐ {{ data.match.live.homeGoals }}–{{ data.match.live.awayGoals }}
        <template v-if="data.match.live.minute != null">· {{ data.match.live.minute }}'</template>
      </span>
    </h2>
    <div class="kickoff">{{ data.match.startTime || data.match.live?.period || '' }}</div>
    <div v-if="computedAt" class="computed-at muted small">
      Számítva: {{ computedAt }}<template v-if="data.cached"> · korábbi eredmény — frissítéshez: ↻ Újraszámolás (jobb felül)</template>
    </div>

    <div v-if="data.match.live" class="notice" style="margin-top:0.75rem">
      Élő mérkőzés: a modell az aktuális állásból indul, és csak a hátralévő játékidőt szimulálja.
      Az eddigi szögletek, lapok és játékosesemények nem ismertek, ezért ezek a piacok élőben nem értékelhetők.
    </div>

    <div v-if="forms.length || data.headToHead" class="stats-grid">
      <div v-for="f in forms" :key="f.teamName" class="card stat-card">
        <div class="stat-head">
          <strong>{{ f.teamName }}</strong>
          <span class="muted">utolsó {{ f.matches }} meccs</span>
        </div>
        <div class="muted small">
          Lőtt <strong>{{ f.avgScored.toFixed(2) }}</strong> · Kapott <strong>{{ f.avgConceded.toFixed(2) }}</strong> gól / meccs
        </div>
        <ul class="recent">
          <li v-for="m in f.recent" :key="m.date + m.opponent">
            <span class="wdl" :class="result(m)">{{ result(m) }}</span>
            <span class="score">{{ score(m) }}</span>
            <span class="opp">{{ m.home ? 'vs' : '@' }} {{ m.opponent }}</span>
            <span class="date muted">{{ m.date }}</span>
          </li>
        </ul>
        <table v-if="f.stats?.length" class="stat-avgs">
          <thead><tr><th></th><th class="num">saját</th><th class="num">ellenfél</th></tr></thead>
          <tbody>
            <tr v-for="s in f.stats" :key="s.label">
              <td>{{ s.label }}</td>
              <td class="num">{{ avg(s.forAvg) }}</td>
              <td class="num muted">{{ avg(s.againstAvg) }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-if="data.headToHead" class="card stat-card">
        <div class="stat-head">
          <strong>Egymás ellen</strong>
          <span class="muted">utolsó {{ data.headToHead.matches.length }}</span>
        </div>
        <div class="muted small">
          {{ data.match.homeTeam }} <strong>{{ data.headToHead.homeAvgGoals.toFixed(2) }}</strong> ·
          {{ data.match.awayTeam }} <strong>{{ data.headToHead.awayAvgGoals.toFixed(2) }}</strong> gól / meccs
        </div>
        <ul class="recent">
          <li v-for="m in data.headToHead.matches" :key="m.date">
            <span class="opp">{{ m.homeTeam }} {{ m.homeGoals }}–{{ m.awayGoals }} {{ m.awayTeam }}</span>
            <span class="date muted">{{ m.date }}</span>
          </li>
        </ul>
      </div>
    </div>

    <div v-if="data.insufficientData" class="notice" style="margin-top:1rem">
      {{ data.message }}
    </div>

    <template v-else>
      <div v-if="data.expectedGoals" class="muted" style="margin-top:1rem">
        Várható gólok (modell): <strong>{{ data.match.homeTeam }} {{ data.expectedGoals.home.toFixed(2) }}</strong>
        — <strong>{{ data.match.awayTeam }} {{ data.expectedGoals.away.toFixed(2) }}</strong>
      </div>

      <div class="section-title">Fogadási lehetőségek</div>
      <div class="chips">
        <button
          v-for="c in CATEGORIES"
          :key="c.key"
          type="button"
          class="chip"
          :class="{ active: category === c.key }"
          @click="category = c.key"
        >
          {{ c.label }} <span class="muted">{{ categoryCount(c.key) }}</span>
        </button>
      </div>
      <div class="controls">
        <label>
          ✅ ha az esély legalább
          <select v-model.number="threshold">
            <option v-for="t in THRESHOLDS" :key="t" :value="t">{{ pct(t) }}</option>
          </select>
        </label>
        <label class="check"><input v-model="onlyYes" type="checkbox" /><span>Csak ✅</span></label>
        <input v-model="query" class="search" type="search" placeholder="Szűrés (pl. szöglet, Gakpo, félidő)…" />
        <span class="muted small">✅ {{ yesCount }} · ❌ {{ noCount }}</span>
      </div>

      <table class="markets verdicts">
        <thead>
          <tr>
            <th></th>
            <th>Piac</th>
            <th>Tipp</th>
            <th class="num">Odds</th>
            <th class="num">Esély</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(v, i) in rows" :key="i" :class="v.happens ? 'yes' : 'no'">
            <td class="mark">{{ v.happens ? '✅' : '❌' }}</td>
            <td>{{ v.marketTitle }}</td>
            <td>
              {{ v.selection }}
              <span v-if="!v.fromData" class="basis" title="Részben általános átlagokon alapul (nincs saját adat)">átlag</span>
            </td>
            <td class="num">{{ v.odds.toFixed(2) }}</td>
            <td class="num">{{ pct(v.probability) }}</td>
          </tr>
        </tbody>
      </table>
      <div v-if="filtered.length > rows.length" class="more">
        <button type="button" class="chip" @click="shown += PAGE">
          További {{ Math.min(PAGE, filtered.length - rows.length) }} megjelenítése ({{ filtered.length - rows.length }} maradt)
        </button>
      </div>

      <p class="muted small" style="margin-top:0.75rem">
        Az <span class="basis">átlag</span> jelölésű sorok részben általános átlagértékeken alapulnak
        (pl. bedobások, kirúgások, szerelések, vagy olyan játékos, akiről nincs friss adat).
      </p>

      <details v-if="data.skipped.length" class="skipped">
        <summary>{{ data.skipped.length }} lehetőség nem értékelhető</summary>
        <table class="markets">
          <tbody>
            <tr v-for="(s, i) in data.skipped" :key="i">
              <td>{{ s.marketTitle }}</td>
              <td>{{ s.selection }}</td>
              <td class="num">{{ s.odds.toFixed(2) }}</td>
              <td class="muted small">{{ s.reason }}</td>
            </tr>
          </tbody>
        </table>
      </details>

      <p class="muted" style="margin-top:1.5rem; font-size:0.78rem">
        Csak szórakoztató/elemzési célra. Nem fogadási tanácsadás. 18+
      </p>
    </template>
    </div>
  </template>
</template>
