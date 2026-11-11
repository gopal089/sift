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

## Ranking fixes (after the first check)

### The problem
The first version found the right *kind* of pages but put the main article far down:
*World War II* was #458 for "world war 2", *Chess* was #174 for "chess". Two causes:

1. **Length normalisation.** BM25 divides a word's count by how long the document is. *World War II* has
   5,456 words (32× the average), so its counts were divided down hard. Short pages that mention the
   words once or twice won.
2. **The title was diluted.** The title was just the first 3 words of a 5,456-word document.

### How we worked (the method matters more than the numbers)
1. **Froze 50 test queries first** (`eval/queries.json`), each with the one article we expect. They were
   written and committed *before* any tuning, and the engine was never run on them while writing them.
2. **One number to compare settings: MRR** (mean reciprocal rank). For each query take 1 / rank of the
   expected article (rank 1 → 1.0, rank 2 → 0.5, rank 10 → 0.1, not found → 0), then average.
   Example with 3 queries at ranks 1, 4 and "not found": (1 + 0.25 + 0) / 3 = 0.417.
3. **Decision rules written down before each run** (highest MRR wins; within 0.01 is a tie → simpler
   setting). This stops us from picking whatever looks nice afterwards.
4. **A held-out set** of 20 more queries (`eval/queries-holdout.json`), used exactly once at the end.

### What we tried, with the numbers (main 50 queries)

| Step | Setting | MRR | #1 results |
| --- | --- | --- | --- |
| Baseline | BM25, title inside the body, b = 0.75 | 0.275 | 9/50 |
| Title field, BM25F | best of 8: w = 5, b_body = 0.5 | 0.635 | 27/50 |
| Title field, **sum** | best of 12: **w = 2, b_body = 0.3** | 0.799 | 38/50 |
| + alias field (redirects) | w_alias = 1 / 2 | 0.801 / 0.790 | 38 / 37 |
| + question words dropped | **final** | **0.815** | 38/50 |
| + soft AND instead | | 0.801 | 38/50 |

Raw files: `eval/results/grid-bm25f.txt`, `grid-sum.txt`, `alias-field.txt`, `missing-word.txt`.

**Final setting** (`Ranking.DEFAULT`):
`score = BM25(body, b = 0.3) + 2 × BM25(title, b = 0.75)`, k1 = 1.2, and the question words
who/what/when/where/why/how/did/does/is are treated as stop words in queries.

### The two formulas, on one example
One term with idf = 2.0. The document's title has 3 words (average 2) and contains the term once.
Its body has 400 words (average 100) and contains it 4 times. k1 = 1.2, b = 0.75 for both, w = 2.

```
B_title = 1 − 0.75 + 0.75 × 3/2     = 1.375
B_body  = 1 − 0.75 + 0.75 × 400/100 = 3.25

BM25F (combine the counts, then saturate once):
  tf~   = 2 × 1/1.375 + 4/3.25 = 1.4545 + 1.2308 = 2.6853
  score = 2.0 × 2.6853 × 2.2 / (1.2 + 2.6853)     = 3.0410

Sum (saturate each field, then add):
  body  = 2.0 × 4 × 2.2 / (4 + 1.2 × 3.25)  = 2.2278
  title = 2.0 × 1 × 2.2 / (1 + 1.2 × 1.375) = 1.6604
  score = 2.2278 + 2 × 1.6604                = 5.5486
```
Both are tested in `Bm25FTest`.

### Why the sum beat BM25F here
BM25 has a ceiling: one term can never add more than idf × (k1 + 1) (here 4.4), however often it
appears. BM25F puts title and body into *one* pile under *one* ceiling. A short page stuffed with the
word is already near the ceiling from its body alone, so the main article's title match can hardly lift it
above that page, even with w = 5. The sum gives the title its *own* ceiling on top of the body's. So "the
query word is in the title" is always worth a clear, separate bonus. Our queries are mostly
"navigational" (the user wants the article *named* like the query), so that is exactly what helps.
The textbook warning about the sum (it counts the same word twice) is real, but on this data it was
the better trade. I measured instead of trusting the textbook: 0.799 vs 0.635.

