# Phase 2 plan — index on disk + API (written before building)

## Goal
Today the index lives only in memory: every start re-reads 397 MB of JSON and takes about 9 s and 562 MB
of heap. Phase 2 writes the index to our own binary files once, then **memory-maps** them at start
("memory-mapping" = the operating system makes a file look like a big array in memory and loads only the
parts we touch). On top of that sits a small Spring Boot API.

## Plan

### 1. Files (one folder, default `data/index/`)
| File | Content | How it is read |
| --- | --- | --- |
| `meta.json` | format version, counts, each file's size, whether aliases were indexed | read once (written last) |
| `dictionary.bin` | all terms, sorted; per term the offset of its blocks in the three postings files | loaded into arrays; **binary search** |
| `postings.bin` | per term: doc-id gaps, counts, position gaps, all as varints | memory-mapped; one term's block decoded per query |
| `title.bin`, `alias.bin` | per term: doc-id gaps + counts for the title / alias field | memory-mapped |
| `docs.bin` | per document: Wikipedia id, title/body/alias lengths, **offset and length of its text** | loaded into arrays |
| `titles.bin` | the titles | loaded into a `String[]` |
| `text.bin` | every article's text, one after another (UTF-8) | memory-mapped; read one article for a snippet |

### 2. Compression: gaps + varints
- **Gap encoding:** doc ids in a posting list are sorted, so store the *difference* to the previous one.
  `[3, 9, 12, 500]` → `[3, 6, 3, 488]`. Small numbers.
- **Varint** ("variable-length integer"): 7 bits of the number per byte; the top bit says "more bytes
  follow". Numbers below 128 take 1 byte instead of 4. `300` → `AC 02`.
- Positions inside one document are sorted too, so they are gap-encoded the same way.
- Report: bytes as plain 4-byte ints (before) vs file size (after), split into doc ids, counts, positions.

### 3. Code
- `VarInt` (encode/decode), `TermDictionary` (sorted terms + binary search), `IndexFileWriter`, `DiskIndex`.
- A small interface `SearchIndex` (postings, title postings, alias postings, doc table) implemented by both
  the in-memory `InvertedIndex` and `DiskIndex`, so the **same `Searcher`** runs on either.
- `scripts/sift-index` builds `data/index/` from `articles.jsonl` and prints the size report.

### 4. Correctness check
`scripts/sift-eval ... --disk DIR`: build in memory, write to disk, load from disk, run the eval on **both**
and compare the rank of the expected article for **every query** (stricter than "same MRR"). On both query
sets, for the default ranking, the baseline, and one setting with aliases (so the alias file is tested too).

### 5. API (Spring Boot 3, module `api`)
- `GET /search?q=&mode=keyword|smart&page=` → results (id, title, url, snippet, score) + stats.
- `GET /suggest?prefix=` → up to 5 titles. `GET /stats`. `GET /health`.
- Validation → friendly JSON, never a 500: empty `q`, `q` over 200 characters, bad `mode`, bad `page`,
  emoji / symbols only (valid request, zero results, with a hint).
- Tests with MockMvc against a tiny index written to a temp folder.

### 6. Done when
Server starts in under 10 s on Java 21 and `/search?q=photosynthesis` returns JSON.

## Strict self-review (as a senior engineer would)

| # | Concern | Decision |
| --- | --- | --- |
| 1 | **A Java mapped buffer is limited to 2 GB.** | Our files are far below (postings ≈ 100 MB, text ≈ 400 MB). The reader refuses bigger files with a clear error instead of failing strangely. Sharding (stretch goal) is the real fix. |
| 2 | **Thread safety.** Spring serves requests on many threads; a `ByteBuffer` has one shared read position. | Every read works on its own `duplicate()` (same bytes, private position). Nothing is written after start. |
| 3 | **Half-written or wrong-version index.** | `meta.json` is written last and stores the format version and every file's size; `DiskIndex.open` checks all of them. |
| 4 | **Binary search needs one sort order.** Java `String` order (UTF-16) differs from UTF-8 byte order for some characters. | Sort and search both compare **unsigned UTF-8 bytes**. Tested with non-ASCII terms. |
| 5 | **Corrupt varint** (never-ending "more bytes" bit). | Decoder throws after 5 bytes. |
| 6 | **No skip pointers:** a query decodes each term's whole doc-id list. "the" has ~280k entries. | Fine at this size (measure it); positions are *not* decoded for ranking, only on demand. Skip lists are a known later optimisation, not needed for the 100 ms target. |
| 7 | **Aliases.** The locked ranking has the alias bonus off, and indexing alias words changes what matches. | The format and writer support aliases (`alias.bin`, alias lengths); the default build leaves them out so results equal the measured setting. The round-trip is verified *with* aliases too. |
| 8 | **`text.bin` is ~400 MB uncompressed.** | Good enough for snippets now (it is not in the Java heap). It will matter for the Docker image and Cloud Run memory in Phase 7; options then: compress per article, or keep only the first N words. Noted in PROGRESS.md. |
| 9 | **`/suggest` and `mode=smart` belong to later phases.** | `/suggest` returns titles starting with the prefix, alphabetically (binary search over sorted titles, own code); Phase 4 replaces it with the trie and popularity. `mode=smart` answers with keyword results and says so in the JSON, until Phase 5. No fake features. |
| 10 | **Snippets.** Phase 3 wants the best window using positions. | Phase 2 returns the first ~200 characters of the article. Positions and text offsets are on disk, so Phase 3 needs no format change. Alias words sit *after* the body in position space; snippet code must ignore positions ≥ title + body length. |
| 11 | **Deep pages** (`page=100000`) would build a huge heap. | `page` is limited to 1–100 (10 results each). |
| 12 | **Spring Boot Gradle plugin vs Gradle 9.9.** | Use Spring's dependency list (BOM) with the plain `application` plugin now: fewer moving parts. The runnable "fat jar" is only needed for Docker in Phase 7. |
| 13 | **"Same MRR" could hide differences.** | The check compares every query's rank and top result, and fails loudly on any difference. |
| 14 | **Startup must stay under 10 s.** | Only the dictionary, doc table and titles are read at start (tens of MB); everything else is mapped lazily. Measured from the Spring log. |
