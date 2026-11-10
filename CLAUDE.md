# Sift — Search Engine From Scratch

## What we are building
Sift is a search engine over ~250,000 Simple English Wikipedia articles.
A user types a query and gets the best 10 results in under 100 ms.
It is a portfolio project for a Google Software Engineering internship application,
so code quality, measured results, and my understanding of every line all matter.

Final demo features:
- Search box with autocomplete (suggestions while typing)
- "Did you mean…?" spelling correction
- Ranked results with highlighted snippets and pagination
- Two modes: Keyword (BM25 + PageRank) and Smart (hybrid keyword + meaning)
- Stats panel: query time in ms, documents searched, cache hit/miss
- Public live link, README with real measured numbers, demo GIF

## About me (how to work with me)
- I am a student. I know Java basics. I am new to search engines.
- I must be able to explain every line in an interview. Teaching matters as much as building.
- Explain in very simple language. If you use a technical term, explain it right away.
- Before writing code for a feature: explain the idea in 3–5 bullets, then implement.
- After each feature: tell me what the code does, why the important parts exist, and how to test it.

## Hard rules
1. **Write the core algorithms by hand.** Do NOT use Lucene, Elasticsearch, Solr, OpenSearch,
   Tantivy, FAISS, hnswlib, jvector, or any search/vector-search library. These must be my own code:
   tokenizer, stemmer (port the Porter algorithm), inverted index, posting-list intersection,
   BM25, top-K heap, PageRank, trie, edit distance, BK-tree, cosine similarity, HNSW, RRF.
   Libraries are fine for plumbing: Spring Boot, Jackson, Caffeine, ONNX Runtime, JUnit, React.
2. **Work one phase at a time.** Finish the current phase, show me the "Done when" check passing,
   then STOP and wait for me. Never start the next phase on your own.
3. **Tests for every algorithm** (JUnit 5). Run them before saying a step is done.
4. **Never invent numbers.** Latency, MRR, index size and throughput only come from real runs.
   Save raw results in `eval/results/` and `loadtest/results/`.
5. **Small commits** with clear messages, e.g. "Add BM25 scoring with tests". Commit at the end of each step.
6. **No secrets in code.** Use environment variables.
7. Keep the tokenizer in ONE shared class used by both indexing and querying.
8. If something in this plan is wrong or there is a clearly better option, tell me why before changing it.

## Learning notes (required every phase)
At the end of each phase, write `docs/learning/phase-N.md` containing:
- What we built, in simple words
- Each new file and what it is for
- The key algorithm explained with a small worked example
- Time and space complexity of the main operations
- 5 interview questions about this phase, with short model answers
Also update `PROGRESS.md`: phase status, what was done, open issues, next step.

## Tech stack
| Part | Tool |
| --- | --- |
| Engine core | Java 21 |
| Build | Gradle (Kotlin DSL), multi-module |
| API | Spring Boot 3 |
| Website | React + Vite + TypeScript |
| Data prep + embeddings | Python 3.11+ (one-time scripts) |
| Embedding model | sentence-transformers/all-MiniLM-L6-v2 (384 dims), exported to ONNX |
| Model at query time | ONNX Runtime for Java |
| Cache | Caffeine |
| Index storage | Own binary files, memory-mapped (gap + varint encoded postings) |
| Tests | JUnit 5; k6 for load tests |
| Packaging / CI | Docker; GitHub Actions |
| Hosting | Google Cloud Run (min instances = 1), Render as fallback |

## Folder layout
```
sift/
  data-prep/   Python: parse Wikipedia dump, export ONNX model, build embeddings
  engine/      Java: tokenizer, index, BM25, PageRank, trie, spelling, HNSW
  api/         Spring Boot: /search, /suggest, /stats, /health
  web/         React + Vite website
  eval/        50 test queries, expected answers, MRR script, results
  loadtest/    k6 scripts and results
  docs/learning/  phase notes for me
  data/        downloaded and generated data (git-ignored)
  CLAUDE.md, PROGRESS.md, README.md, DESIGN.md, Dockerfile, .github/workflows/ci.yml
```
`data/` must be in `.gitignore`. Never commit the dump, index files, or vectors.

## Phases

### Phase 0 — Setup
- Check Java 21, Node 20+, Python 3.11+, Git are installed; tell me how to install anything missing.
- Create the folder layout, Gradle multi-module build, `.gitignore`, empty README, PROGRESS.md.
- Download `simplewiki-latest-pages-articles.xml.bz2` from dumps.wikimedia.org into `data/`.
- Python script: stream-parse the dump into `data/articles.jsonl`, one line per article:
  `id, title, text (plain, wiki markup removed), links (titles of linked articles)`.
  Skip redirects, disambiguation, and non-article namespaces.
