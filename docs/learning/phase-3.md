# Phase 3 — The website

## What we built, in simple words

A small web page where you type a query and see results, like a normal search engine:

- a search box and a Keyword / Smart switch,
- a list of results, each with a title link, the address and a **snippet** (a short piece of the
  article) with the query words in bold,
- Previous / Next pages,
- a stats panel: query time, documents searched, matches, cache, searched terms,
- clear messages while loading, when nothing is found and when something fails.

Two things changed behind the page:

1. **Snippets now show the part of the article where the query words are**, not just its first lines.
   The engine uses the word positions stored in the index to find the best 30-word window.
2. **The index format got a "skip table"** so reading one article's positions is fast (format version 2).

Screenshots: `docs/screenshots/phase3-desktop.png`, `docs/screenshots/phase3-mobile.png` (375 px wide).

## Each new file and what it is for

| File | Purpose |
| --- | --- |
| `engine/.../snippet/SnippetBuilder.java` | Picks the best window with a sliding window over term positions; returns text + highlight ranges. |
| `engine/.../snippet/Snippet.java` | The result: plain text and `{start, end}` ranges to show in bold. |
| `engine/.../text/Tokenizer.java` (changed) | `forEachWord` now also reports where each word starts and ends in the text. |
| `engine/.../store/IndexFileWriter.java`, `DiskIndex.java` (changed) | Skip table: one entry per 64 postings saying where their positions start. |
| `web/index.html`, `web/src/main.tsx` | The page shell and the line that starts React. |
| `web/src/App.tsx` | The page: holds the current search, calls the API, shows the right state. |
| `web/src/api.ts` | The API client: builds the address, calls `fetch`, turns failures into friendly errors. |
| `web/src/highlight.ts` | Cuts a snippet into normal and bold pieces from the ranges. |
| `web/src/urlState.ts` | Reads and writes `?q=…&mode=…&page=…` in the address bar. |
| `web/src/components/SearchForm.tsx` | Search box, button, mode switch. |
| `web/src/components/ResultList.tsx`, `Pager.tsx`, `StatsPanel.tsx` | Results, page buttons, stats. |
| `web/src/styles.css` | All styling; light and dark; one column that fits a phone. |
| `web/src/*.test.ts(x)` | 16 Vitest tests: highlighting, API client, the page flow, address-bar state. |
| `web/vite.config.ts` | Dev server on port 5173; forwards `/api/*` to the Spring server on 8080. |

## The key algorithms, with small worked examples

### 1. Best snippet window (sliding window, two pointers)
Query terms: `volcano` (term 0) and `lava` (term 1). Their positions in one article's body:
```
volcano: 3, 40, 41        lava: 44, 90
merged and sorted:  (3,v) (40,v) (41,v) (44,l) (90,l)        window = 10 words
```
For each match as the left edge, extend the right edge while matches are less than 10 words away:
```
left = 3   → window [3, 13)   holds (3,v)                   1 different term, 1 match
left = 40  → window [40, 50)  holds (40,v) (41,v) (44,l)    2 different terms, 3 matches   ← best
left = 41  → window [41, 51)  holds (41,v) (44,l)           2 different terms, 2 matches
left = 44  → window [44, 54)  holds (44,l)                  1 term
left = 90  → window [90, 100) holds (90,l)                  1 term
```
Rule: most *different* terms, then most matches, then earliest. The right pointer never moves back, so
this takes one pass over the matches. We then start the snippet up to 5 words before the first match
(fewer if that would push the last match out of the window).

### 2. From word numbers to characters
Positions are word numbers (word 40, word 41, …), but text is cut at character offsets. The tokenizer walks
the article text again, this time also reporting each word's start and end character, and stops as soon as
the window ends:
```
text:   "The volcano erupted."
words:   the → [0,3)   volcano → [4,11)   erupted → [12,19)
```
The API answers with plain text and ranges, e.g. `snippet: "The volcano erupted."`,
`highlights: [{start: 4, end: 11}]`.

### 3. Skip table for positions
On disk, one term's positions for all its documents are stored one after another. To read the positions
of the 5,000th document we would have to decode all 4,999 before it. For a common word that is millions of
numbers: "the who" took **40 ms** per query.
Now each term stores, for every 64th posting, how many bytes its positions are from the previous one:
```
postings:      0 … 63 | 64 … 127 | 128 … 191 | …
skip entries:           210        187          …        (bytes of positions in the block before)
want posting 130 → jump 210 + 187 bytes ahead, then skip only postings 128 and 129
```
"the who" now takes **5 ms**. Cost: 0.5 MB more on disk (positions 57.5 → 58.0 MB).

