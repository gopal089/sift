# Phase 4 — PageRank, autocomplete, spelling

## What we built, in simple words

1. **PageRank**: a number for every article that says how important it is, computed from which articles
   link to it. Search now lifts important articles a little. "world war 2" finally shows *World War II* first.
2. **Autocomplete**: while you type, the search box shows up to 5 article titles that start with your
   letters, the most important first. Typing "pyt" shows *Pythagorean theorem*, *Python (programming language)*, …
3. **"Did you mean …?"**: if a query word is not a known word, the engine looks for the closest real word.
   "pyhton" → "python".

## Each new file and what it is for

| File | Purpose |
| --- | --- |
| `engine/.../rank/LinkGraph.java` | Which article links to which, as two flat arrays; follows redirects. |
| `engine/.../rank/PageRank.java` | The PageRank loop, and the 0-to-1 "boost" used in ranking. |
| `engine/.../suggest/TitleTrie.java` | Trie of titles; every node keeps its top 5 completions by PageRank. |
| `engine/.../spell/EditDistance.java` | Edit distance with dynamic programming (plain, and with swaps). |
| `engine/.../spell/BkTree.java` | Finds all words within a small edit distance without checking every word. |
| `engine/.../spell/SpellChecker.java` | Picks the best correction for a word and corrects a whole query. |
| `engine/.../index/Vocabulary.java` | The real (unstemmed) words found in at least 5 articles, with counts. |
| `engine/.../eval/SpellEvalCli.java`, `scripts/sift-spell-eval` | Accuracy and speed on the typo queries. |
| `eval/queries-typo.json` | 15 misspelled queries with the expected correction, frozen before the spelling code existed. |
| `pagerank.bin`, `words.bin` (index folder, format 3) | PageRank per document; the spelling vocabulary. |
| `web/src/components/SearchForm.tsx` (changed) | Suggestion list with keyboard control; "Did you mean" in `App.tsx`. |

## The key algorithms, with small worked examples

### 1. PageRank
Imagine a "random surfer": with probability d = 0.85 they click a random link on the current page,
otherwise they jump to any page. A page's PageRank is the share of time the surfer spends there.
Each round, every page gives its rank away in equal shares to the pages it links to:
```
new[v] = (1 − d)/N  +  d × (rank of pages with no links)/N  +  d × Σ rank[u]/outlinks(u)   over pages u linking to v
```
4 pages, start 0.25 each. Links: 0 → 1, 2;  1 → 2;  2 → 0;  3 → 2.  Base = 0.15/4 = 0.0375.
```
page 0 = 0.0375 + 0.85 × 0.25                    (all of page 2)         = 0.25
page 1 = 0.0375 + 0.85 × 0.125                   (half of page 0)        = 0.14375
page 2 = 0.0375 + 0.85 × (0.125 + 0.25 + 0.25)   (pages 0, 1, 3)         = 0.56875
page 3 = 0.0375                                  (nobody links to it)    = 0.0375       sum = 1
```
Repeat until the total change is below 1e-6 or 50 rounds. On the real data: 280,748 pages, 3,934,569 links,
**37 rounds, 1.5 s**. A page with no links ("dangling", 2,627 of them) would swallow rank, so its rank is
shared among all pages.
Top pages: United States, France, United Kingdom, Germany, Departments of France, City, Italy.

**Using it:** raw PageRank differs by factors of thousands, so we take the logarithm and scale it to 0–1
("boost"): least important page 0, most important 1. Final score = score × (1 + α × boost).

### 2. Trie with top-5 per node
A trie stores words letter by letter; words with the same beginning share a path.
```
root ─ p ─ y ─ t ─ h ─ o ─ n       each node keeps the 5 most important titles below it
                   └─ a ─ g ─ …    node "pyt": [Pythagorean theorem, Python (programming language), Pythagoras, Python, …]
```
Building trick: insert titles from the highest PageRank to the lowest; a node keeps a title only while it
has fewer than 5. Every node then holds its best 5, already in order, with no sorting.
A lookup walks one node per typed letter and reads the list: O(length of the prefix).

