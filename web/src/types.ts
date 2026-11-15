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
  /** Smart mode only: time to turn the query into a meaning vector, and to search the vectors (HNSW). */
  embedMs: number | null;
  vectorMs: number | null;
  /** Page 1 only: time to decide on the definition card and find its related articles. */
  cardMs: number | null;
}

export interface RelatedArticle {
  id: number;
  title: string;
  url: string;
}

/** Shown above the results when the top result's title (or a redirect to it) has exactly the query's words. */
export interface Definition {
  id: number;
  title: string;
  url: string;
  /** The article's first one or two sentences. */
  text: string;
  matchedBy: "title" | "redirect";
  /** Up to 5 articles nearest in meaning to this one, none of them on the current results page. */
  related: RelatedArticle[];
}

export interface SearchResponse {
  query: string;
  mode: Mode;
  modeUsed: Mode;
  notice: string | null;
  /** The query with spelling mistakes corrected, or null if every word is known. */
  didYouMean: string | null;
  page: number;
  pageSize: number;
  totalMatches: number;
  hasMore: boolean;
  definition: Definition | null;
  results: Result[];
  stats: QueryStats;
}

export interface SuggestResponse {
  prefix: string;
  suggestions: string[];
}
