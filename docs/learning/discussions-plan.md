# Plan: definition card + "Discussions" tab

Written 2026-11-16. **Nothing is built yet. This plan waits for your "go".**
All numbers here come from real runs that day; raw files are named next to each one.

## Short version
- **Definition card:** show it when the top result's **title gives exactly the same words as the query** (after our
  own tokenizer: lowercase, stop words out, Porter stem). On the main 50 it shows for **32 / 50 queries with
  0 wrong cards**, in both Smart and Keyword mode. The text comes from our own index. Ranking is not touched.
- **Discussions:** of the four sources, only **Hacker News** fits every rule (free, no key, keyword search, no
  scraping). **Mastodon and Bluesky are dropped**: searching posts needs an account. **Wikipedia Pageviews** has no
  keyword search and no discussions; it is free, keyless and CC0, so I suggest it as **one optional line** ("viewed
  N times last month") and not as a source of items. You decide at "go".
- **Biggest risk:** Hacker News is a tech news site. For general topics its results are often off-topic ("haiku"
  finds Claude Haiku model launches). The tab will say plainly what it is. With only one real source, you may decide
  the tab is not worth building. That is a fair outcome of this research.

## 1. Source research

Checked on 2026-11-16 in the official docs, plus **one** test request per source from this laptop (no key, no
login), so the table says what really happens today, not only what the docs say.

| | Hacker News (Algolia) | Wikipedia Pageviews | Mastodon | Bluesky |
| --- | --- | --- | --- | --- |
| Keyword search? | **Yes**: `GET hn.algolia.com/api/v1/search?query=…&tags=story`, full text, filters by tag, date, points | **No.** Counts views per article and date; there is nothing to search | Status search exists, but "full text search is not available to unauthenticated users" | `app.bsky.feed.searchPosts` exists, but the official docs say it "may require authentication" |
| Free? | Yes | Yes | Yes | Yes |
| Key or login? | **None** (test: HTTP 200 without a key) | **None**, but every request **must send a User-Agent with contact info** (a URL or email) | **Needs an account** (user token with `read:search`). Test without one: `statuses: []` | **Needs an account** for reliable use. Test: the public host `public.api.bsky.app` answers **403 Forbidden** |
| Rate limit | "10,000 per hour" per IP | 200 requests/min for unauthenticated bots with a proper User-Agent (10/min without one). "New in 2026 and subject to … change". On 429, wait for `Retry-After` | (not reached) | (not reached) |
| Terms on display | No display terms in the API docs. YC's Terms of Use forbid scraping and copying site content "other than as specifically authorized". The public API is the authorized route. **Plan: titles, dates and links only; no comment or story text** | Data is **CC0** (public domain) | (dropped) | (dropped) |
| **Verdict** | **Use**, with title/date/link only | **Optional one-line extra**, not a discussion source | **Drop**: needs an account | **Drop**: needs an account; public search is blocked |

Sources:
- HN: https://hn.algolia.com/api (the rate-limit sentence is on this page, and the "Common query parameters"
  section lists `query`, `tags`, `numericFilters`). YC terms: https://www.ycombinator.com/legal/. The official HN
  API (no search) says "There is currently no rate limit": https://github.com/HackerNews/API.
- Pageviews: https://doc.wikimedia.org/generated-data-platform/aqs/analytics-api/documentation/access-policy.html
  (User-Agent rule, CC0). Limits: https://www.mediawiki.org/wiki/Wikimedia_APIs/Rate_limits.
- Mastodon: https://docs.joinmastodon.org/methods/search/.
- Bluesky: https://github.com/bluesky-social/bsky-docs/issues/332 (searchPosts returns 403; the issue is still
  open and the repo is archived since 2026-08-18). The old searchPosts reference page on docs.bsky.app now redirects to
  https://endpoints.bsky.app/.
- Note: in my one test, `api.bsky.app` (not the documented public host) did answer without a login. I am **not**
  using it. It is not the documented public route, and the same issue reports it returning 403 too since August
  2026. Building on an undocumented path is close to scraping.

