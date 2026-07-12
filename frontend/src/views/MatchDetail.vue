<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, type RecommendationResponse } from '../api'

const props = defineProps<{ id: string }>()

const data = ref<RecommendationResponse | null>(null)
const loading = ref(true)
const error = ref<string | null>(null)

const pct = (n: number) => `${Math.round(n * 100)}%`
const edgePct = (n: number) => `${n >= 0 ? '+' : ''}${Math.round(n * 100)}%`

onMounted(async () => {
  try {
    data.value = await api.recommendation(props.id)
  } catch (e: any) {
    error.value = e.message ?? 'Ismeretlen hiba'
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div v-if="loading">
    <div class="spinner" />
    <p class="muted" style="text-align:center">Meccs elemzése — formák, gólátlagok és fogadási piacok…</p>
  </div>

  <div v-else-if="error" class="notice error">Hiba: {{ error }}</div>

  <template v-else-if="data">
    <div class="tournament">{{ data.match.tournament ?? 'Labdarúgás' }}</div>
    <h2 style="margin:0.3rem 0 0.2rem">{{ data.match.homeTeam }} – {{ data.match.awayTeam }}</h2>
    <div class="kickoff">{{ data.match.startTime || '' }}</div>

    <div v-if="data.insufficientData" class="notice" style="margin-top:1rem">
      {{ data.message }}
    </div>

    <template v-else-if="data.recommendation">
      <div class="rec-hero">
        <div class="label">Javasolt tipp</div>
        <div class="market">{{ data.recommendation.marketTitle }}</div>
        <div class="selection">{{ data.recommendation.selection }}</div>
        <div class="rationale">{{ data.recommendation.rationale }}</div>
        <div class="metrics">
          <div class="metric">
            <span class="k">Odds</span>
            <span class="v">{{ data.recommendation.odds.toFixed(2) }}</span>
          </div>
          <div class="metric">
            <span class="k">Modell szerinti esély</span>
            <span class="v">{{ pct(data.recommendation.modelProbability) }}</span>
          </div>
          <div class="metric">
            <span class="k">Odds által beárazott</span>
            <span class="v">{{ pct(data.recommendation.impliedProbability) }}</span>
          </div>
          <div class="metric">
            <span class="k">Értékelőny</span>
            <span class="v" :class="data.recommendation.valueEdge >= 0 ? 'good' : 'bad'">
              {{ edgePct(data.recommendation.valueEdge) }}
            </span>
          </div>
        </div>
      </div>

      <div v-if="data.expectedGoals" class="muted">
        Várható gólok (modell): <strong>{{ data.match.homeTeam }} {{ data.expectedGoals.home.toFixed(2) }}</strong>
        — <strong>{{ data.match.awayTeam }} {{ data.expectedGoals.away.toFixed(2) }}</strong>
      </div>
      <div class="form-line" v-if="data.homeForm && data.awayForm">
        <span>{{ data.homeForm.teamName }}: {{ data.homeForm.matches }} meccs, lőtt {{ data.homeForm.avgScored.toFixed(2) }} / kapott {{ data.homeForm.avgConceded.toFixed(2) }}</span>
        <span>{{ data.awayForm.teamName }}: {{ data.awayForm.matches }} meccs, lőtt {{ data.awayForm.avgScored.toFixed(2) }} / kapott {{ data.awayForm.avgConceded.toFixed(2) }}</span>
      </div>

      <div class="section-title">Legjobb értékű piacok</div>
      <table class="markets">
        <thead>
          <tr>
            <th>Piac</th>
            <th>Tipp</th>
            <th class="num">Odds</th>
            <th class="num">Modell</th>
            <th class="num">Értékelőny</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(s, i) in data.topMarkets" :key="i">
            <td>{{ s.marketTitle }}</td>
            <td>{{ s.selection }}</td>
            <td class="num">{{ s.odds.toFixed(2) }}</td>
            <td class="num">{{ pct(s.modelProbability) }}</td>
            <td class="num" :class="s.valueEdge >= 0 ? 'edge-pos' : 'edge-neg'">{{ edgePct(s.valueEdge) }}</td>
          </tr>
        </tbody>
      </table>

      <p class="muted" style="margin-top:1.5rem; font-size:0.78rem">
        Csak szórakoztató/elemzési célra. Nem fogadási tanácsadás. 18+
      </p>
    </template>
  </template>
</template>
