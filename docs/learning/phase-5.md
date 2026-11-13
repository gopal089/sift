# Phase 5 — Smart (meaning) search

## What we built, in simple words
Keyword search only finds articles that share words with the query. "how do plants make food" never says
"photosynthesis", so keywords alone rank it low. Phase 5 adds **meaning search**:

1. A small neural network turns any text into a list of 384 numbers, an **embedding**. Texts with similar
   meaning get similar lists. The final model is **bge-small-en-v1.5** (the first version used all-MiniLM-L6-v2).
2. We embedded every article once in Python (final recipe: **title + first 60 words**) and saved the
   280,748 vectors.
3. At query time Java embeds the query (our own WordPiece tokenizer + the model in ONNX Runtime + our own
   pooling; bge also gets an instruction prefix in front of the query) and finds the most similar article
   vectors with our own **HNSW** graph.
4. **Smart mode** mixes the keyword list and the meaning list with **Reciprocal Rank Fusion** (RRF).

## Status: done (2026-11-13)
The first version (MiniLM, 200 words) failed two checks. I then ran **one** pre-registered experiment
(see "Fixing semantic search" below). Its rule picked bge-small + 60 words + plain RRF:
- **Done when:** "how do plants make food" → Photosynthesis is **#2** in Smart mode (was #4). ✅
- Smart MRR on the main 50: **0.956** (keyword 0.868, first Smart version 0.868). ✅
- Smart p95 over HTTP **13.0 ms** (limit 100 ms). All 261 Java and 21 website tests pass. ✅
- The old held-out 20 are not re-run (explained below). The final unseen check is `queries-holdout-v2.json`,
  run once in Phase 6.

## Results (all from real runs; raw files in `eval/results/`)
Final settings (bge-small, title + 60 words):
| What | Number |
| --- | --- |
| WordPiece ids, Java vs model tokenizer (bge) | 1,000 / 1,000 strings identical, no tokenizer change needed |
| Query vector, Java vs Python (bge, with prefix) | min cosine 1.0000000 on 200 queries |
| Query embedding (Java, 1 thread, 500 titles) | median 2.49 ms, p95 2.87 ms (MiniLM: 0.97 / 1.22 ms) |
| Embedding all 280,748 articles (Mac GPU) | 1,000.6 s (shared the GPU with another embedding run) |
| HNSW build (M 16, efConstruction 200, 1 thread) | 206.5 s; graph 39.2 MB in memory, 36.8 MB file |
| recall@10 at efSearch 50 / 100 / 200 / 300 / 400 | 0.830 / 0.890 / 0.932 / 0.944 / 0.957 → **400** (rule: smallest ≥ 0.95) |
| HNSW (efSearch 400) vs brute force time | 1.51 ms vs 24.8 ms median |
| int8 | 103.9 MB vs 411.3 MB; brute-force recall vs float32 0.986 (≥ 0.97) → **kept** |
| MRR main 50 through HNSW: keyword / semantic / hybrid | 0.868 / 0.957 / **0.956** (float32 and int8 identical) |
| MRR per category, keyword → hybrid | questions 0.415 → 0.826, abbreviations 0.516 → 0.800, diagnostic, single, multi, obscure all stay 1.000 |
| Smart query time over HTTP | server median 5.3 ms, p95 11.9 ms; HTTP p95 13.0 ms |
| Server RSS after start / after 1,200 queries | 784 MB / 1,059 MB (heap after GC 275 MB) |

First version (MiniLM, title + 200 words), kept for comparison:
| What | Number |
| --- | --- |
| recall@10 at efSearch 50 / 100 / 200 | 0.878 / 0.931 / 0.966 → 200 |
| MRR main 50: keyword / semantic / hybrid | 0.868 / 0.649 / 0.868 |
| Photosynthesis for "how do plants make food" | keyword #15, semantic #20, hybrid #4 |
| Smart query time in the server | median 3.3 ms, p95 10.0 ms; RSS after queries 1,013 MB |

