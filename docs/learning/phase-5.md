# Phase 5 — Smart (meaning) search

## What we built, in simple words
Keyword search only finds articles that share words with the query. "how do plants make food" never says
"photosynthesis", so keywords alone rank it low. Phase 5 adds **meaning search**:

1. A small neural network (all-MiniLM-L6-v2) turns any text into a list of 384 numbers, an **embedding**.
   Texts with similar meaning get similar lists.
2. We embedded every article once (title + first 200 words) in Python and saved the 280,748 vectors.
3. At query time Java embeds the query (our own WordPiece tokenizer + the model in ONNX Runtime + our own
   pooling) and finds the most similar article vectors with our own **HNSW** graph.
4. **Smart mode** mixes the keyword list and the meaning list with **Reciprocal Rank Fusion** (RRF).

## Status: built and measured, but NOT done
Two checks fail, so I stopped for a decision (see PROGRESS.md "Next step"):
- **Done when:** "how do plants make food" → Photosynthesis is **#4** in Smart mode (needs top 3).
  Even the exact Python search ranks it #20 for meaning alone, so the cause is the model + the
  "title + first 200 words" recipe, not my tokenizer, ONNX or HNSW.
- **Held-out sanity:** Smart 0.748 vs keyword 0.763 on the old 20 (one query, "narwhal", went #1 → #2).

## Results (all from real runs; raw files in `eval/results/`)
| What | Number |
| --- | --- |
| WordPiece ids, Java vs model tokenizer | 1,000 / 1,000 strings identical |
| Query vector, Java vs Python | min cosine 1.0000000 on 200 queries |
| Query embedding (Java, 1 thread) | median 0.97 ms, p95 1.22 ms |
| Embedding all 280,748 articles (Mac GPU) | 641.5 s |
| HNSW build (M 16, efConstruction 200, 1 thread) | 225 s; graph 39.2 MB |
| recall@10 at efSearch 50 / 100 / 200 | 0.878 / 0.931 / 0.966 → **200** (rule: smallest ≥ 0.95) |
| HNSW vs brute force time | 0.83 ms vs 26.0 ms median |
| int8 | 103.9 MB vs 411.3 MB; recall vs float32 0.995 → **adopted** |
| MRR main 50: keyword / semantic / hybrid | 0.868 / 0.649 / 0.868 → Smart = **hybrid** |
| MRR per category, keyword → hybrid | questions 0.415 → 0.630, abbreviations 0.516 → 0.751, diagnostic 1.000 → 0.861, single 1.000 → 0.907, obscure 1.000 → 0.938 |
| Smart query time in the server | median 3.3 ms, p95 10.0 ms |
| Server RSS after 1,200 queries, int8 / float32 | 1,013 MB / 1,332 MB |

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
