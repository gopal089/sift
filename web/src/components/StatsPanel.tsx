import type { QueryStats } from "../types";

/** Small panel with the numbers behind one search. All values come straight from the API. */
export function StatsPanel({ stats }: { stats: QueryStats }) {
  const ms = stats.tookMs < 10 ? stats.tookMs.toFixed(2) : stats.tookMs.toFixed(0);
  return (
    <aside className="stats" aria-label="Search statistics">
      <dl>
        <div>
          <dt>Query time</dt>
          <dd>{ms} ms</dd>
        </div>
        <div>
          <dt>Documents searched</dt>
          <dd>{stats.documentsSearched.toLocaleString("en-US")}</dd>
        </div>
        <div>
          <dt>Matches</dt>
          <dd>{stats.totalMatches.toLocaleString("en-US")}</dd>
        </div>
        <div>
          <dt>Cache</dt>
          <dd>{stats.cache}</dd>
        </div>
        <div>
          <dt>Searched terms</dt>
          <dd>{stats.terms.join(", ") || "none"}</dd>
        </div>
      </dl>
    </aside>
  );
}
