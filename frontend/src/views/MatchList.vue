<script setup lang="ts">
import { computed, onMounted, onUnmounted } from 'vue'
import { autoRefreshMinutes, error, loadMatches, matches, refreshing, updatedAt } from '../matchList'

const fmt = (n: number | null) => (n == null ? '–' : n.toFixed(2))
const updated = computed(() =>
  updatedAt.value?.toLocaleString('hu-HU', { dateStyle: 'short', timeStyle: 'short' }) ?? null,
)

// The backend refreshes the list in the background; poll its snapshot (cheap, no scraping) to show updates.
const POLL_MS = 30_000
let poll: number | undefined

onMounted(() => {
  loadMatches()
  poll = window.setInterval(() => loadMatches(), POLL_MS)
})
onUnmounted(() => window.clearInterval(poll))
</script>

<template>
  <div v-if="matches === null && !error" class="loading">
    <div class="spinner" />
    <p class="muted">Kiemelt meccsek betöltése a TippmixPRO-ról…</p>
  </div>

  <div v-else-if="error && matches === null" class="notice error">
    Nem sikerült betölteni a meccseket: {{ error }}
  </div>

  <template v-else-if="matches">
    <div v-if="refreshing" class="refresh-banner">
      <div class="spinner small-spinner" />
      <span>Meccsek frissítése a TippmixPRO-ról…</span>
    </div>
    <div v-else-if="error" class="notice error" style="margin-bottom:1rem">A frissítés nem sikerült: {{ error }}</div>

    <p class="muted">Kiemelt labdarúgó mérkőzések — kattints egyre az összes fogadási lehetőség ✅/❌ értékeléséért.</p>
    <p v-if="updated" class="muted small list-updated">
      Frissítve: {{ updated }}<template v-if="autoRefreshMinutes"> · automatikusan {{ autoRefreshMinutes }} percenként</template>
    </p>

    <div v-if="matches.length === 0" class="notice">
      Jelenleg nincs elérhető kiemelt labdarúgó mérkőzés.
    </div>
    <div v-else class="grid" :class="{ stale: refreshing }">
      <RouterLink
        v-for="m in matches"
        :key="m.id"
        :to="{ name: 'match', params: { id: m.id } }"
        class="card match-card"
      >
        <div class="tournament">{{ m.tournament ?? 'Labdarúgás' }}</div>
        <div class="teams">
          <span class="team">{{ m.homeTeam }}</span>
          <span class="team">{{ m.awayTeam }}</span>
        </div>
        <div class="kickoff">
          <span v-if="m.live" class="live-badge">
            ÉLŐ {{ m.live.homeGoals }}–{{ m.live.awayGoals }}<template v-if="m.live.minute != null"> · {{ m.live.minute }}'</template>
          </span>
          <template v-else>{{ m.startTime || '' }}</template>
        </div>
        <div class="odds-row">
          <div class="odds-pill"><span class="k">1</span><span class="v">{{ fmt(m.odds.home) }}</span></div>
          <div class="odds-pill"><span class="k">X</span><span class="v">{{ fmt(m.odds.draw) }}</span></div>
          <div class="odds-pill"><span class="k">2</span><span class="v">{{ fmt(m.odds.away) }}</span></div>
        </div>
      </RouterLink>
    </div>
  </template>
</template>