**What the HN results look like** (main 50, `tags=story`, 5 per query; `eval/results/discussions-hn-probe.jsonl`):
- At least 1 story for **49 / 50** queries; 5 stories for 46.
- Time per request from this laptop: **median 802 ms, p95 1,594 ms, max 1,913 ms**. That is close to the
  2-second timeout, so expect some timeouts. They show the quiet empty state, which is why the cache matters.
- Relevance is mixed: "photosynthesis" gives "Why Are Plants Green? To Reduce the Noise in Photosynthesis" (good);
  "haiku" gives Claude model launches; "the who" gives "The man who killed Google Search?".

## 2. Definition card

### The rule (confidence)
Show the card only if **all** of these are true:
1. Page 1 of results, mode Smart or Keyword.
2. Tokenize the top result's **title** with the same shared `Tokenizer` used for the query. The **set of terms is
   exactly equal** to the query's terms, and the set is not empty.

There is no threshold to tune, so there is nothing to over-fit.

**Measured on the main 50** (`eval/card_rule_probe.py`, raw rows in `eval/results/definition-card-probe.json`).
"Wrong" means the top result is not the expected answer:

| Rule | Smart: shown / wrong | Keyword: shown / wrong |
| --- | --- | --- |
| **Title terms = query terms (chosen)** | **32 / 0** | **32 / 0** |
| Title terms ⊆ query terms | 36 / 1 | 39 / 4 |
| Keyword #1 = Smart #1 | 43 / 1 | 43 / 1 |
| Keyword score #1 ÷ #2 ≥ 1.5 | 8 / 0 | 8 / 2 |

Why not the looser rules: their wrong cards are exactly the harmful kind. "what is the capital of australia" would
show *Australia*, and "who discovered penicillin" (Keyword) would show *Penicillin*. A card claims "this is the
answer", so a wrong one is worse than none.

Honest limits:
- No card for questions ("who invented the telephone" → *Alexander Graham Bell* is right but not shown).
- No card when the title is longer than the query: "einstein" → *Albert Einstein*, "world war 2" → *World War II*
  ("2" ≠ "ii").
- These queries were also used to choose the rule, so the real rate on new queries may be a bit lower. The held-out
  set is not used, as you asked.

### What it shows and where the text comes from
- The title (a link to the article), then the first 1–2 sentences of the article from `index.text(docId)`. That is
  the article text the snippets already read, so there are **no network calls**.
- Sentences: my own small splitter. A sentence ends at `.` `!` `?` followed by a space and a capital letter, but not
  after single initials ("J. R. R."), common abbreviations ("Dr.", "St.", "U.S.", "c.", "e.g.") or a number
  ("1. "). Take sentence 1; add sentence 2 if sentence 1 is shorter than 12 words. Cap at 50 words, cutting at a
  word boundary and adding "…".
- API: `SearchResponse` gets a new **optional** field `definition: {id, title, url, text} | null`. It is computed
  after ranking, from the already-ranked top hit, so ranking cannot change. It is cached with the page.
- The top result also stays as #1 in the list below, because the list must not change. The card shows the
  article's opening sentences; the list shows the matched window.

### Proof that ranking does not change
`eval/api_results.py save/compare` (built for the highlight change) runs before and after: main 50, both modes,
top 100. Ids, scores and order must be identical, and MRR equal to 5 decimals. Any difference stops the work.

## 3. Discussions: architecture

```
browser ── GET /api/search?q=…               (unchanged, ~6 ms, never waits for anything external)
        └─ GET /api/discussions?q=…&title=…  (only when the Discussions tab is open)
                 │
                 ▼
        DiscussionsService ── Caffeine cache, 10 min, key = normalised query (+ title)
                 │  on a miss: both sources run in parallel, each with a 2 s timeout
                 ├─ HackerNewsSource  ── rate limiter ── java.net.http.HttpClient ── hn.algolia.com
                 └─ PageviewsSource   ── rate limiter ── HttpClient ── wikimedia.org   (if you approve it)
```

