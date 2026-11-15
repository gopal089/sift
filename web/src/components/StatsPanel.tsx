import { useId, useState } from "react";
import type { QueryStats } from "../types";
import { ChevronRightIcon } from "./Icons";

function formatMs(ms: number): string {
  return ms < 10 ? ms.toFixed(2) : ms.toFixed(0);
}

interface Props {
  stats: QueryStats;
  /** The visible count, e.g. "184 results". It is also announced by screen readers (role="status"). */
  summary: string;
  /** A newer search is running: show "Searching…" (it only appears if the search takes a while). */
  loading: boolean;
}

/**
 * The quiet line above the results: "184 results · 30 ms · Details". The full numbers behind the search
 * (all straight from the API) open under "Details", in monospace so the digits line up.
 */
export function StatsPanel({ stats, summary, loading }: Props) {
  const [open, setOpen] = useState(false);
  const listId = useId();
  return (
    <>
      <div className="meta">
        <p role="status">{summary}</p>
        <span aria-hidden="true">·</span>
        <span>{formatMs(stats.tookMs)} ms</span>
        <button
          type="button"
          className="text-button"
          aria-expanded={open}
          aria-controls={listId}
          onClick={() => setOpen((o) => !o)}
        >
          <ChevronRightIcon small className="disclosure-icon" />
          Details
        </button>
        {loading && <span className="loading">Searching…</span>}
      </div>
      <dl className="details" id={listId} hidden={!open} aria-label="Search statistics">
        <dt>Query time</dt>
        <dd>{formatMs(stats.tookMs)} ms</dd>
        {stats.embedMs != null && stats.vectorMs != null && (
          <>
            <dt>Meaning model</dt>
            <dd>{formatMs(stats.embedMs)} ms</dd>
            <dt>Vector search</dt>
            <dd>{formatMs(stats.vectorMs)} ms</dd>
          </>
        )}
        <dt>Documents searched</dt>
        <dd>{stats.documentsSearched.toLocaleString("en-US")}</dd>
        <dt>Matches</dt>
        <dd>{stats.totalMatches.toLocaleString("en-US")}</dd>
        <dt>Cache</dt>
        <dd>{stats.cache}</dd>
        <dt>Searched terms</dt>
        <dd>{stats.terms.join(", ") || "none"}</dd>
      </dl>
    </>
  );
}