## New files
| File | What it is for |
| --- | --- |
| `eval/queries-holdout-v2.json` | 30 fresh held-out queries, frozen before any Phase 5 code. Reserved for Phase 6; nothing ran on them. |
| `data-prep/export_model.py` | Exports the model to ONNX (`data/model/`), writes `vocab.txt`, checks ONNX + our pooling = sentence-transformers. |
| `data-prep/embed_articles.py` | Embeds all articles → `data/vectors/vectors-f32.bin` + `vectors-meta.json` (count, title hash). |
| `data-prep/make_wordpiece_fixture.py` | Makes the 1,000-string tokenizer test fixture from the model's own tokenizer. |
| `data-prep/embed_check_queries.py` | 200 queries with Python vectors, for the Java-vs-Python check. |
| `engine/.../embed/WordPieceTokenizer.java` | Our BERT WordPiece tokenizer (normalise → split → longest-match pieces). |
| `engine/.../embed/QueryEmbedder.java` | Tokenizer → ONNX Runtime → mean pooling → length 1. `Embedder` is its small interface. |
| `engine/.../embed/VectorMath.java` | Mean pooling + normalising, dot product, cosine. |
| `engine/.../vector/Float32Vectors.java`, `Int8Vectors.java` | The vectors, memory-mapped; int8 = 1 byte per number + 1 scale per vector. |
| `engine/.../vector/BruteForce.java` | Exact search: compare with all vectors, keep the top K in a min-heap. |
| `engine/.../vector/Hnsw.java`, `NodeHeap.java` | Our HNSW graph: build, search, save, load; a primitive binary heap. |
| `engine/.../vector/VectorFiles.java` | Opens the vector folder and refuses vectors that belong to different articles. |
| `engine/.../vector/HnswBuildCli.java` | Builds and saves the graph, writes the int8 vectors. |
| `engine/.../search/SmartSearcher.java`, `QueryRanker.java` | Semantic and hybrid (RRF) ranking; the common interface with the keyword `Searcher`. |
| `engine/.../eval/EmbedCheckCli.java`, `VectorEvalCli.java`, `SmartEvalCli.java` | The three measurements: Java = Python, HNSW recall, MRR per mode. |
| `scripts/sift-run` | Runs any engine tool on Java 21 and records commit + machine. |
| `api/...` | `mode=smart` is real now; stats show model and vector time. |
| `engine/.../vector/SemanticIndex.java` | "Top k articles by meaning": one interface for exact and HNSW search. |
| `engine/.../vector/ExactIndex.java` | Exact search over one or two vectors per article (score = max). |
| `engine/.../vector/StackedVectors.java`, `HnswIndex.java` | Two vector sets seen as one, so one HNSW graph can hold both; maps nodes back to articles. |
| `engine/.../eval/FixExperimentCli.java` | The pre-registered experiment: 5 variants × 2 fusions, selection rule in code (and tested). |
| `eval/measure_api.py` | Starts the server, sends 1,200 queries over HTTP, reports p50/p95 and memory. |

## The key ideas, each with a small worked example

### 1. Embeddings and cosine similarity
An embedding is a point in 384-dimensional space. We make every vector **length 1** (divide by its
length), so the **dot product** Σ aᵢbᵢ equals the **cosine** of the angle between them: 1 = same direction,
0 = unrelated.

Tiny example in 2 dimensions: a = (0.6, 0.8), b = (0.8, 0.6), c = (−0.8, 0.6).
a·b = 0.48 + 0.48 = **0.96** (very similar); a·c = −0.48 + 0.48 = **0** (unrelated).

Why length 1 matters: then the dot product only measures direction (meaning), not how long the text was,
and it needs no division at query time.

**Mean pooling.** The model gives one vector per word piece. The sentence vector is their average
(padding excluded), then normalised. Pieces (1,0) and (3,4) → mean (2,2) → length 2.83 → (0.707, 0.707).

### 2. WordPiece tokenization
The model only knows 30,522 word pieces. Our tokenizer must give exactly the ids it was trained with.
1. **Normalise:** drop control characters, odd spaces → ' ', spaces around Chinese characters, remove
   accents (Unicode NFD splits "é" into "e" + accent mark; we drop the mark), lowercase.
2. **Split** on spaces and make each punctuation mark a word: "Café, déjà-vu!" → cafe | , | deja | - | vu | !
3. **Longest match first:** "unaffable" → is "unaffable" in the vocab? no … "un"? yes → then "##affable"? no …
   "##aff"? yes → "##able"? yes → **un ##aff ##able**. If some part has no match, the whole word is [UNK].
4. Add [CLS] at the start and [SEP] at the end; cut at 256.

