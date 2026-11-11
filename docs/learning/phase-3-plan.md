# Phase 3 plan — the website (written before building)

## Goal
A person opens the page, types a query and understands everything without explanation: results with the
query words in bold, pages, a keyword/smart switch, and a small stats panel.

## Plan

### 1. Better snippets (engine + API)
Today a snippet is the first 200 characters of the article. New: show the part of the article where the
query words are.
- The index already stores each term's **positions** (word numbers) in every document.
- For a hit, collect the body positions of all query terms, then slide a **window of 30 words** over them
  and keep the window with the most *different* query terms (ties: most matches, then earliest).
- Positions are word numbers, but we must cut the text at *character* offsets. So the tokenizer gets a
  second output: for every word, where it starts and ends in the text. We scan the text only up to the
  end of the chosen window.
- The API returns the snippet text plus a list of `{start, end}` highlight ranges. The website makes the
  bold text from those ranges. The server never sends HTML.

### 2. Website (`web/`, React + Vite + TypeScript)
- One page: search box, keyword/smart switch, results, pagination, stats panel, footer credit.
- The query, mode and page live in the address bar (`?q=…&mode=…&page=…`), so reload, back/forward and
  sharing a link all work.
- States: first visit, loading, results, no results (with the server's hint), error (with a retry button).
- In development Vite forwards `/api/*` to the Spring server on port 8080, so there is no cross-origin setup.
- Tests (Vitest): the highlight splitter and the API client.

### 3. Done when
The full flow works in the browser against the real API; screenshots at desktop and 375 px width.

## Strict self-review

| # | Concern | Decision |
| --- | --- | --- |
| 1 | **XSS** (a page running someone else's script). Article text is untrusted. | The API sends plain text + ranges; React escapes text by default. No `dangerouslySetInnerHTML` anywhere. |
| 2 | **Highlight offsets and Unicode.** Java and JavaScript both count string positions in UTF-16 units, so the ranges mean the same thing on both sides. | Tested with accents and emoji. Ranges are sorted and never overlap; the splitter ignores bad ranges instead of crashing. |
| 3 | **Positions on disk have no per-document index.** Getting one document's positions means decoding that term's positions for all documents. For a very common word ("the") that is millions of numbers. | Decoded once per term per query and reused for all 10 hits. Stop words are dropped from most queries anyway. Measure the worst case ("the who", "to be or not to be"); it must stay under 100 ms. A per-document skip table is the later fix (needs a format change). |
| 4 | **Token numbers must match the index exactly**, or the window is in the wrong place. | The snippet code uses the *same* tokenizer function the indexer uses; a test checks that position i from the index is word i of the text. Alias words sit after the body in position space and are ignored. |
| 5 | **Out-of-order responses.** Typing fast can make an old, slow answer overwrite a newer one. | Every new search cancels the previous request (`AbortController`). |
| 6 | **Accessibility.** | Real `<form role="search">`, a visible `<label>`, radio buttons for the mode, `<nav>` for pages with `aria-current`, results announced through an `aria-live` region, visible focus rings, works with keyboard only. |
| 7 | **Mobile (375 px).** | Single column, no fixed widths, 16 px inputs (so iOS does not zoom), long words wrap. |
| 8 | **"Cache hit/miss" in the stats panel**, but the cache is Phase 6. | The panel shows "Cache: off" from the API's real value. No fake numbers. |
| 9 | **Smart mode** is not built. | The switch exists; the page shows the server's notice ("showing keyword results"). |
| 10 | **Title-only matches** have no body positions. | The snippet falls back to the start of the article. |
| 11 | **Deep pages.** | The API allows pages 1–100; the pager shows "Previous / Next" and the page number, not 100 buttons. |
| 12 | **Production hosting** of the built site and CORS. | Out of scope here; Phase 7 will serve the built files from the same origin as the API. |