- **Separate endpoint, separate request.** The site calls `/api/discussions` only when the tab is opened. `/search`
  code, cache and timing are untouched. The k6 search load test is re-run to prove it.
- **No new dependencies.** Java's built-in `HttpClient` (async `sendAsync`) and Jackson, which we already have.
  The controller returns a `CompletableFuture`, so no web-server thread sits waiting on the network.
- **Timeouts:** 2 s connect and 2 s per request for each source. The whole endpoint answers within about 2.1 s, even
  if every source hangs.
- **Cache:** Caffeine, `expireAfterWrite(10 min)`, at most 2,000 entries. Empty or failed answers are cached for
  1 minute, so a broken source is not hammered. Caffeine's `get(key, loader)` means 20 people searching the same
  thing cause **one** outside request.
- **Rate limits (own code, a token bucket):**
  - HN: at most 2 requests/s, so at most 7,200/h, below 10,000/h.
  - Wikimedia: at most 1 request/s (60/min), below 200/min. On HTTP 429, that source is paused for `Retry-After`
    (or 60 s).
  - When there is no token, it returns "unavailable" at once and does not wait.
- **Circuit breaker:** 3 failures in a row → skip that source for 60 s.
- **Response:** `{query, sources: [{name, status: "ok"|"empty"|"unavailable", items: [{title, url, discussionUrl,
  date, points, comments}]}], pageviews?: {title, month, views}}`. Always HTTP 200, even when everything failed.
- **HN query:** only the meaningful words (the same `highlightTerms` filter), `tags=story`,
  `numericFilters=points>=10` (drops low-quality and spam posts), 5 items. I checked that the API accepts these
  parameters.
- **Safety filter:** HN titles are checked against a small word list I write and test (sexual and slur terms, whole
  words, case-insensitive). A match drops the item. No user text is shown, only titles. HN is moderated (flagged
  posts are hidden), so titles are low-risk, and a word list is a reasonable second layer. Bluesky and Mastodon,
  which have far more unmoderated text, are not used.
- **Pageviews line (optional):** for the top result's title, last full month,
  `per-article/simple.wikipedia/all-access/user/{title}/monthly/…`. Example (one test): *Photosynthesis*, October
  2026: 2,372 views. The User-Agent is `Sift/1.0 (+$SIFT_CONTACT_URL)`. If that env var is not set, the line is turned
  off rather than sending a User-Agent without contact info. No API keys are needed by any source; if one ever is,
  it comes from an env var.

## 4. Discussions: UI
- **A third tab, "Discussions", after Smart and Keyword**, in the same row, with the same underline style and
  tokens. URL `?q=…&mode=discussions`, so back/forward and links work.
- The group's hidden legend changes from "Search mode" to "Show", because Discussions is not a search mode (see the
  self-review). Arrow keys still move between the three, as radio buttons do.
- Contents: one quiet line, "Hacker News stories that mention these words. Sift does not rank or check them." Then a
  list. Each item: title (link to the original), then "Hacker News · 12 Mar 2024 · 247 points · 100 comments"
  (the comments link goes to the HN thread). Optionally the pageviews line at the top.
- States:
  - Loading: the same delayed "Searching…" as now.
  - Empty or failed: one quiet sentence, "No discussions found for these words." Never the red error box: a
    failing outside site is not our error.
- 375 px wide, light and dark, 44 px targets, focus rings. These are re-checked with the review scripts: checks 7,
  9–11, 16–24, 26 and 29 and screenshots.

## 5. Tests (no network in CI)
- **Java:**
  - Sentence splitter (initials, abbreviations, numbers, 50-word cap).
  - Card rule (equal sets, subset, empty, page 2).
  - API test: `definition` appears for "photosynthesis" on the fixture and is null for a question.
  - HN JSON parsing from a saved sample file.
  - Word-list filter.
  - Token bucket (with a fake clock).
  - Cache: one outside call for two identical requests.
  - Timeout: a fake source that never answers → "unavailable" within 2.2 s.
  - 429 → pause.
  - Circuit breaker.
  - `/api/discussions` always 200.
  - Sources sit behind an interface `DiscussionSource`, so tests use fakes. Nothing reaches the internet in tests.
