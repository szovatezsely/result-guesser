<script setup lang="ts">
import { useRoute } from 'vue-router'
import { computed } from 'vue'
import { calculating, recalcRequests } from './recalc'
import { loadMatches, refreshing } from './matchList'

const route = useRoute()
const isDetail = computed(() => route.name === 'match')
const isList = computed(() => route.name === 'matches')
</script>

<template>
  <header class="appbar">
    <h1>Result Guesser</h1>
    <span class="badge">TippmixPRO</span>
    <span class="spacer" />
    <button
      v-if="isList"
      type="button"
      class="recalc"
      :disabled="refreshing"
      title="A legfrissebb kiemelt meccsek és oddsok betöltése a TippmixPRO-ról"
      @click="loadMatches(true)"
    >
      ↻ <span class="long">Meccsek frissítése</span><span class="short">Frissítés</span>
    </button>
    <button
      v-if="isDetail"
      type="button"
      class="recalc"
      :disabled="calculating"
      title="Friss odds és statisztikák alapján újraszámolja az elemzést"
      @click="recalcRequests++"
    >
      ↻ Újraszámolás
    </button>
    <RouterLink v-if="isDetail" to="/" class="back">
      ← <span class="long">Vissza a meccsekhez</span><span class="short">Meccsek</span>
    </RouterLink>
  </header>
  <main class="container">
    <RouterView />
  </main>
</template>