### Why redirects needed their own field
Redirects are alternate names: "USA" → *United States*, "WW2" → *World War II*.
- **First try: put redirect words into the title field.** MRR fell from 0.635 to 0.561 (all words) and
  0.455 (new words only). *United States* has 41 redirects, so its "title" grew from 2 words to 110, and
  title length normalisation then punished the very articles people search for most.
- **Second try: a separate alias field.** Each distinct alias word counts once per article and there is
  no length normalisation (b = 0). BM25 with tf = 1 and b = 0 is simply idf, so the bonus is
  `w_alias × idf`. Now "usa" moved *United States* from #2,091 to #87 and "ww1" from #16 to #3.
  But "the who" fell from #1 to #7 and "gravity" from #1 to #3, because other articles have "who" or
  "gravity" among their alias words. Overall: +0.002 MRR.
- **Decision:** my rule said "adopt only if MRR improves by at least 0.01". It did not, so the alias
  bonus is **off** (`wAlias = 0`). The code and the index format support it, so Phase 6 can try again.

### Question words: stop list vs soft AND
4 of the 8 question queries found nothing: AND requires every word, and *Earthquake* does not contain
"what". Two fixes were compared:
- **(a) Drop question words at query time.** "what causes earthquakes" → [caus, earthquak]. MRR 0.815.
- **(b) Soft AND.** Any word may match, but documents with more of the query words always rank first.
  MRR 0.801: the answer is found, but far down (*Earthquake* #93), because thousands of pages
  containing "what" and "causes" come before it.
- Both together: 0.815, the same as (a). So (a) wins as the simpler option. Soft AND stays in the code
  as a setting (`soft=1`), switched off.
- "the who" still works: when *every* word is a stop word, we keep them all → [the, who].

### Overfitting and the held-out set
Picking the best of many settings on 50 queries makes the score look better than it really is: some of the
gain is luck that fits these exact queries. That is **overfitting**. To check, the final setting was run
**once** on 20 queries that were never used for any choice:

| Group | Main 50: baseline → final | Held-out 20: baseline → final |
| --- | --- | --- |
| **All** | 0.275 → **0.815** | 0.312 → **0.697** |
| diagnostic (6 / 0) | 0.113 → 0.867 | (none) |
| famous one-word (14 / 6) | 0.176 → 0.929 | 0.121 → 0.792 |
| famous multi-word (10 / 5) | 0.273 → 0.950 | 0.367 → 1.000 |
| obscure (8 / 3) | 0.844 → 1.000 | 0.833 → 1.000 |
| questions (8 / 4) | 0.119 → 0.375 | 0.042 → 0.048 |
| abbreviations (4 / 2) | 0.040 → 0.516 | 0.500 → 0.502 |

(`eval/results/missing-word.txt`, `eval/results/holdout-final.txt`.)
What this tells us, honestly:
- The big gain is real: it shows up on queries the tuning never saw (0.312 → 0.697; 13 of 20 at #1).
- The held-out score is lower than the main score (0.697 vs 0.815). Part of that is overfitting and part
  is that 20 queries is a small sample: one query moving from #1 to #2 changes it by 0.025.
- **Questions are still bad** (0.048 held-out). "what is the tallest mountain in the world" cannot be
  answered by matching words; that is what Smart (meaning) search in Phase 5 is for.
- **Abbreviations that need a redirect are still bad** ("usa" #2,091, "uk" #222).
- Both winners sat on the edge of their grid (smallest w, smallest b), so the true best may lie further
  out. We deliberately did not keep searching: more tuning on the same 50 queries means more overfitting.
- The ranking is now **locked until Phase 6**.

### What it cost
| Measure | Before | After |
| --- | --- | --- |
| Heap used by the index | 521.0 MB | 562.3 MB |
| Slowest eval query (median of 20) | 2.68 ms | 1.27 ms |
| JUnit tests | 93 | 135 |

The +41.3 MB is the title field (both numbers measured with the CLI on Java 21; the old code was
re-measured today and is still 521.0 MB). The data itself is small: 697,412 title postings = 5.3 MB, plus
two more length arrays = 2.2 MB. Most of the rest is Java object overhead: 142,552 title terms each
get their own list object, two small arrays, a key string and a map entry (very roughly 150–200 bytes
each, so about 25 MB; this split is an estimate, not a measurement). Phase 2's on-disk format stores these
lists as bytes in one file, which removes that overhead.
Final check output: `eval/results/phase1-check-final.txt`.

### New files in this step
| File | Purpose |
| --- | --- |
| `eval/queries.json`, `eval/queries-holdout.json` | The frozen 50 tuning queries and 20 held-out queries. |
| `engine/.../eval/EvalQuery.java`, `RankMetrics.java` | One query; MRR and hit@k. |
| `engine/.../eval/Evaluator.java` | Runs queries through the real `Searcher`, finds the rank of the expected article. |
| `engine/.../eval/EvalCli.java`, `EvalReport.java` | Compare several settings in one run; write `.txt` and `.tsv`. |
| `engine/.../search/Ranking.java` | All search settings in one record, plus the locked `DEFAULT`. |
| `engine/.../search/Bm25F.java` | The BM25F formula (kept for comparison). |
| `engine/.../index/FieldPostingList.java` | Small posting list for one field (title or alias): doc ids + counts. |
| `engine/.../data/RedirectAliases.java` | Redirects → alternate titles per article (chains followed, case variants skipped). |
| `scripts/sift-cli`, `scripts/sift-eval` | Run the CLI / the eval on Java 21. |

### 5 more interview questions

**6. How did you know your ranking change was an improvement and not just a fix for one query?**
I froze 50 queries with expected answers before tuning and compared settings by MRR, with the decision
rule written down first. Baseline 0.275, final 0.815. Then I ran the final setting once on 20 held-out
queries that were never used for choosing: 0.312 → 0.697. The gain held, but smaller, which is what
overfitting looks like, and I report both numbers.

**7. BM25F is the "correct" multi-field BM25. Why did you ship a sum of two BM25 scores?**
Because I measured both: 0.635 vs 0.799 MRR. BM25F saturates title and body together, so a term's score
is capped at idf × (k1 + 1) and a title match cannot stand out from a short page that repeats the word.
The sum gives the title its own capped bonus. For navigational queries on Wikipedia, "the word is in the
title" is the strongest signal, so the sum fits the data better. The cost is double counting, which could
hurt on long, descriptive queries.

**8. You built a feature (redirect aliases) and then switched it off. Why?**
It fixed what it was for ("usa" #2,091 → #87) but hurt other queries ("the who" #1 → #7), for a net
+0.002 MRR. My rule, set before the run, required +0.01. Shipping it anyway would be tuning to one query.
The first design (aliases inside the title field) was clearly worse, 0.635 → 0.561, because popular
articles got very long title fields. Keeping the field separate, with no length penalty, removed that
problem; it just was not enough to pass the bar yet.

**9. Why drop question words instead of using soft AND, which is more general?**
On the data, dropping them gave 0.815 and soft AND 0.801. Soft AND does find the article, but below every
page that happens to contain "what" and "is", e.g. *Earthquake* at #93. Both together gave no extra
gain, so I kept the simpler one. Soft AND is still there as a switch.

**10. What is still wrong with your ranking?**
Questions whose answer is not named in the query (held-out MRR 0.048), abbreviations that are only
redirects ("uk" #222), and "world war 2" still shows *Guild Wars 2* first with *World War II* at #5,
because "2" is in that title and "II" is not "2". Meaning-based search (Phase 5), PageRank (Phase 4) and a
retry of the alias field in Phase 6 are the planned fixes.

## How to run it yourself

```bash
./gradlew :engine:test                                                   # all JUnit tests (135)
scripts/sift-cli data/articles-sample.jsonl                              # interactive, fast (2,000 articles)
scripts/sift-cli data/articles.jsonl --query "photosynthesis" --repeat 20
scripts/sift-cli data/articles.jsonl --query "chess" --ranking bm25       # the old Phase 1 ranking, to compare
scripts/sift-eval my-run                                                 # MRR on the 50 queries with the default ranking
```
`scripts/sift-cli` builds the CLI, asks Gradle for its Java 21 (`./gradlew -q :engine:printJavaHome`)
and runs the CLI with it, so official numbers always come from Java 21 even though `java` on PATH is 25.
If you run `engine/build/install/sift-cli/bin/sift-cli` directly on another Java, it prints a warning.