### 3. Edit distance (dynamic programming)
The smallest number of single-letter changes (insert, delete, replace) between two words.
Cell (i, j) = distance between the first i letters of one word and the first j letters of the other.
```
       ""  c  a  t
  ""    0  1  2  3      cell = min( left + 1, above + 1, diagonal + (letters differ ? 1 : 0) )
  c     1  0  1  2
  u     2  1  1  2
  t     3  2  2  1      → "cut" → "cat" = 1
```
A second version also counts swapping two neighbours as 1: "pyhton" → "python" is 1 (plain: 2).

### 4. BK-tree
125,050 words; computing the distance to all of them for every typo would be slow. In a BK-tree every
child hangs on a branch labelled with its distance to its parent. To find words within k of the query:
compute d = distance(query, node). Only branches labelled between d − k and d + k can contain a match
(triangle inequality: if x is c away from the node, it is at least |d − c| away from the query).
```
            book                     query "bok", k = 1:  distance to "book" = 1 → match
        1 /     \ 4                  look only into branches 0..2 → visit "books", skip the whole "cake" branch
     books      cake
```
Measured: a lookup computes 3,700–35,000 distances instead of 125,050.

### 5. Choosing the correction
Unknown word → candidates within 2 edits (1 edit for words of up to 4 letters) → the closest wins, counting
a swap as one edit; ties go to the word found in more articles. "pyhton": *python* (1 swap) beats *photon*
(2 edits). A word is "unknown" if fewer than 5 articles contain it.

## Time and space complexity

N = pages, E = links, V = vocabulary words, L = word length.

| Operation | Time | Space |
| --- | --- | --- |
| PageRank | O(rounds × (N + E)) | O(N + E) |
| Build the trie | O(N log N) sort + O(total title characters × children per node) | O(nodes) in flat arrays |
| Suggest | O(prefix length × children per node) | O(1) |
| Edit distance | O(L₁ × L₂) | O(L) (two or three rows) |
| BK-tree insert | O(depth) distances | O(V) |
| BK-tree search | between O(log V) and O(V) distances; measured 3%–28% of V for k = 2 | O(matches) |

## Measured results (Java 21, Apple M5)

**PageRank weight α** (main 50 queries; rule: best MRR, within 0.01 = tie → smaller α)

| α | 0 | 0.1 | 0.2 | **0.5** |
| --- | --- | --- | --- | --- |
| MRR | 0.815 | 0.843 | 0.843 | **0.868** |
| Results at #1 | 38/50 | 40/50 | 40/50 | 42/50 |

α = 0.5 wins; no query got worse at any α. Sanity check on the held-out 20 (not used for choosing):
0.697 → **0.763**, allowed minimum was 0.677. `eval/results/pagerank-alpha.txt`, `pagerank-holdout-sanity.txt`.

**MRR, all stages**

| Ranking | Main 50 | Held-out 20 |
| --- | --- | --- |
| Baseline BM25 | 0.275 | 0.312 |
| After Phase 1 fixes | 0.815 | 0.697 |
| **+ PageRank (α = 0.5), current default** | **0.868** | **0.763** |

**Spelling** (`eval/results/typo-accuracy.txt`): **12 of 15 correct (80%)**. Slowest suggestion 12.1 ms.
The three misses:
- "goverment" and "medecine": no suggestion, because these misspellings appear in 5 or more Wikipedia
  articles themselves, so they count as known words.
- "elefant" → "elegant" (1 edit) instead of "elephant" (2 edits). The closest word wins.

I did not change the rules after seeing these results: fixing them now would be tuning on the test set.

**Speed in the API** (`eval/results/phase4-api-check.txt`; search + PageRank + 10 snippets + spelling)

