import { useEffect, useRef, useState, type MouseEvent } from "react";
import { ApiError, documentCount, search } from "./api";
import { AlertIcon } from "./components/Icons";
import { MAX_PAGE, Pager } from "./components/Pager";
import { ResultList } from "./components/ResultList";
import { SearchForm } from "./components/SearchForm";
import { StatsPanel } from "./components/StatsPanel";
import type { Mode, SearchResponse } from "./types";
import { readState, writeState, type SearchState } from "./urlState";

/** What the results area is showing right now. */
type Status =
  | { kind: "idle" } // nothing searched yet
  | { kind: "loading"; previous: SearchResponse | null } // the old answer stays visible (dimmed) meanwhile
  | { kind: "done"; data: SearchResponse }
  | { kind: "error"; message: string };

/** Example searches on the home page: real links, so they also work when opened in a new tab. */
const EXAMPLES = ["photosynthesis", "how do plants make food", "world war 2", "pyhton"];

/** The Wikipedia dump the index was built from (see README). */
const DUMP_DATE = "1 November 2026";

/** A plain click: not Ctrl/Cmd/Shift-click (new tab or window) and not the middle button. */
function isPlainClick(event: MouseEvent) {
  return event.button === 0 && !event.metaKey && !event.ctrlKey && !event.shiftKey && !event.altKey;
}

function href(state: SearchState) {
  return writeState(state) || window.location.pathname;
}