Traps we hit on purpose in the test: ASCII symbols like `$ + < = > ^ | ~` count as punctuation for BERT
even though Unicode calls them symbols; Java's `isWhitespace` does *not* count the no-break space, BERT
does; zero-width spaces are "format" characters and are dropped; the Greek final sigma must be lowercased
one character at a time.

### 3. HNSW (Hierarchical Navigable Small World)
Brute force compares the query with all 280,748 vectors. HNSW builds a graph where each article links
to ~16–32 similar articles, then **walks** the graph towards the query.

- **Layers:** every node is on layer 0; a node reaches layer L with probability (1/M)^L. With M = 16:
  ~280k nodes on layer 0, ~17.5k on layer 1, ~1.1k on layer 2, ~70 on layer 3. The search starts at the top
  (few nodes, long jumps) and goes down (more nodes, short steps), like express train → local train → walking.
- **Greedy walk (upper layers):** move to whichever neighbour is more similar to the query, until none is.
- **Beam search (layer 0):** keep the best `ef` nodes found so far and a queue of nodes to expand; stop when
  the best unexpanded node is worse than the worst kept one.
- **Building:** insert nodes one by one: search for the new node's neighbours with beam width
  `efConstruction`, keep M of them by the **diversity rule** (keep a candidate only if it is closer to the
  new node than to any neighbour already kept: links point in different directions), link both ways, and
  if a neighbour now has too many links, re-choose its links with the same rule.

Worked example of the diversity rule (2-d): the new node points at 30°. Candidates: A at 18° (sim 0.98),
B at 0° (sim 0.87), C at 90° (sim 0.50). Keep A. B: sim(B, A) = 0.95 > 0.87, so B is "behind" A → skip.
C: sim(C, A) = 0.31 < 0.50 → keep. Result: A and C, two different directions, instead of A and B.
(This exact case is a unit test.)

A bug I made and fixed: in the greedy walk I moved `current` to a better neighbour *while still looping
over the old node's neighbour list*, so the loop read the new node's list with the old count. Fix: remember
`from = current` before the loop.

### 4. int8 vectors (scalar quantisation)
Store each number in 1 byte instead of 4. Per vector: scale = max|x| / 127; byte = round(x / scale).
x = (0.50, −0.25, 0.10): scale = 0.003937; bytes = round(127, −63.5, 25.4) = (127, −63, 25);
decoded = (0.500, −0.248, 0.098). Similarity = scale × Σ qᵢ·bᵢ (the query stays float).
Error per number ≤ scale/2 ≈ 0.002, so similarities move by about 0.001–0.01: enough to swap close
neighbours sometimes, rarely enough to change the top results.

### 5. Reciprocal Rank Fusion (RRF)
Two ranked lists with incomparable scores (BM25 ≈ 5–40, cosine ≈ 0.2–0.8). RRF ignores scores and uses
only ranks: score(d) = Σ 1 / (60 + rank_list(d)).

Example: keyword list = [Plant, Leaf, Photosynthesis], meaning list = [Photosynthesis, Sugar].
- Photosynthesis: 1/(60+3) + 1/(60+1) = 0.0159 + 0.0164 = **0.0323**
- Plant: 1/61 = 0.0164
- Leaf: 1/62 = 0.0161, Sugar: 1/62 = 0.0161 (tie → smaller doc id first)

Being good in both lists beats being first in one. The constant 60 flattens the curve: rank 1 vs rank 2
differ little (0.0164 vs 0.0161), so one list cannot dominate.

## Fixing semantic search (one pre-registered experiment)
The first version failed two checks, so I ran **one** experiment whose variants and selection rule were
written down and committed *before* any run (PROGRESS.md, commit e3c9e6b). Writing the rule first matters:
if you look at the numbers and then pick the rule, you can always find a rule that makes your favourite win.

### Idea 1: dilution, or why fewer words can be better
Our article vector is the **average** (mean pooling) of the vectors of all its word pieces. An average of
many different topics points "somewhere in the middle" and is not very close to any one of them.
Real example from our data: the first 60 words of "Photosynthesis" say "green plants make their own food
from sunlight", which is almost the query. Words 60–200 then drift to the origin of the elements in
supernovae, Halley's Comet and Earth's early atmosphere. The 200-word vector averages all of that, so it moves
away from "how do plants make food". The first sentence or two of a Wikipedia article usually says what the
thing *is*, so a vector made from **title + ~60 words** is more focused. Variant B tests that.

