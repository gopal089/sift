import { useEffect, useState } from "react";
import { ApiError, search } from "./api";
import { Pager } from "./components/Pager";
import { ResultList } from "./components/ResultList";
import { SearchForm } from "./components/SearchForm";
import { StatsPanel } from "./components/StatsPanel";
import type { Mode, SearchResponse } from "./types";
import { readState, writeState, type SearchState } from "./urlState";

/** What the results area is showing right now. */
type Status =
  | { kind: "idle" } // nothing searched yet
  | { kind: "loading" }
  | { kind: "done"; data: SearchResponse }
  | { kind: "error"; message: string };

export function App() {
  // The search the page shows. It mirrors the address bar, so links, reload and back/forward all work.
  const [state, setState] = useState<SearchState>(() => readState(window.location.search));
  const [status, setStatus] = useState<Status>({ kind: "idle" });
  const [attempt, setAttempt] = useState(0); // bumped by "Try again" to repeat the same search

  // Back / forward buttons: read the state from the address bar again.
  useEffect(() => {
    const onPopState = () => setState(readState(window.location.search));
    window.addEventListener("popstate", onPopState);
    return () => window.removeEventListener("popstate", onPopState);
  }, []);

  // Run the search whenever the query, mode or page changes.
  useEffect(() => {
    if (!state.query) {
      setStatus({ kind: "idle" });
      return;
    }
    const controller = new AbortController();
    setStatus({ kind: "loading" });
    search(state.query, state.mode, state.page, controller.signal)
      .then((data) => setStatus({ kind: "done", data }))
      .catch((error: unknown) => {
        if (controller.signal.aborted) return; // a newer search replaced this one: ignore it
        const message = error instanceof ApiError ? error.message : "Something went wrong. Please try again.";
        setStatus({ kind: "error", message });
      });
    // If the state changes before the answer arrives, cancel this request so an old answer
    // can never overwrite a newer one.
    return () => controller.abort();
  }, [state.query, state.mode, state.page, attempt]);

  /** Shows a new search and records it in the browser history. */
  function go(next: SearchState) {
    window.history.pushState(null, "", writeState(next) || window.location.pathname);
    setState(next);
    window.scrollTo({ top: 0 });
  }

  const data = status.kind === "done" ? status.data : null;

  return (
    <div className="page">
      <header className="header">
        <h1 className="logo">
          <a href={window.location.pathname} aria-label="Sift home">
            Sift
          </a>
        </h1>
        <p className="tagline">A search engine built from scratch, over Simple English Wikipedia.</p>
        <SearchForm
          query={state.query}
          mode={state.mode}
          onSearch={(query: string, mode: Mode) => go({ query, mode, page: 1 })}
        />
      </header>

      <main className="main">
        {/* Screen readers announce changes inside this element. */}
        <p className="status" role="status" aria-live="polite">
          {status.kind === "idle" && "Type a word or a question and press Search."}
          {status.kind === "loading" && "Searching…"}
          {data && data.totalMatches > 0 && (
            <>
              {data.totalMatches.toLocaleString("en-US")} {data.totalMatches === 1 ? "result" : "results"} for “
              {data.query}”{data.page > 1 && `, page ${data.page}`}
            </>
          )}
          {data && data.totalMatches === 0 && <>No results for “{data.query}”.</>}
        </p>

        {data?.notice && <p className="notice">{data.notice}</p>}

        {data?.didYouMean && (
          <p className="did-you-mean">
            Did you mean{" "}
            <button type="button" className="link-button" onClick={() => go({ ...state, query: data.didYouMean!, page: 1 })}>
              {data.didYouMean}
            </button>
            ?
          </p>
        )}

        {status.kind === "error" && (
          <div className="error" role="alert">
            <p>{status.message}</p>
            <button type="button" onClick={() => setAttempt((n) => n + 1)}>
              Try again
            </button>
          </div>
        )}

        {data && data.totalMatches === 0 && !data.notice && !data.didYouMean && (
          <p className="hint">Check the spelling, or try fewer or different words.</p>
        )}

        {data && data.results.length > 0 && (
          <>
            <StatsPanel stats={data.stats} />
            <ResultList results={data.results} firstNumber={(data.page - 1) * data.pageSize + 1} />
            <Pager page={data.page} hasMore={data.hasMore} onPage={(page) => go({ ...state, page })} />
          </>
        )}
        {data && data.results.length === 0 && data.totalMatches > 0 && (
          <>
            <p className="hint">There are no more results on this page.</p>
            <Pager page={data.page} hasMore={false} onPage={(page) => go({ ...state, page })} />
          </>
        )}
      </main>

      <footer className="footer">
        Content from{" "}
        <a href="https://simple.wikipedia.org/" rel="noreferrer">
          Simple English Wikipedia
        </a>
        ,{" "}
        <a href="https://creativecommons.org/licenses/by-sa/4.0/" rel="noreferrer">
          CC BY-SA
        </a>
        .
      </footer>
    </div>
  );
}