- **Done when:** `articles.jsonl` exists with ~200k+ articles; print count and 2 sample lines.

### Phase 1 — Keyword search in the terminal
- Tokenizer: lowercase, split on non-letters/digits, stop-word list, Porter stemmer (own code).
- In-memory inverted index: term → sorted postings (docId, termFrequency, positions).
- Document stats: length per doc, average length, total docs.
- BM25 (k1 = 1.2, b = 0.75) with IDF = ln((N − n + 0.5)/(n + 0.5) + 1).
- AND queries via two-pointer intersection of sorted posting lists; OR fallback if AND is empty.
- Top-K with a size-10 min-heap.
- CLI: type a query, print top 10 titles with scores and time taken.
- **Done when:** sensible results for "photosynthesis", "world war 2", "python programming"
  in under 200 ms each, and all unit tests pass.

### Phase 2 — Persist the index + API
- Write the index to binary files: dictionary, postings (gap + varint encoded), doc table.
- Load with memory-mapping (FileChannel.map). Report index size before vs after compression.
- Spring Boot endpoints:
  - `GET /search?q=&mode=keyword|smart&page=` → results (id, title, url, snippet, score) + stats
  - `GET /suggest?prefix=` → up to 5 suggestions
  - `GET /stats` → doc count, index size, cache stats
  - `GET /health`
- Validate input: empty, very long (>200 chars), emoji, nonsense → friendly JSON, never a 500.
- **Done when:** server starts in under 10 s and `/search?q=photosynthesis` returns JSON.

### Phase 3 — Website
- React + Vite + TypeScript. Clean, simple design. Works on mobile width.
- Search box, results list, bold highlighted query words in snippets (best window using positions),
  pagination, mode toggle, stats panel, loading and empty states.
- **Done when:** I can use it end to end in the browser without explanation.

### Phase 4 — PageRank, autocomplete, spelling
- PageRank offline from article links: d = 0.85, iterate until change < 1e-6 or 50 rounds,
  handle dangling pages. Save scores to a file.
- Final keyword score = BM25 × (1 + α × normalised PageRank); α configurable, start at 0.2.
- Trie of titles + frequent terms; each node stores its top 5 completions → `/suggest`.
- Edit distance (DP) + BK-tree over the vocabulary; suggest the most frequent word within distance 2.
- **Done when:** typing "pyt" shows suggestions and "pyhton" suggests "python".

### Phase 5 — Smart (meaning) search
- Python: export all-MiniLM-L6-v2 to ONNX (+ tokenizer files); embed title + first ~200 words of
  each article; save normalised float32 vectors to a binary file.
- Java: embed the query with ONNX Runtime; brute-force cosine (dot product of normalised vectors) top-K.
- Hybrid mode: Reciprocal Rank Fusion, score = Σ 1/(60 + rank) over the keyword and meaning lists.
- Then implement simplified HNSW myself (M, efConstruction, efSearch configurable).
  Compare with brute force: latency and recall@10 on 200 random queries.
- **Done when:** "how do plants make food" returns Photosynthesis in the top 3 in Smart mode,
  and the HNSW vs brute-force comparison table is saved.

### Phase 6 — Prove quality and speed
- `eval/queries.json`: 50 queries with the expected best article (help me write them).
- Script computing MRR and precision@10 for keyword, keyword+PageRank, smart, hybrid.
- Caffeine cache for repeated queries.
- k6 load test: report p50/p95/p99 latency and requests per second.
- **Done when:** a results table with real numbers exists in `eval/results/` and `loadtest/results/`.

### Phase 7 — Ship it
- Multi-stage Dockerfile; GitHub Actions: build + test on every push.
- Deploy to Google Cloud Run (min instances 1, enough memory for the index); health check.
  Before deploying, tell me the expected monthly cost and wait for my OK.
- README: one-line pitch, live link, demo GIF placeholder, results table, architecture diagram
  (Mermaid), "What I built myself", run locally in 3 commands.
- DESIGN.md: key decisions and trade-offs with numbers.
- **Done when:** the public link works on my phone on mobile data.

### Stretch (only if I ask)
- Shard the index into 4 parts; a coordinator queries shards in parallel and merges top-10 lists.
- Polite crawler for 2–3 documentation sites that respects robots.txt and rate limits.