Tiny example with 2-d "topic" vectors: a query about food = (1, 0). Text about food = (1, 0) and text about
light = (0, 1). An article made only of the food part scores cos = 1.0. The average of food and light,
(0.5, 0.5), normalised to (0.71, 0.71), scores only 0.71, even though the article contains the answer.

### Idea 2: a model trained for retrieval
all-MiniLM-L6-v2 is a general sentence-similarity model: "do these two texts say similar things?".
Search is lopsided: a 5-word question against a 60-word passage that *answers* it.
**bge-small-en-v1.5** (same size: 384 numbers, same 30,522-piece vocabulary) was trained mainly for
retrieval, and it asks you to put an instruction in front of **queries only**:
"Represent this sentence for searching relevant passages: how do plants make food". The prefix tells the
model "this is a question looking for a passage", so the query vector moves toward answer-like passages.
bge also uses the **[CLS] vector** (the first output) instead of the average of all pieces. My tokenizer
needed no change (1,000/1,000 identical ids), and the Java query vectors matched Python with min cosine
1.0000000. Variants D and E test it.

### Idea 3: two vectors per article, take the max
Keep both a short vector (60 words) and a long one (200 words) for each article. similarity(article) =
**max**(sim to short, sim to long): the article counts as close if *either* view is close. The cost is 2× the
vectors (memory, brute-force time), and an HNSW graph with 2 nodes per article. When we ask the graph for
the top 10 articles, we ask for 20 nodes and keep each article's first (best) appearance. 20 nodes always
contain at least 10 different articles. Variants C and E test it.

### Idea 4: keyword-weighted RRF
Plain RRF gives both lists the same vote. On name-like queries ("diagnostic", "single word") keyword search
was already perfect (MRR 1.000) and hybrid made it worse (0.861, 0.907): the meaning list pushed in
look-alike articles. A weight changes the vote: score(d) = **2** / (60 + keyword rank) + 1 / (60 + meaning rank).

Same example as before: keyword = [Plant, Leaf, Photosynthesis], meaning = [Photosynthesis, Sugar]:
- Photosynthesis: 2/63 + 1/61 = 0.0317 + 0.0164 = **0.0481** (still first: good in both lists)
- Plant: 2/61 = 0.0328, Leaf: 2/62 = 0.0323
- Sugar: 1/62 = 0.0161, now clearly below Leaf (before it was a tie)

Meaning can still lift an article that is in both lists, but a meaning-only article finds it harder to push
out a strong keyword match.

### Why the old held-out rule was too strict
The rule was "Smart MRR must not be lower than keyword MRR" on the old 20 held-out queries. With 20
queries, one article moving from #1 to #2 changes that query's score from 1/1 to 1/2. The MRR then drops by
0.5 / 20 = **0.025**. That is exactly what happened ("narwhal", 0.763 → 0.748). A rule that one rank flip
can fail measures noise, not quality. The fix is to judge on more queries and to decide in advance how much
difference counts. Here the selection uses the main 50 with a 0.01 tie margin. The final verdict comes once,
in Phase 6, on the untouched 30 queries in `queries-holdout-v2.json`. The old 20 are never re-run, because
after looking at them they are no longer "unseen".

### What the experiment found (`eval/results/fix-variants.txt`)
Exact search over int8 vectors, main 50, overall MRR (the rule uses the hybrid columns only):
| Variant | semantic only | hybrid, plain RRF | hybrid, keyword weight 2 | Photosynthesis (plain RRF) |
| --- | --- | --- | --- | --- |
| A. MiniLM, 200 words | 0.665 | 0.868 | 0.871 | #4 |
| B. MiniLM, 60 words | 0.885 | 0.928 | 0.919 | #2 |
| C. MiniLM, 60 + 200, max | 0.894 | 0.932 | 0.922 | #2 |
| **D. bge, 60 words, prefix** | 0.957 | **0.956** | 0.934 | **#2** |
| E. bge, 60 + 200, max, prefix | 0.955 | 0.944 | 0.925 | #3 |

Rule 1: the best is D with plain RRF (0.956). Rule 2: nothing else is within 0.01 (E is 0.944, needs
≥ 0.946), so there was no tie to break. Keyword search alone: 0.868.

