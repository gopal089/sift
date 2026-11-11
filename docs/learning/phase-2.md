# Phase 2 — Index on disk + API

## What we built, in simple words

Before, the search engine had to re-read all 280,748 articles and rebuild the index in memory every time
it started: about 9 seconds and 562 MB of Java memory. Now:

1. **`scripts/sift-index`** builds the index once and writes it to our own binary files in `data/index/`.
   The posting lists are **compressed** to 30.6% of their size.
2. **`DiskIndex`** opens those files in 0.04 s. It loads only small tables and **memory-maps** the big
   files. Memory-mapping means: the operating system makes a file look like one huge array, and reads a
   piece from disk only when we touch it.
3. The **same `Searcher`** runs on the disk index. We proved it gives exactly the same results as the
   in-memory index for every eval query.
4. A **Spring Boot API** answers `/search`, `/suggest`, `/stats` and `/health`, and replies to bad input
   with friendly JSON instead of crashing.

Result: the server starts in **0.7 s** and `/search?q=photosynthesis` returns JSON in under 1 ms.

## Each new file and what it is for

| File | Purpose |
| --- | --- |
| `engine/.../store/VarInt.java` | Write/read a number in 1–5 bytes instead of always 4. |
| `engine/.../store/IndexFileWriter.java` | Writes the index folder; gap + varint compression; size report. |
| `engine/.../store/TermDictionary.java` | All terms sorted in flat arrays; binary search → where the term's postings are. |
| `engine/.../store/DiskIndex.java` | Opens the folder, memory-maps the big files, decodes one term's postings on demand. |
| `engine/.../store/IndexMeta.java` | `meta.json`: format version, counts, file sizes, before/after compression. |
| `engine/.../index/SearchIndex.java` | Small interface that both the in-memory index and `DiskIndex` implement. |
| `engine/.../suggest/TitleSuggester.java` | Phase 2 autocomplete: binary search over sorted titles. |
| `engine/.../cli/IndexBuildCli.java`, `scripts/sift-index` | Build the index and print the size report. |
| `api/.../SiftApplication.java` | Starts Spring Boot. |
| `api/.../EngineConfig.java` | Opens the index and creates the `Searcher` once at startup. |
| `api/.../SearchController.java` | The four endpoints and the input checks. |
| `api/.../ApiExceptionHandler.java`, `BadRequestException.java` | Every error → the same JSON shape; 400 / 404 / 405 / 500. |
| `api/.../ApiModels.java` | The JSON shapes (Java records). |
| `scripts/sift-api` | Start the server on Java 21. |
| `eval/results/phase2-index-size.txt`, `disk-check-*.txt`, `phase2-api-check.txt` | Raw measurements. |

## The index folder

| File | Size | Content | At startup |
| --- | --- | --- | --- |
| `meta.json` | tiny | version, counts, every file's size | read and checked |
| `dictionary.bin` | 19.2 MB | sorted terms + 3 offsets each | loaded into arrays |
| `postings.bin` | 109.2 MB | per term: doc ids, counts, positions | memory-mapped |
| `title.bin` | 2.2 MB | per term: doc ids + counts in titles | memory-mapped |
| `alias.bin` | 0 MB | same for redirect aliases (empty: the alias bonus is off) | memory-mapped |
| `docs.bin` | 6.4 MB | per document: Wikipedia id, 3 field lengths, **where its text starts** | loaded into arrays |
| `titles.bin` | 4.7 MB | the titles | loaded |
| `text.bin` | 267.0 MB | all article texts, one after another | memory-mapped |

Total 408.7 MB on disk.

## The key algorithms, with small worked examples

### 1. Gap encoding
Doc ids in a posting list are sorted, so we store the difference to the previous one:
```
doc ids:  3   9   12   500
gaps:     3   6    3   488        (3−0, 9−3, 12−9, 500−12)
```
To read, add them back up: 3, 3+6 = 9, 9+3 = 12, 12+488 = 500. Common words have small gaps.
Positions inside one document are sorted too: `[0, 14, 15]` → `[0, 14, 1]`.