- **Vitest:** the card, the tab and its URL, keyboard switching, loading/empty states, and that `/api/search` is
  **not** called again when switching to Discussions (and the reverse).
- **Before/after:** main-50 MRR and ids/scores identical. k6 search p95 within noise of the last run, with no
  Discussions traffic in the test.

## 6. Risks
| Risk | Effect | What I do |
| --- | --- | --- |
| HN off-topic results for non-tech words | Users see odd stories | Plain label; points ≥ 10; meaningful words only. Accept, or drop the tab |
| HN slow from the server's network (p95 1.6 s here) | Some empty states | 2 s timeout, 10 min cache; measured again after building |
| YC terms say nothing explicit about showing API results | Unclear permission | Titles, dates and links back only; no text; non-commercial. If you want certainty, ask YC or drop HN |
| Wikimedia limits "subject to change" | Line disappears | It is optional and fails quietly |
| A blocklist misses something, or blocks an innocent title | Rare bad title, or a missing item | HN moderation first; list tested; items link out, never embedded |
| Card wording looks like an answer for a vague query | Misleads | Strict exact-title rule (0 wrong of 32 here); card says "From Simple English Wikipedia" |
| Outbound calls blocked on the future host | Tab always empty | It is designed to be empty-safe; the host check is in the deployment plan |

## 7. Senior-engineer self-review (strict)
1. **Is "Discussions" honest with one source?** It is really "Hacker News mentions". Calling it "Discussions" over-
   promises. I'd rename the tab "Hacker News" or "On Hacker News", or skip the feature. **Recommendation:** build the
   card (clearly useful, 0 wrong on the main 50). Build the tab only if you accept HN-only.
2. **A tab is the wrong control in a strict sense.** Smart and Keyword change *how we rank*; Discussions changes
   *what we show*. Putting all three in one radio group mixes two ideas. A screen reader hears "Show: Discussions,
   3 of 3", which is OK, but a link under the results ("See Hacker News stories about this") would be more honest.
   I follow your spec (a third tab) and note this as a trade-off in DESIGN.md.
3. **The card rule was chosen on the same 50 queries it is measured on.** "0 wrong" is optimistic. It has no tuned
   number, which helps, but the claim in the README must say "on the main 50 (used to choose the rule)".
4. **The card duplicates result #1.** That is a small waste of space, accepted to keep the list unchanged. On phones
   it pushes result #2 down. I'll measure where result #1 starts on a 375 px screen before and after, and report it.
5. **Sentence splitting is the fragile part.** Wikipedia openings have parentheses, pronunciations and dates
   ("Napoleon Bonaparte (15 August 1769 – 5 May 1821) was…"). The tests will use real openings from 10 main-50
   articles, not invented strings.
6. **External calls make the server do work for anyone who hits `/api/discussions`.** Mitigations:
   - The cache.
   - The rate limiters cap outbound traffic, whatever the inbound traffic, so we can never break HN's limit.
   - The query length limit (200) already exists.
   - A malicious user can only make the tab empty for others for up to 1 minute.
7. **Timeouts are close to real latency.** A 2 s timeout with a 1.9 s worst case means some cold misses fail. That is
   your rule and a fine trade-off, but I won't claim "always shows results".
8. **Testing the timeout with real sleeps makes slow, flaky tests.** Use a fake clock or futures that never complete,
   plus `orTimeout`. One test takes about 2 s at most.
9. **No new dependencies is achievable.** HttpClient is in the JDK; Caffeine and Jackson are already used.
10. **Secrets:** none are needed today. The contact URL is not a secret but still goes in an env var, so personal
    contact details never land in git.

## Decisions for you at "go"
1. Discussions = HN only. Build it, rename it, or skip it?
2. Pageviews "viewed N times" line: include or not? (If yes, set `SIFT_CONTACT_URL`, e.g. the repo URL.)
3. OK with the strict card rule: 32/50 shown, no cards for questions?
