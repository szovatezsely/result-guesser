<script setup lang="ts">
import { useRoute } from 'vue-router'
import { computed } from 'vue'
import { calculating, recalcRequests } from './recalc'

const route = useRoute()
const isDetail = computed(() => route.name === 'match')
</script>

<template>
  <header class="appbar">
    <h1>Result Guesser</h1>
    <span class="badge">TippmixPRO</span>
    <span class="spacer" />
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
