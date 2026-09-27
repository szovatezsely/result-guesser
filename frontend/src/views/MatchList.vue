<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, type PopularMatch } from '../api'

const matches = ref<PopularMatch[]>([])
const loading = ref(true)
const error = ref<string | null>(null)

const fmt = (n: number | null) => (n == null ? '–' : n.toFixed(2))

onMounted(async () => {
  try {
    matches.value = await api.matches()
  } catch (e: any) {
    error.value = e.message ?? 'Ismeretlen hiba'
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div v-if="loading" class="spinner" />

  <div v-else-if="error" class="notice error">
    Nem sikerült betölteni a meccseket: {{ error }}
  </div>

  <div v-else-if="matches.length === 0" class="notice">
    Jelenleg nincs elérhető kiemelt labdarúgó mérkőzés.
  </div>

  <template v-else>
    <p class="muted">Kiemelt labdarúgó mérkőzések — kattints egyre az összes fogadási lehetőség ✅/❌ értékeléséért.</p>
    <div class="grid">
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
