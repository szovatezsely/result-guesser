// The popular-match list, kept in memory for the whole session so going back
// to the main page shows it instantly. The backend refreshes it from
// TippmixPRO in the background; this module picks those updates up and lets
// the app bar's "Meccsek frissítése" button force a new scrape.
import { ref } from 'vue'
import { api, type PopularMatch } from './api'

export const matches = ref<PopularMatch[] | null>(null)
export const updatedAt = ref<Date | null>(null)
export const autoRefreshMinutes = ref<number | null>(null)
export const error = ref<string | null>(null)
/** A user-requested scrape is running (disables the button). */
export const refreshing = ref(false)

/** Fetches the list: the backend's latest snapshot, or a fresh scrape when [refresh]. */
export async function loadMatches(refresh = false) {
  if (refresh) refreshing.value = true
  try {
    const res = await api.matches(refresh)
    matches.value = res.matches
    updatedAt.value = res.updatedAt ? new Date(res.updatedAt) : null
    autoRefreshMinutes.value = res.autoRefreshMinutes
    error.value = null
  } catch (e: any) {
    error.value = e.message ?? 'Ismeretlen hiba'
  } finally {
    if (refresh) refreshing.value = false
  }
}
