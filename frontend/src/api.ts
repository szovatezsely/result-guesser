// Typed client for the backend API. Same-origin (/api is proxied to the backend
// by nginx in production and by Vite during local dev).

export interface MatchOdds {
  home: number | null
  draw: number | null
  away: number | null
}

export interface PopularMatch {
  id: string
  homeTeam: string
  awayTeam: string
  startTime: string | null
  tournament: string | null
  odds: MatchOdds
  href: string | null
}

export interface TeamForm {
  teamName: string
  matches: number
  avgScored: number
  avgConceded: number
}

export interface ScoredSelection {
  marketTitle: string
  selection: string
  odds: number
  modelProbability: number
  impliedProbability: number
  valueEdge: number
}

export interface Recommendation {
  marketTitle: string
  selection: string
  odds: number
  modelProbability: number
  impliedProbability: number
  valueEdge: number
  rationale: string
}

export interface RecommendationResponse {
  match: PopularMatch
  insufficientData: boolean
  message: string | null
  homeForm: TeamForm | null
  awayForm: TeamForm | null
  expectedGoals: { home: number; away: number } | null
  recommendation: Recommendation | null
  topMarkets: ScoredSelection[]
}

async function getJson<T>(url: string): Promise<T> {
  const res = await fetch(url)
  if (!res.ok) {
    const body = await res.json().catch(() => ({}))
    throw new Error((body as any)?.error || `Kérés sikertelen (${res.status})`)
  }
  return res.json() as Promise<T>
}

export const api = {
  matches: () => getJson<PopularMatch[]>('/api/matches'),
  recommendation: (id: string) =>
    getJson<RecommendationResponse>(`/api/matches/${encodeURIComponent(id)}/recommendation`),
}
