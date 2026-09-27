// Typed client for the backend API. Same-origin (/api is proxied to the backend
// by nginx in production and by Vite during local dev).

export interface MatchOdds {
  home: number | null
  draw: number | null
  away: number | null
}

export interface LiveState {
  homeGoals: number
  awayGoals: number
  minute: number | null
  period: string | null
}

export interface PopularMatch {
  id: string
  homeTeam: string
  awayTeam: string
  startTime: string | null
  tournament: string | null
  odds: MatchOdds
  href: string | null
  live: LiveState | null
}

export interface RecentMatch {
  date: string
  competition: string | null
  opponent: string
  home: boolean
  goalsFor: number
  goalsAgainst: number
}

/** Per-match average of a statistic: the team's own and its opponents'. */
export interface StatLine {
  label: string
  forAvg: number | null
  againstAvg: number | null
}

export interface TeamForm {
  teamName: string
  matches: number
  avgScored: number
  avgConceded: number
  recent: RecentMatch[]
  stats: StatLine[]
}

export interface H2hMatch {
  date: string
  competition: string | null
  homeTeam: string
  awayTeam: string
  homeGoals: number
  awayGoals: number
}

export interface HeadToHead {
  matches: H2hMatch[]
  homeAvgGoals: number
  awayAvgGoals: number
}

/** One betting option judged by the model. */
export interface Verdict {
  marketTitle: string
  selection: string
  odds: number
  probability: number
  happens: boolean
  /** False when part of it rests on general averages rather than these teams'/players' own data. */
  fromData: boolean
  category: Category
}

export type Category = 'GOALS' | 'CORNERS' | 'CARDS' | 'SHOTS' | 'OTHER_STATS' | 'PLAYERS'

export interface SkippedOption {
  marketTitle: string
  selection: string
  odds: number
  reason: string
}

export interface AnalysisResponse {
  match: PopularMatch
  insufficientData: boolean
  message: string | null
  homeForm: TeamForm | null
  awayForm: TeamForm | null
  headToHead: HeadToHead | null
  expectedGoals: { home: number; away: number } | null
  threshold: number
  verdicts: Verdict[]
  skipped: SkippedOption[]
  /** When the analysis was calculated (ISO-8601). */
  computedAt: string | null
  /** True when this is an earlier calculation served again. */
  cached: boolean
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
  /** The saved analysis if the match was calculated before; [refresh] forces a new calculation. */
  analysis: (id: string, refresh = false) =>
    getJson<AnalysisResponse>(`/api/matches/${encodeURIComponent(id)}/analysis${refresh ? '?refresh=true' : ''}`),
}
