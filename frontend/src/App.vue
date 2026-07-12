<script setup lang="ts">
import { useRoute } from 'vue-router'
import { computed } from 'vue'

const route = useRoute()
const isDetail = computed(() => route.name === 'match')
</script>

<template>
  <header class="appbar">
    <h1>Result Guesser</h1>
    <span class="badge">TippmixPRO</span>
    <span class="spacer" />
    <RouterLink v-if="isDetail" to="/" class="back">← Vissza a meccsekhez</RouterLink>
  </header>
  <main class="container">
    <RouterView />
  </main>
</template>
