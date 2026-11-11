// The JSON shapes the API returns (they mirror ApiModels.java on the server).

export type Mode = "keyword" | "smart";

/** Characters [start, end) of a snippet that should be bold. */
export interface Highlight {
  start: number;
  end: number;
}

export interface Result {
  id: number;
  title: string;
  url: string;
  snippet: string;
  highlights: Highlight[];
  score: number;
}

export interface QueryStats {
  tookMs: number;
  documentsSearched: number;
  totalMatches: number;
  terms: string[];
  matchMode: string;
  cache: string;
}

export interface SearchResponse {
  query: string;
  mode: Mode;
  modeUsed: Mode;
  notice: string | null;
  page: number;
  pageSize: number;
  totalMatches: number;
  hasMore: boolean;
  results: Result[];
  stats: QueryStats;
}