### 2. Varint (variable-length integer)
A normal `int` always takes 4 bytes. A varint uses 7 bits per byte for the number; the 8th (top) bit
means "another byte follows".
```
5    = 0000101               → 05           1 byte
300  = 10 0101100            → AC 02        2 bytes
       low 7 bits 0101100 + "more" bit → 1010 1100 = AC;  remaining bits 10 → 0000 0010 = 02
decode AC 02:  (AC & 7F) = 44,  + (02 << 7) = 256  → 300
```
So `[3, 9, 12, 500]` → gaps `[3, 6, 3, 488]` → bytes `03 06 03 E8 03`: 5 bytes instead of 16.

### 3. One term's block in `postings.bin`
For "cat" in doc 0 (positions 0, 2) and doc 1 (position 5):
```
df=2 | doc gaps: 0 1 | counts: 2 1 | position gaps: 0 2 | 5
```
Ranking reads only the first three parts. Positions are decoded only when asked for (snippets, Phase 3).

### 4. The dictionary: binary search instead of a hash map
All terms sit sorted in one byte array; three arrays hold each term's file offsets.
```
terms:   [ant, bee, cat, dog, emu, fox, gnu]      looking for "dog"
step 1:  middle = cat   → "cat" < "dog" → right half
step 2:  middle = emu   → "emu" > "dog" → left half
step 3:  middle = dog   → found, offset = 1,234 → jump there in postings.bin
```
619,144 terms need at most 20 steps. Terms are compared by their UTF-8 bytes, the same order they were
sorted in when writing.

### 5. A request, end to end
`GET /search?q=the history of india&page=1`
1. Controller checks the input (not empty, ≤ 200 characters, mode, page 1–100).
2. Tokenizer → `[histori, india]`.
3. For each term: binary search in the dictionary → decode its block from `postings.bin` and `title.bin`.
4. Intersect, score (BM25 body + 2 × BM25 title), keep the top 10 in the heap.
5. For each hit: read the first bytes of its text from `text.bin` (offset from `docs.bin`) → snippet.
6. Return JSON with results and stats.

## Time and space complexity

V = unique terms (619k), L = a term's posting list length, N = documents, K = results wanted.

| Operation | Time | Space |
| --- | --- | --- |
| Varint encode / decode one number | O(1) (1–5 bytes) | 1–5 bytes |
| Write the index | O(V log V) to sort terms + O(all postings) | files only |
| Open the index | O(V + N) (dictionary, doc table, titles) | O(V + N) heap; big files stay on disk |
| Term lookup | O(log V) comparisons | O(1) |
| Decode one posting list (ids + counts) | O(L) | O(L), freed after the query |
| Query | O(Σ log V + Σ L + matches × log K) | O(shortest list) for AND |
| Snippet (start of the text) | O(snippet length) | O(snippet length) |
| Suggest by prefix | O(log N + results) | O(N) ints |

## Measured results (Java 21, Apple M5)

**Compression** (`eval/results/phase2-index-size.txt`): plain 4-byte ints vs gap + varint

| Part | Before | After | After / before |
| --- | --- | --- | --- |
| Doc ids | 89.3 MB | 29.4 MB | 33.0% |
| Counts (tf) | 89.3 MB | 22.3 MB | 25.0% |
| Positions | 180.5 MB | 57.5 MB | 31.8% |
| Title postings | 5.3 MB | 2.2 MB | 40.7% |
| **Total posting data** | **364.4 MB** | **111.4 MB** | **30.6%** |

Counts compress best: almost every count is below 128, so 4 bytes become 1 (exactly 25%).

**Before vs after Phase 2**

| Measure | In-memory (Phase 1) | On disk (Phase 2) |
| --- | --- | --- |
| Time until searches can run | 8.93 s (build) | **0.04 s** to open the index; **0.71–0.73 s** for the whole server |
| Java heap | 562.3 MB after GC | 104 MB in use after 32 queries (not after a forced GC), `-Xmx512m` |
| Process memory (RSS) | — | 260 MB |
| Build the index files (once) | — | 9.90 s |