| Query set | Slowest query (median of 5) | Slowest single request |
| --- | --- | --- |
| Main 50 | 17.1 ms ("the who") | 21.9 ms |
| Held-out 20 | 9.9 ms | 11.8 ms |
| Typo 15 | 6.9 ms | 7.5 ms |
| 4 stop-word-only stress queries | 58.7 ms ("the the the of and in") | 64.9 ms |

`/suggest`: 0.6 ms median round trip. Server start: 1.3 s (was 0.7 s; it now builds the trie and the BK-tree).
Index folder: 423 MB (`pagerank.bin` 2.2 MB, `words.bin` 1.2 MB added). Disk vs memory on format 3: identical.
Tests: **221 JUnit** (202 engine, 19 API) + **20 Vitest**.

**"Done when": ✅** "pyt" shows suggestions, "pyhton" suggests "python", typo accuracy reported, every
measured query under 100 ms.

## Honest limits
- **α = 0.5 is the edge of the grid again**, and it was chosen on the same 50 queries as everything else.
  The held-out set has now been looked at three times, so it is no longer a clean test. Phase 6 needs a fresh one.
- **The trie holds titles only** (the original plan also mentioned frequent terms; those have no PageRank).
  It is rebuilt at every start and its memory was not measured separately.
- **Titles deeper than 24 characters** are filtered from a node's 5 stored titles, so a very long prefix can
  miss a title that exists.
- **Spelling only looks at unknown words.** A real word typed by mistake ("form" for "from") is never
  corrected, and misspellings that are common in Wikipedia count as known.
- **"the who" got slower with PageRank** (5 → 17 ms): the top results are now long, popular articles, and
  their snippets need more work. Still far under 100 ms.

## 5 interview questions (with short model answers)

**1. Explain PageRank and how you handled pages without links.**
A page's rank is the chance a random surfer is on it: with probability 0.85 they follow a random link,
otherwise they jump anywhere. I iterate "every page shares its rank equally among its links" until the
total change is under 1e-6: 37 rounds on 280k pages and 3.9M links. A page with no out-links would leak
rank out of the system, so its rank is spread over all pages each round. The ranks always sum to 1.

**2. How do you combine PageRank with BM25, and how did you pick the weight?**
score × (1 + α × boost), where boost is log PageRank scaled to 0–1. Multiplying keeps relevance first: a
page that does not match still scores 0. The log matters because raw PageRank spans several orders of
magnitude. I tried α in {0, 0.1, 0.2, 0.5} on 50 frozen queries with the rule fixed beforehand; 0.5 won
with MRR 0.868 against 0.815, and no query got worse. Held-out went 0.697 → 0.763.

**3. Why does each trie node store its top 5 instead of collecting them at query time?**
Collecting means walking the whole subtree under the prefix; under "p" that is tens of thousands of
titles on every key press. Storing 5 ids per node makes a lookup O(prefix length). I get the lists for
free by inserting titles in descending PageRank order, so the first 5 to pass through a node are its best 5.
The cost is memory: 5 ints per node, which is why the tree is flat arrays and stops at depth 24.

**4. Why a BK-tree, and why must the distance be a metric?**
It prunes with the triangle inequality: if the query is d from a node, only children at distance d − k to
d + k can hold matches, so most branches are never visited. I measured 3,700–35,000 distance computations
instead of 125,050. The pruning is only correct for a true metric. Levenshtein is one; the "swap counts
as 1" variant I use is not, so I search the tree with Levenshtein and use the swap-aware distance only to
rank the candidates.

**5. Your spelling accuracy is 80%. What went wrong and what would you do?**
Two of three misses are misspellings that occur in at least 5 Wikipedia articles, so my rule "unknown =
in fewer than 5 articles" treats them as real words. The fix is to also suspect a rare word when a much
more frequent word is one edit away ("goverment" vs "government"). The third, "elefant" → "elegant", needs
knowledge of sounds (ph ↔ f), not letters. I left the rules as they were because changing them after
seeing the 15 test queries would just be fitting the test; I would write new typo queries first.