export function App() {
  // The search the page shows. It mirrors the address bar, so links, reload and back/forward all work.
  const [state, setState] = useState<SearchState>(() => readState(window.location.search));
  const [status, setStatus] = useState<Status>({ kind: "idle" });
  const [attempt, setAttempt] = useState(0); // bumped by "Try again" to repeat the same search
  const [documents, setDocuments] = useState<number | null>(null);
  const focusResults = useRef(false); // after Next / Previous, move keyboard focus to the new results

  // Back / forward buttons: read the state from the address bar again.
  useEffect(() => {
    const onPopState = () => setState(readState(window.location.search));
    window.addEventListener("popstate", onPopState);
    return () => window.removeEventListener("popstate", onPopState);
  }, []);

  // The article count for the home page, asked once.
  useEffect(() => {
    const controller = new AbortController();
    documentCount(controller.signal).then(setDocuments);
    return () => controller.abort();
  }, []);

  // Run the search whenever the query, mode or page changes.
  useEffect(() => {
    document.title = state.query ? `${state.query} – Sift` : "Sift — search Simple English Wikipedia";
    if (!state.query) {
      setStatus({ kind: "idle" });
      return;
    }
    const controller = new AbortController();
    setStatus((old) => ({
      kind: "loading",
      previous: old.kind === "done" ? old.data : old.kind === "loading" ? old.previous : null,
    }));
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

  // After Next / Previous, put keyboard focus on the first new result, so the keyboard does not have to
  // travel back up from the bottom of the page.
  useEffect(() => {
    if (status.kind === "done" && focusResults.current) {
      focusResults.current = false;
      document.querySelector<HTMLAnchorElement>(".result-title a")?.focus();
    }
  }, [status]);

  /** Shows a new search and records it in the browser history. */
  function go(next: SearchState) {
    window.history.pushState(null, "", href(next));
    setState(next);
    window.scrollTo({ top: 0 });
  }

  /** For links inside the page: a plain click stays in the app; Ctrl/Cmd-click still opens a new tab. */
  function follow(event: MouseEvent<HTMLAnchorElement>, next: SearchState) {
    if (!isPlainClick(event)) return;
    event.preventDefault();
    go(next);
  }

  const home = !state.query;
  const data = status.kind === "done" ? status.data : status.kind === "loading" ? status.previous : null;
  const loading = status.kind === "loading";

  return (
    <div className="page" data-view={home ? "home" : "results"}>
      <header className="masthead">
        <a className="wordmark" href={window.location.pathname} onClick={(e) => follow(e, { ...state, query: "", page: 1 })}>
          Sift
        </a>
        <div className="masthead-main">
          {home && <h1 className="home-title">Search Simple English Wikipedia</h1>}
          <SearchForm
            query={state.query}
            mode={state.mode}
            explainMode={home}
            onSearch={(query: string, mode: Mode) => go({ query, mode, page: 1 })}
          />
        </div>
      </header>

      {home ? (
        <main className="content">
          <p className="home-count">
            {documents != null && `${documents.toLocaleString("en-US")} articles, from the ${DUMP_DATE} dump.`}
          </p>
          <ul className="examples" aria-label="Example searches">
            <li aria-hidden="true">Try</li>
            {EXAMPLES.map((example) => {
              const next = { query: example, mode: state.mode, page: 1 };
              return (
                <li key={example}>
                  <a href={href(next)} onClick={(e) => follow(e, next)}>
                    {example}
                  </a>
                </li>
              );
            })}
          </ul>
        </main>
      ) : (
        <main className="content" aria-busy={loading}>
          <h1 className="visually-hidden">
            Results for “{state.query}”
          </h1>

          {!data && loading && (
            <p className="meta loading" role="status">
              Searching…
            </p>
          )}

          {data && (
            <StatsPanel
              stats={data.stats}
              loading={loading}
              summary={
                data.totalMatches === 0
                  ? "No results"
                  : `${data.totalMatches.toLocaleString("en-US")} ${data.totalMatches === 1 ? "result" : "results"}`
              }
            />
          )}

          {data?.notice && <p className="message">{data.notice}</p>}

          {data?.didYouMean && (
            <p className="message">
              Did you mean{" "}
              <a href={href({ ...state, query: data.didYouMean, page: 1 })} onClick={(e) => follow(e, { ...state, query: data.didYouMean!, page: 1 })}>
                {data.didYouMean}
              </a>
              ?
            </p>
          )}

          {status.kind === "error" && (
            <div className="error" role="alert">
              <AlertIcon />
              <div>
                <p>{status.message}</p>
                <button type="button" className="button" onClick={() => setAttempt((n) => n + 1)}>
                  Try again
                </button>
              </div>
            </div>
          )}

          {data && data.totalMatches === 0 && !data.notice && (
            <>
              <p className="message">No articles match “{data.query}”.</p>
              <ul className="empty-tips">
                <li>Check the spelling.</li>
                <li>Try fewer words, or more general ones.</li>
                {state.mode === "keyword" && (
                  <li>
                    <a href={href({ ...state, mode: "smart", page: 1 })} onClick={(e) => follow(e, { ...state, mode: "smart", page: 1 })}>
                      Search in Smart mode
                    </a>
                    , which also finds articles by meaning.
                  </li>
                )}
              </ul>
            </>
          )}

          {data && data.results.length > 0 && (
            <ResultList results={data.results} firstNumber={(data.page - 1) * data.pageSize + 1} />
          )}
          {data && data.results.length === 0 && data.totalMatches > 0 && (
            <p className="message">There are no results on this page.</p>
          )}
          {data && data.totalMatches > 0 && (
            <Pager
              page={data.page}
              pageCount={Math.min(MAX_PAGE, Math.ceil(data.totalMatches / data.pageSize))}
              hrefFor={(page) => href({ ...state, page })}
              onPage={(page, event) => {
                if (!isPlainClick(event)) return;
                event.preventDefault();
                focusResults.current = true;
                go({ ...state, page });
              }}
            />
          )}
        </main>
      )}

      {/* While the first results load, the footer waits: if it were drawn now, the results would push it
          down when they arrive, and the page would jump (a "layout shift"). */}
      {(home || data || status.kind === "error") && (
        <footer className="footer">
          <p>
            Article text from{" "}
            <a href="https://simple.wikipedia.org/" rel="noreferrer">
              Simple English Wikipedia
            </a>{" "}
            ({DUMP_DATE} dump),{" "}
            <a href="https://creativecommons.org/licenses/by-sa/4.0/" rel="noreferrer">
              CC BY-SA 4.0
            </a>
            .
          </p>
        </footer>
      )}
    </div>
  );
}
