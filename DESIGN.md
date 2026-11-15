# Sift — design decisions and trade-offs

Each decision says what was chosen, what else was considered, and the measured numbers behind it.
Raw files are in `eval/results/` and `loadtest/results/`. MRR is on the 50 main queries unless it says
"held-out". The main 50 were used for tuning, so their numbers are optimistic; the held-out v2 set (30 queries)
was run once at the end.

## 1. Ranking: BM25 sum instead of BM25F
Both let a title match count more than a body match. They differ in **where the saturation happens**:
- **BM25F**: add up title and body counts first (title × w), then saturate once.
- **BM25 sum**: score the body and the title separately, each saturated, then add: BM25(body) + w × BM25(title).

| Ranking | MRR | Raw file |
| --- | --- | --- |
| Baseline BM25, one field (title = first words of the body) | 0.275 | `baseline-bm25.txt` |
| Best BM25F (w = 5, b_body = 0.5), from an 8-setting grid | 0.635 | `grid-bm25f.txt` |
| Best BM25 sum (w = 2, b_body = 0.3), from a 12-setting grid | **0.799** | `grid-sum.txt` |

Why the sum wins here: articles are short (median 72 words), and the article *about* a thing has the thing in its
title. In BM25F a long article that repeats a word in the body reaches saturation anyway, so the title adds little.
In the sum, the title match is its own saturated score that a body cannot imitate. Also chosen: body b = 0.3
(lighter length normalisation, because short articles were favoured too much) and dropping question words
(who/what/how…) from queries, which raised MRR 0.799 → 0.815 (`missing-word.txt`).

## 2. PageRank weight α = 0.5 (the plan said start at 0.2)
Final score = BM25 × (1 + α × normalised PageRank).

| α | 0 | 0.1 | 0.2 | 0.5 |
| --- | --- | --- | --- | --- |
| MRR | 0.815 | 0.843 | 0.843 | **0.868** |

`pagerank-alpha.txt`. 0.5 fixed the remaining "famous thing" queries (diagnostic 0.889 at α = 0.2 → 1.000) and made no query worse
(10 better, 0 worse vs α = 0).
Held-out check, not used to choose: 0.697 → 0.763 (`pagerank-holdout-sanity.txt`). PageRank: d = 0.85,
37 rounds to a change < 1e-6, 1.5 s for 3.9 million links.