What I learned from the table:
- **Dilution was real.** Same model, fewer words: 0.665 → 0.885 for meaning alone (A → B).
- **The retrieval model helped most.** bge 60 words beat MiniLM 60 words: 0.885 → 0.957 (B → D).
- **Two vectors helped only a little, then hurt.** MiniLM gained 0.004 (B → C); bge lost 0.012 (D → E).
  The long vector adds the drifting text back in.
- **Keyword weight 2 hurt with good vectors** (D 0.956 → 0.934). Once the meaning list is good, giving
  keywords the louder vote mainly hurts question queries (0.826 → 0.709).
- With D, the categories keyword search already got right (diagnostic, single, multi, obscure) all stay
  1.000. The first Smart version made them worse.

## Complexity
| Operation | Time | Space |
| --- | --- | --- |
| WordPiece, query of n characters | O(n · w) with w = longest word (≤ 100) | O(n) |
| Model run (6 transformer layers, t tokens) | O(t² · d + t · d²), d = 384 | small |
| Brute force, N vectors of d numbers | O(N · d) ≈ 108 million multiply-adds | O(K) heap |
| HNSW search | ≈ O(log N) layers + O(ef · M · d) on layer 0 | O(N) visited stamps per thread (reused) |
| HNSW build | ≈ O(N · log N · efConstruction · M · d) | graph O(N · M) ints |
| RRF of two lists of L | O(L log L) | O(L) |
| int8 vs float32 | same O(); 4× fewer bytes to read | N·d bytes + N floats |

## Interview questions
1. **Why normalise the vectors to length 1?**
   Then the dot product *is* the cosine similarity, so ranking needs only multiply-adds, no square roots or
   divisions, and the vector length (which partly reflects text length) stops influencing the score.
2. **Why write the tokenizer yourself, and how do you know it is right?**
   It was a project rule (the core is my own code), and it is the riskiest silent bug: one wrong id changes
   the vector a little and never crashes. A JUnit test compares my ids with the model's own tokenizer on
   1,000 strings (accents, punctuation, numbers, emoji, CJK, control characters), and a second check
   compares whole Java vs Python query vectors (cosine ≥ 0.9999 on 200 queries).
3. **What do M, efConstruction and efSearch trade off?**
   M: links per node: higher = better recall and more memory, slower build. efConstruction: search width
   while building: better graph, slower build. efSearch: search width per query: higher recall, slower
   query. Only efSearch can be changed without rebuilding, so it is the knob we tuned with a fixed rule.
4. **When would you *not* use HNSW?**
   When N is small enough that brute force is fast (we measured brute force at 26 ms for 280k vectors),
   when you need exact results, or when memory is tight (the graph adds ~39 MB; product quantisation
   or an IVF index uses less). HNSW also handles deletes poorly.
5. **Why RRF instead of adding the scores?**
   BM25 and cosine live on different scales that change from query to query; adding them needs a weight
   that would have to be tuned (and would overfit 50 queries). RRF needs only ranks, has one constant
   (k = 60, the published default), and is robust: a document good in both lists rises.
6. **Your first semantic search ranked the obvious answer #20. How did you decide what to change?**
   I checked the cheapest explanation first: exact Python search also gave #20, so my tokenizer, ONNX and
   HNSW were not the cause. The problem was the model and how the article text was turned into a vector. I
   wrote down five variants and the selection rule *before* running anything, evaluated all of them with
   exact search (so HNSW error could not blur the comparison), and let the rule pick. I did not tune on the
   one query I cared about, because that overfits to one query.
7. **Why can a shorter text give a better document vector?**
   Mean pooling averages every piece of the text. A long passage that covers many sub-topics averages to
   a vector that is moderately close to all of them and very close to none. The opening of an encyclopedia
   article is a definition, the part a short query most often asks about. Taking the max over a short and
   a long vector keeps both: focused matches from the short one and coverage from the long one, at 2× the
   memory.
8. **Your held-out check failed by one query. Why not just change the rule and move on?**
   Changing a rule *after* seeing it fail is the mistake held-out sets exist to prevent. So I explained the
   problem openly: with 20 queries one #1 → #2 flip moves MRR by 0.025, which is noise. I stopped using those
   20, since they are no longer unseen. The next judgement uses a new frozen set of 30 that has never been
   run, once, in Phase 6. The new rule (0.01 tie margin on 50 queries) was written before the experiment.