### 4. Bold text without HTML
```
splitHighlights("Plants make food", [{start: 0, end: 6}])
  → [{text: "Plants", bold: true}, {text: " make food", bold: false}]
```
React renders each piece as text inside `<strong>` or `<span>`. Because the server never sends HTML and
the page never inserts HTML, article text like `<script>` can only ever be *shown*, not *run*.

## Time and space complexity

h = number of matches of the query terms in one article, w = window size in words, c = characters of the
article up to the end of the window.

| Operation | Time | Space |
| --- | --- | --- |
| Find a document in a posting list | O(log L) binary search | O(1) |
| Read one posting's positions (with skip table) | O(L / 64) once for the table + at most 63 postings skipped | O(L / 64) |
| Best window | O(h log h) to sort the matches + O(h) for the two pointers | O(h) |
| Map the window to characters | O(c) | O(w) |
| `splitHighlights` | O(k log k) for k ranges | O(k) |

## Measured results (Java 21, Apple M5)

| Measure | Value | Raw file |
| --- | --- | --- |
| Query time inside the server, 7 test queries (search + 10 snippets) | 0.7–6.0 ms; slowest "to be or not to be" | `eval/results/phase3-api-speed.txt` |
| Same before the skip table | "the who" 39.9 ms, "to be or not to be" 32.9 ms | measured once by hand, not saved |
| Posting data after compression | 111.9 MB (was 111.4 MB) of 364.4 MB = 30.7% | `eval/results/phase3-index-size.txt` |
| Index build | 9.84 s | same |
| Disk vs memory on format 2 | identical for every query, both sets | `eval/results/disk-check-v2-*.txt` |
| Tests | 178 JUnit (155 engine, 23 API) + 16 Vitest | |
| Page width check at 375 px | no sideways scrolling (page width = 375) | checked in the browser |

**"Done when": ✅** searched "world war 2" and "photosynthesis" in the browser against the real API, paged
forward, screenshots at desktop and 375 px.

## Honest limits
- **Snippets come straight from the article text**, so leftovers of wiki markup or bare web addresses in
  the data show up (see the second result for "photosynthesis").
- **The window with the most matches wins**, which is not always the article's first sentence.
- **Smart mode** shows the server's notice and keyword results until Phase 5. **Cache** shows "off" until Phase 6.
- **No autocomplete in the search box yet** (Phase 4).
- **Not tested with a real screen reader**; the structure follows the usual rules (labels, roles, live region).
- **Hosting:** in development Vite forwards `/api` to the API. How the built site is served is decided in Phase 7.

## How to run it yourself
```bash
scripts/sift-index            # once (and again whenever the index format changes)
scripts/sift-api              # terminal 1: API on http://localhost:8080
npm --prefix web install      # once
npm --prefix web run dev      # terminal 2: website on http://localhost:5173
npm --prefix web test         # 16 frontend tests
```

## 5 interview questions (with short model answers)

**1. How do you choose which part of an article to show?**
The index stores word positions for every term. For a hit I merge the positions of all query terms and
slide a 30-word window over them with two pointers, keeping the window with the most different query
terms, then the most matches. That is one pass over the matches. Then I map word numbers to characters by
walking the text with the same tokenizer the index used.

**2. Your first version of this was slow. What happened and how did you fix it?**
"the who" went from 2 ms to 40 ms. Positions on disk were one long run per term, so reading one document's
positions meant decoding all earlier documents' positions, millions of varints for "the". I added a skip
table: for every 64th posting, the byte distance to its positions. Now I jump to the right block and skip
at most 63 postings: 5 ms, for 0.5 MB of extra index. I found it by measuring after the change, not by guessing.

**3. How do you prevent XSS when you show text from Wikipedia with bold words?**
The server sends plain text plus `{start, end}` ranges instead of HTML. The page splits the text at those
ranges and lets React render the pieces as text nodes, which escapes everything. There is no
`dangerouslySetInnerHTML` anywhere, and a test checks that `<b>` in a snippet stays visible text.

**4. A user types fast and two requests are in flight. How do you avoid showing the older answer last?**
Each search runs in an effect that owns an `AbortController`. When the query changes, React runs the
effect's clean-up, which aborts the old request, and the handler ignores anything from an aborted
request. So only the newest search can update the page.

**5. Why keep the search in the address bar instead of only in React state?**
Then the URL *is* the state: reload shows the same results, back and forward work, and a link can be
shared. The page reads `?q=&mode=&page=` on load and on `popstate`, and writes it with `pushState` on
every new search. It also made screenshots and tests simple: open a URL, get a result page.