## 3. Redirect aliases: tried twice, not adopted
Indexing redirect titles ("USA" → United States) as an extra field. First try: +0.002 MRR. Phase 6 re-test with a
rule fixed in advance (keyword +0.01 and hybrid not −0.01): keyword 0.86760 → 0.87519 (+0.0076), so **not adopted**
(`phase6-alias-wa*.txt`). It does fix "usa" (#1,702 → #33) and Canberra (#42 → #3), but the rule was set before the
run, and a rule that bends after the result is not a rule.

## 4. Index format: own binary files, gap + varint, memory-mapped
| Part | Plain ints | Gap + varint |
| --- | --- | --- |
| Posting lists (doc ids, counts, positions, titles) | 364.4 MB | **111.9 MB (30.7%)** |
| Whole index folder (incl. 267 MB of article text for snippets) | | 413.7 MB |

`phase4-index-build.txt`. Sorted doc ids are stored as differences ("gaps"): small numbers fit in 1 byte with
varint. The files are memory-mapped (`FileChannel.map`), so opening takes 0.05 s and the OS loads pages when they
are used. The Java heap stays small (275 MB after GC).

## 5. Meaning model: bge-small-en-v1.5 on "title + 60 words" instead of MiniLM on 200 words
The first version (all-MiniLM-L6-v2, title + 200 words) failed its check: Photosynthesis #4 for "how do plants
make food" (needed top 3). One pre-registered experiment (rules committed before running) compared 5 variants with
exact search, int8 vectors, main 50 (`fix-variants.txt`):

| Variant | Meaning only | Hybrid, plain RRF |
| --- | --- | --- |
| A. MiniLM, 200 words | 0.665 | 0.868 |
| B. MiniLM, 60 words | 0.885 | 0.928 |
| C. MiniLM, 60 + 200 words, max | 0.894 | 0.932 |
| **D. bge-small, 60 words, query prefix** | 0.957 | **0.956** |
| E. bge-small, 60 + 200 words, max | 0.955 | 0.944 |

- **Shorter text** helped: an average of many topics is close to none of them (A → B).
- **A retrieval-trained model** helped most (B → D). bge expects an instruction in front of queries only.
- **Two vectors** added little or hurt (D → E).

Cost of bge: query embedding median 2.5 ms instead of 1.0 ms, and the model file is 127 MB instead of 87 MB. My
WordPiece tokenizer needed no change (same vocabulary); the Java vectors match Python with cosine 1.0000000.

## 6. Fusion: plain RRF, k = 60
score = Σ 1 / (60 + rank) over the keyword top 100 and the meaning top 100. RRF uses only ranks, so BM25 scores
(5–40) and cosines (0.2–0.8) never need a common scale. Keyword weight 2 was tested and lost for every variant
(D: 0.956 → 0.934), mainly on questions, once the meaning list was good.

## 7. int8 vectors
One byte per number plus one scale per vector.

| | float32 | int8 |
| --- | --- | --- |
| Size | 411.3 MB | **103.9 MB** |
| Brute-force recall@10 vs float32 | 1.000 | 0.986 (rule ≥ 0.97) |
| Hybrid MRR through HNSW | 0.956 | 0.956 |
| Brute-force time | 24.8 ms | 30.3 ms |

`vector-recall-bge60.txt`, `smart-main-bge60.txt`. int8 is not faster in Java (reading single bytes from a
ByteBuffer costs more than it saves), but it saves 307 MB of memory. That decides the cloud instance size.

## 8. HNSW with efSearch = 400
Rule, fixed before measuring: the smallest efSearch with recall@10 ≥ 0.95 against exact search (500 random title
queries). Graph: M = 16, efConstruction = 200, built in 207 s, 36.8 MB.

| efSearch | 50 | 100 | 200 | 300 | **400** | 600 |
| --- | --- | --- | --- | --- | --- | --- |
| recall@10 | 0.830 | 0.890 | 0.932 | 0.944 | **0.957** | 0.970 |
| median ms | 0.26 | 0.45 | 0.81 | 1.16 | **1.51** | 2.13 |

Exact brute force is 24.8 ms, so HNSW is 16× faster at 96% recall. That matters under load: 10 concurrent Smart
queries would need 248 ms of CPU for brute force alone. The 4% of neighbours HNSW misses do not change the final
ranking here (MRR 0.956 for both).

## 9. Cache: Caffeine, 10,000 finished pages
Key = mode + page + query; value = the finished page (results, snippets, "did you mean"). The index never changes
while the server runs, so entries never go stale and there is no expiry. Hit: 0.006 ms in the server, about
0.65 ms over HTTP. Miss: 2.8 ms keyword, 6.0 ms Smart (`phase6-cache.json`). It is bounded: at most about 100 MB,
inside the 512 MB heap. Trade-off: the cache only helps repeated queries. With the cache off, laptop capacity is
about 1,700 keyword or 390 Smart requests per second (`loadtest/results/`).

## 10. Smart is the website's default mode
Rule fixed before the held-out run: Smart becomes the default if its held-out MRR ≥ keyword − 0.01.
Result: 0.857 vs 0.774, so **Smart**. The API's own default (no `mode` parameter) stays `keyword`.

## 11. Packaging: one container, data baked into the image
One Spring Boot jar (Spring Boot Gradle plugin: `bootJar`) serves both the website (built by Vite, copied into the
jar's `static/` folder) and the API. Every endpoint answers at `/search` and `/api/search`, so the site and the API
share one address and need no CORS setup.

The index, vectors, graph and model (~690 MB) are not in git. Where does the server get them?

| Option | For | Against |
| --- | --- | --- |
| **A. Bake into the image** (chosen) | Simplest: `docker build` copies `data/` in; nothing to download at startup, starts in 2.3 s; works the same on any host; no buckets or keys | Big image (553 MB compressed, 1.1 GB unpacked): slower pushes and first pull; changing data means a new image (fine: the data only changes when I rebuild it) |
| B. Download from cloud storage at startup | Small image; data can change without a rebuild | Needs a bucket, access keys or IAM, and a ~700 MB download on every cold start (seconds to tens of seconds); one more thing that can fail |
| C. Build the data in CI or at startup | Nothing stored anywhere | Not practical: embedding takes ~17 min on a GPU and hours on CPU; the dump download is 360 MB |

**A** wins because the data is static and the size is fine for both hosts. The build context is an allowlist
(`.dockerignore`), so the 2+ GB of experiment vectors and the dump are never sent to Docker.

Image layers: index 433 MB, vectors + graph 148 MB, model 133 MB, jar 73 MB, Java 21 runtime base. Multi-stage
build: Node builds the site → JDK builds the jar → the final image has only the JRE (no compiler, no Node), and runs
as a non-root user. Build for x86 servers with `docker buildx build --platform linux/amd64` (my laptop is ARM).

## 12. Memory: 512 MB heap, 1 GiB container
The index and vectors are memory-mapped (outside the heap); the heap holds the dictionary, trie, BK-tree, HNSW
graph and the cache. Measured in Docker (`phase7-docker.json`): 795 MiB at start, 900 MiB after 1,200 queries. With
a **1 GiB limit** under a 10-user load test: 836 MiB, no out-of-memory kill, 0 errors (keyword 1,901 req/s, Smart
441 req/s). Mapped file pages can be dropped by the OS and re-read, so the container fits in 1 GiB. 2 GiB would
leave headroom.

## 13. CI
GitHub Actions on every push: Java build + 269 tests (`./gradlew build -Pci`) and the website's type check,
42 Vitest tests and build. One test, `RealDataSmokeTest`, needs the real data, which is not in git. It is tagged
`needs-data` and left out with `-Pci`; locally it runs and checks the known answers on the real index and model.

## 14. Website design decisions (redesign, 2026-11-16)
Two directions were prototyped and screenshotted at 1280 and 375 px (`docs/design/directions.md`): **A, Reference**
(a reading page, like a well-set encyclopedia) and **B, Catalogue** (dense rows, monospace numbers, like a library
index). A was chosen, with one idea taken from B. The 30-point review with every measurement is in
`docs/design/review.md`.

**Type: a serif for reading, a sans for controls, system fonts only.**
- Titles and snippets use a serif (Charter, then Cambria/Georgia on other systems). People read snippets as
  short passages of text, and a book face with clear letter shapes reads well at 16 px and 1.6 line height.
- The search box, tabs, stats line and footer use the system sans (`system-ui`). They are controls, not reading,
  and a sans makes them look different from the content.
- **No web fonts.** Fonts already on the device cost 0 bytes and never swap after load, so there is no layout
  shift from fonts (measured CLS: 0 on every page). The cost: the serif looks a little different on Windows and
  Android than on a Mac.
- **Monospace with tabular numbers only inside "Details"** (the idea from B). In that list the numbers sit in a
  column, and equal-width digits make "6 ms" and "24 ms" line up. Everywhere else it would look like a terminal.
- Six sizes in a scale (13 → 33 px). The input is 16 px, because iOS zooms the page in on smaller inputs.

**Palette: warm paper and ink, one blue, one yellow.**
- Paper `#faf8f3`, ink `#1d1b17`, a second ink `#57534a` for the quiet parts, link blue `#1a4c8b`. Dark theme:
  the same roles, re-mixed (`#171612` paper, `#eae6dc` ink, `#9cbcf0` links). Only colours change between themes.
- Contrast, computed from `tokens.css` with the WCAG formula: body text 16.2:1 (light) and 14.5:1 (dark); the
  weakest text pair in either theme is 6.6:1 (placeholder, dark), above the 4.5:1 rule. Borders 3.5:1 / 3.4:1
  (need 3:1).
- **Matched words: pale yellow background, normal weight.** Bold on every match made snippets with many matches
  hard to read, and helper words like "do" were also marked. Now only meaningful words are marked
  (decision below), and the yellow was made a little stronger to work without bold: text on it is 12.1:1 / 6.9:1.
- No gradients, shadows or cards. Rules (1 px lines) and space separate things.

**Layout: one reading column.**
- Desktop: a left column for the wordmark (8.5rem), then a 40rem reading column (about 70 characters per line, a
  comfortable length). Search box, tabs, results and pager all share that column's left edge.
- Phone (below 48rem): one column, 16 px side margins. The first result starts about 250 px from the top instead of
  about 490 px before, because the result count and time are one quiet line and the other stats moved into
  "Details".
- Every tap target is at least 44 × 44 px (measured: 0 failures at 375 px), including in-text links, which get
  invisible padding instead of a bigger font.
- Every value in the CSS comes from a token in `tokens.css` (69 tokens). A Vitest test fails if a raw colour, size
  or time appears outside `tokens.css`, if a token is unused, or if a CSS class is not used by any component.

**Highlight change on the API side (the only non-visual change).** `SnippetBuilder.highlightTerms` drops stop words,
question words and common helper words ("do", "can", "from" …) from the words to highlight. Ranking still uses all
query words. Checked on the main 50 queries in both modes, top 100 results: ids, scores and order identical; MRR
0.86758 → 0.86758 (keyword) and 0.95622 → 0.95622 (Smart) (`eval/results/highlight-change.txt`).

## 15. "Discussions" tab: researched, not built (2026-11-16)
The idea was a third tab showing outside discussions of the query, fetched by our server. The research and the
full design are kept as evidence in `docs/learning/discussions-plan.md`. It was dropped for three reasons:

1. **Only one free source fits the rules** (free, no key or account, real keyword search, no scraping):

   | Source | Keyword search | Key / account | Verdict |
   | --- | --- | --- | --- |
   | Hacker News (Algolia API) | Yes | None; "10,000 per hour" per IP | Usable (titles, dates and links only) |
   | Wikipedia Pageviews | **No**, it counts views per article | None (User-Agent with contact required); data CC0 | Not a discussion source |
   | Mastodon | Only for logged-in users ("full text search is not available to unauthenticated users") | Account | Dropped |
   | Bluesky | `searchPosts` "may require authentication"; the public host answered **403** | Account | Dropped |

   One source makes "Discussions" really "Hacker News mentions".
2. **Off-topic results for general queries.** Hacker News is a tech news site. One test request per main-50 query
   found a story for 49 of 50, but for general words the stories are about something else: "haiku" → AI model
   launches, "the who" → "The man who killed Google Search?" (`eval/results/discussions-hn-probe.jsonl`).
3. **Slow.** From this laptop: median 802 ms, p95 1,594 ms, max 1,913 ms per request. That is against Sift's
   ~6 ms searches, and close to the planned 2-second timeout, so some cold requests would show an empty state.

Built instead: the definition card and "Related articles" (§16), both from our own index with no network calls.
