import type { Mode } from "./types";

/**
 * The mode used when the address bar does not say. Smart, by the rule fixed before the final held-out run
 * (Smart MRR >= keyword MRR - 0.01 on queries-holdout-v2): 0.857 vs 0.774. See eval/results/holdout-v2-final.txt.
 */
export const DEFAULT_MODE: Mode = "smart";

/** What the page is showing. It lives in the address bar: ?q=volcano&mode=keyword&page=2 (Smart, the default, is left out) */
export interface SearchState {
  query: string;
  mode: Mode;
  page: number;
}

/** Reads the state from a query string such as "?q=volcano&page=2". Bad values fall back to defaults. */
export function readState(search: string): SearchState {
  const params = new URLSearchParams(search);
  const page = Number.parseInt(params.get("page") ?? "1", 10);
  return {
    query: (params.get("q") ?? "").trim(),
    mode: params.get("mode") === "keyword" ? "keyword" : params.get("mode") === "smart" ? "smart" : DEFAULT_MODE,
    page: Number.isInteger(page) && page >= 1 && page <= 100 ? page : 1,
  };
}

/** The query string for a state. Defaults are left out to keep links short. */
export function writeState(state: SearchState): string {
  const params = new URLSearchParams();
  if (state.query) params.set("q", state.query);
  if (state.mode !== DEFAULT_MODE) params.set("mode", state.mode);
  if (state.page > 1) params.set("page", String(state.page));
  const text = params.toString();
  return text ? `?${text}` : "";
}
