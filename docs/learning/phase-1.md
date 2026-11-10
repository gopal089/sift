# Phase 1 — Keyword search in the terminal

## What we built, in simple words

A working search engine that runs in the terminal. You give it `articles.jsonl` and it:
1. Reads every article and breaks its title and text into **terms** (lowercase word roots).
2. Builds an **inverted index**: for every term, the list of articles that contain it, how
   often, and at which word positions.
3. For a query, it finds the articles containing *all* query words (AND). If there are none,
   it falls back to *any* word (OR). It scores them with **BM25** and keeps the **top 10**
   with a small heap.

On all 280,748 articles: the index builds in **7.91 s**, uses **521 MB** of memory, and every
test query answers in **under 3 ms**.

## Each new file and what it is for

| File | Purpose |
| --- | --- |
| `engine/.../text/PorterStemmer.java` | Hand-written Porter (1980) stemmer: `connections → connect`. |
| `engine/.../text/StopWords.java` | The classic 33-word English stop list (Lucene's). |
| `engine/.../text/Tokenizer.java` | **The one shared tokenizer.** `tokenize()` is used for indexing (keeps every word); `queryTerms()` is used for queries (applies the stop-word rule and removes duplicates). |
| `engine/.../data/Article.java`, `ArticleReader.java` | Stream `articles.jsonl` one line at a time (Jackson does the JSON parsing). |
| `engine/.../index/IntArrayList.java` | A growable list of plain `int`s (4 bytes each, not 16+ byte objects). |
| `engine/.../index/PostingList.java` | One term's postings as three flat `int[]` arrays: doc IDs, freqs, positions. |
| `engine/.../index/DocTable.java` | Per-document Wikipedia ID, title and length, plus the average length. |
| `engine/.../index/InvertedIndex.java`, `IndexBuilder.java` | term → posting list; builder adds documents one by one. |
| `engine/.../search/Bm25.java` | The BM25 formula (k1 = 1.2, b = 0.75). |
| `engine/.../search/PostingIntersection.java` | Two-pointer AND of sorted lists, shortest first. |
| `engine/.../search/TopKHeap.java`, `ScoredDoc.java` | Size-K min-heap that keeps the best K results. |
| `engine/.../search/Searcher.java`, `SearchResult.java` | AND search, OR fallback, BM25 scoring, top-K. |
| `engine/.../cli/SearchCli.java` | The terminal program; prints build stats, memory and timings. |
| `engine/src/test/...` | 93 JUnit tests, including Porter's official 23,531-word list. |
| `eval/results/phase1-check.txt` | Raw output of the final check (full data). |
| `eval/results/phase1-diagnosis-ranks.txt` | Where the "right" article ranks for each query. |

## The key algorithms, with small worked examples

### 1. Tokenizer + stop-word rule
```
index:  "To be, or not to be"      → [to, be, or, not, to, be]      (positions 0..5, nothing removed)
query:  "the history of india"     → [histori, india]               ("the", "of" dropped: real words remain)
query:  "to be or not to be"       → [to, be, or, not]              (all stop words → keep all, dedupe)
query:  "the who"                  → [who]                          ("who" is NOT in the 33-word list)
```

### 2. Porter stemmer: the measure m
Write a word as consonant groups C and vowel groups V: `[C](VC)^m[V]`.
`tree` = C V → m = 0. `trouble` = C V C V → m = 1. `private` = C V C V C V → m = 2.
Rule example (step 4): remove `-ment` only if what remains has m > 1.
`adjustment → adjust` (`adjust` = V C V C, so m = 2 ✓). `cement` stays: the longest match is
`-ement`, which would leave just `c` (m = 0 ✗).

### 3. Inverted index
```
doc 0 "Cats":  "The cat sat on the mat."   → cat(0) the(1) cat(2) sat(3) on(4) the(5) mat(6)
doc 1 "Dogs":  "A dog chased the cat."     → dog(0) a(1) dog(2) chase(3) the(4) cat(5)

cat → docIds [0, 1]   freqs [2, 1]   positions [0, 2, | 5]
the → docIds [0, 1]   freqs [2, 1]   positions [1, 5, | 4]
```
Documents are added in increasing ID order, so every list is sorted without any sorting step.

### 4. BM25 (the hand-worked example from `Bm25Test`)
3 documents, lengths 7, 6, 3 → avgLen = 5.33. Term "cat" appears in n = 2 docs; doc 0 has tf = 2, length 7.
```
idf        = ln((3 − 2 + 0.5) / (2 + 0.5) + 1) = ln(1.6)                 = 0.4700
lengthNorm = 1.2 × (1 − 0.75 + 0.75 × 7 / 5.33)  = 1.2 × 1.2344          = 1.4813
score      = 0.4700 × 2 × (1.2 + 1) / (2 + 1.4813)                        = 0.5940
```

### 5. AND intersection (two pointers)
```
a = [1, 3, 5, 9]   b = [3, 4, 9, 12]
1<3 → move a | 3=3 → keep 3 | 5>4 → move b | 5<9 → move a | 9=9 → keep 9   ⇒ [3, 9]
```
With several terms we start from the shortest list, so the running result only gets smaller.

### 6. Top-K min-heap (K = 3)
Scores arrive: 0.5, 2.0, 1.0, 3.0, 0.1, 2.5. The heap root is always the *worst* of the kept three.
```
after 0.5, 2.0, 1.0 → keep {0.5, 1.0, 2.0}, root 0.5
3.0 > 0.5 → replace root → {1.0, 2.0, 3.0}, root 1.0
0.1 < 1.0 → ignore
2.5 > 1.0 → replace root → {2.0, 2.5, 3.0}   ⇒ answer 3.0, 2.5, 2.0
```

## Time and space complexity

Let T = total tokens (47.3 M), P = postings (23.4 M), V = unique terms (619 k), N = documents,
and for a query: Lᵢ = the length of term i's posting list, m = matching docs, K = 10.

| Operation | Time | Space |
| --- | --- | --- |
| Tokenize a text | O(characters) | O(tokens) |
| Build the index | O(T) expected (hash-map lookups) | O(T + P + V): positions + doc IDs/freqs + dictionary |
| AND query | O(Σ Lᵢ) to intersect and score + O(m log K) heap | O(min Lᵢ) |
| OR query | O(Σ Lᵢ) + O(m log K) | O(N) score array |
| Top-K heap | O(n log K) for n candidates | O(K) |
| Positions of posting i | O(i) (sums the earlier freqs) | O(tf) |

## Measured results (full data, Java 21, Apple M5)

From `eval/results/phase1-check.txt`:

| Measure | Value |
| --- | --- |
| Documents | 280,748 |
| Index build time | 7.91 s |
| Unique terms | 619,144 |
| Postings / positions (tokens) | 23,410,476 / 47,317,366 |
| Posting data (exact int bytes) | 359.1 MB |
| Heap used by the whole index | 521.0 MB |
| Stop words' share of posting data | 76.3 MB (21.2%); they are 28.7% of all tokens |
| Query time, warm median (20 repeats) | 0.04–1.26 ms |
| Query time, first run | 0.07–2.64 ms |

**Ranking quality.** The main article ranks: *Photosynthesis* #4, *Python (programming language)* #3,
*World War II* **#458** for "world war 2", *The Who* #13, *History of India* #57.
The cause is BM25's length normalisation: *World War II* has 5,456 words (about 32× the average), and
its title counts as only 3 words. See PROGRESS.md for the proposed fix.

## 5 interview questions (with short model answers)

**1. How does your AND query work, and why start with the shortest posting list?**
Posting lists are sorted by doc ID, so two lists intersect with two pointers in O(a + b). Equal IDs
are a match; otherwise the pointer at the smaller ID moves forward. Starting with the shortest
list keeps the intermediate result as small as possible and lets me stop as soon as it is empty.

**2. Explain BM25 in one minute. What do k1 and b do?**
Each query term adds idf × a saturating function of tf. IDF rewards rare words. k1 = 1.2
controls saturation: the 10th mention adds much less than the 2nd. b = 0.75 controls how much
long documents are penalised. The "+1" inside the log keeps IDF positive for very common words.
In my data, b also *hurt* me: *World War II* (5,456 words) ranked #458 for "world war 2",
because its length divided down its scores.

**3. You kept stop words in the index. What did that cost, and why do it?**
It cost 76.3 MB, which is 21.2% of the 359.1 MB of posting data. Stop words are 28.7% of all
tokens. In return, queries like "to be or not to be" are answerable at all. At query time I
drop stop words only when a real word remains, so normal queries pay nothing extra. In Phase 2,
gap encoding will shrink these lists a lot, because they are dense (small gaps → 1 byte each).

**4. Why a min-heap of size K instead of sorting all matches?**
Sorting m matches costs O(m log m). The heap costs O(m log K) with K = 10, and only O(K)
memory. It's a *min*-heap because the question for every new document is "is it better than
the *worst* one I'm keeping?", and the min-heap keeps that worst one at the root.

**5. Why store postings as int arrays instead of a `Posting` object per entry?**
There are 23.4 M postings and 47.3 M positions. One Java object per posting adds about 16 bytes
of header plus a reference and boxed fields, which would mean GBs. Flat `int[]` arrays cost
exactly 4 bytes per number: 359.1 MB of posting data, 521.0 MB of heap in total, including
the dictionary and titles.

## How to run it yourself

```bash
./gradlew :engine:test                       # 93 tests
./gradlew :engine:installDist                # builds engine/build/install/sift-cli
export JAVA_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null || echo ~/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2/jdk-21.0.12.1+1/Contents/Home)
engine/build/install/sift-cli/bin/sift-cli data/articles-sample.jsonl          # interactive, fast
engine/build/install/sift-cli/bin/sift-cli data/articles.jsonl --query "photosynthesis" --repeat 20
```