**Same results from disk** (`eval/results/disk-check-main.txt`, `disk-check-holdout.txt`): for every query
the rank of the expected article, the number of matches and the top result are identical to the
in-memory index. MRR 0.815194 = 0.815194 (50 queries) and 0.697350 = 0.697350 (20 held-out).
Checked for the default ranking, the old baseline, and a setting with aliases (so `alias.bin` is tested).

**API** (`eval/results/phase2-api-check.txt`): `/search?q=photosynthesis` → 200 JSON, *Photosynthesis*
first; time inside the server 0.3–2.2 ms for the five test queries. **172 tests pass** (150 engine, 22 API).

**"Done when": ✅** server starts in under 10 s (0.7 s) on Java 21 and `/search?q=photosynthesis` returns JSON.

## Honest limits
- **`/suggest` is a simple version**: titles that start with the prefix, alphabetically ("pyt" →
  Pythagoras … Python). The trie ranked by popularity comes in Phase 4.
- **`mode=smart` returns keyword results** and says so in `notice`, until Phase 5.
- **Snippets are the start of the article**, not the best window around the query words (Phase 3).
  Positions and text offsets are already on disk, so no format change is needed.
- **No skip pointers:** each query decodes its terms' whole doc-id lists. Fast enough here (the slowest
  test query is 2.2 ms).
- **`text.bin` is 267 MB uncompressed.** Fine locally; it will matter for the Docker image and Cloud Run
  memory in Phase 7.
- **A mapped file can be at most 2 GB** in Java. Ours are far below; the reader refuses bigger files.
- **Heap number:** 104 MB is "in use right now", not measured after a garbage collection like the
  Phase 1 number, so the two are not strictly comparable.

## How to run it yourself
```bash
scripts/sift-index                                   # once: builds data/index/ (about 10 s)
scripts/sift-api                                     # starts http://localhost:8080 on Java 21
curl "http://localhost:8080/search?q=photosynthesis"
curl "http://localhost:8080/suggest?prefix=pyt"
curl "http://localhost:8080/stats"
./gradlew test                                       # all 172 tests
```

## 5 interview questions (with short model answers)

**1. How do you compress a posting list, and why does it work?**
Doc ids are sorted, so I store gaps (differences) instead of ids, then write each gap as a varint: 7 data
bits per byte, the top bit says "more bytes follow". Frequent terms have small gaps, which fit in one byte.
My posting data went from 364.4 MB to 111.4 MB (30.6%). Counts compress best (25%) because nearly all are
below 128.

**2. What is memory-mapping and why use it instead of reading the file into arrays?**
`FileChannel.map` asks the OS to make a file look like memory. Pages are loaded only when touched and
the OS can drop them again under memory pressure. Startup does not read 380 MB of postings and text: the
index opens in 0.04 s, and the data lives outside the Java heap, so there is nothing for the garbage
collector to scan. The trade-off: the first query that touches a cold page pays a disk read, and one
mapped buffer is limited to 2 GB.

**3. How do you find a term's postings without a hash map?**
The dictionary is one sorted byte array of all terms plus offset arrays. I binary-search it: about 20
comparisons for 619k terms. It costs roughly 20 MB instead of well over 100 MB of small Java objects, and
the sorted order will help prefix search later. A hash map would be O(1), but 20 comparisons are far
below the cost of decoding the posting list anyway.

**4. How did you make sure the on-disk index is correct?**
Three levels. Unit tests: write a small index (with non-ASCII terms, an empty document, aliases), read it
back and compare every term's doc ids, counts and positions. Then the same `Searcher` runs on both
implementations through one interface, and a test compares full search results. Finally, the eval on the
real 280k-article index compares the rank for every query from disk and from memory: identical on both
query sets. A truncated file or a wrong format version is refused when opening.

**5. Many requests arrive at once. Is your index thread-safe?**
Yes, because nothing is modified after startup. The one trap is that a `ByteBuffer` has a single read
position; two threads sharing it would corrupt each other's reads. So every read works on its own
`duplicate()`: the same underlying bytes with a private position. Decoded posting lists are local to
one request.
