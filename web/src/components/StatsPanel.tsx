import type { QueryStats } from "../types";

function formatMs(ms: number): string {
  return ms < 10 ? ms.toFixed(2) : ms.toFixed(0);
}

/** Small panel with the numbers behind one search. All values come straight from the API. */
export function StatsPanel({ stats }: { stats: QueryStats }) {
  const ms = formatMs(stats.tookMs);
  return (
    <aside className="stats" aria-label="Search statistics">
      <dl>
        <div>
          <dt>Query time</dt>
          <dd>{ms} ms</dd>
        </div>
        {stats.embedMs != null && stats.vectorMs != null && (
          <div>
            <dt>Meaning search</dt>
            <dd>
              {formatMs(stats.embedMs)} ms model + {formatMs(stats.vectorMs)} ms vectors
            </dd>
          </div>
        )}
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
