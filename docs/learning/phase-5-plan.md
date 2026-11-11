# Phase 5 plan — Smart (meaning) search (written before building)

## Goal
Find articles by *meaning*, not only by shared words. "how do plants make food" never says
"photosynthesis", but a sentence-embedding model puts both near each other. Smart mode mixes this with
the keyword engine; Keyword mode stays exactly as it is.

## Plan

### 0. Fresh held-out set (done first)
`eval/queries-holdout-v2.json`: 30 new queries (9 single, 7 multi, 4 obscure, 6 questions,
4 abbreviations), no expected article reused from the main 50 or the old held-out 20. Only the titles
were checked. Nothing runs on it until Phase 6.

### 1. Embeddings (Python, `data-prep/`, one-time)
- Model: `sentence-transformers/all-MiniLM-L6-v2`, 384 dimensions. Its sentence vector is the **mean of
  the token vectors** (attention mask applied), then scaled to length 1 (L2-normalised).
- **ONNX export** with `torch.onnx.export` (torch + onnx are allowed; this avoids needing `optimum`'s
  separate exporter package). Inputs `input_ids`, `attention_mask`, `token_type_ids` (int64, dynamic
  batch and length); output = token vectors. Pooling and normalising happen in our own code, so the
  ONNX file is just the transformer. Saved with `vocab.txt` + `tokenizer_config.json` in `data/model/`.
- **Check in Python** that ONNX token vectors → our pooling equals sentence-transformers' output
  (cosine ≥ 0.9999) before embedding anything.
- **Document text** = `title + ". " + first 200 words of the text` (words = whitespace split). The model
  reads at most 256 word pieces; longer input is cut (same as sentence-transformers does).
- Embed all 280,748 articles with sentence-transformers on the Mac GPU (MPS; CPU fallback), batch 128,
  in `articles.jsonl` order = the index's doc id order.
- Output `data/vectors/vectors-f32.bin`: little-endian float32, row *d* = doc id *d*; plus
  `vectors-meta.json` (count, dims, model, text recipe, sha256 of the titles in order). Java refuses to
  load vectors whose count or title hash do not match the index (protects against a stale file).

### 2. Query embedding in Java
- **WordPiece tokenizer written by hand** (`engine/.../embed/WordPieceTokenizer`), the BERT "uncased"
  rules: clean control chars, put spaces around CJK characters, lowercase, strip accents (Unicode NFD,
  drop combining marks), split on whitespace and punctuation, then greedy longest-match-first word
  pieces with `##` for word continuations; a word with no match (or > 100 chars) → `[UNK]`;
  add `[CLS]` … `[SEP]`; cut to 256.
  `java.text.Normalizer` (the JDK) does NFD; that is plumbing, not tokenizer logic.
- **Parity test 1:** 1,000 strings (titles, sentences, numbers, accents, punctuation, emoji, CJK,
  control chars) tokenized in Python by the model's own tokenizer → expected ids saved as a test fixture
  in `engine/src/test/resources/wordpiece/` with a copy of `vocab.txt` (Apache-2.0, 226 KB).
  A JUnit test demands **exactly** the same ids for all 1,000.
- **Embedder** (`QueryEmbedder`): ONNX Runtime for Java runs the model; our code mean-pools and
  normalises. One `OrtSession`, reused (it is thread-safe for `run`).
- **Parity test 2:** 200 queries (the main 50 + 150 random titles/phrases; no held-out queries):
  Java vector vs Python vector, cosine ≥ 0.9999 each. Saved to `eval/results/`.
- Report median and p95 query-embedding time.

### 3. Vector search
- **Brute force** (`BruteForceSearch`): dot product with every vector (vectors are length 1, so dot
  product = cosine), size-K min-heap (the one from Phase 1). Exact; the reference for recall.
- **HNSW** (`Hnsw`), written by hand: layered graph; each node gets a random top layer
  (`floor(-ln(U) × 1/ln(M))`); insert = greedy descent through upper layers, then a beam search of width
  `efConstruction` on each lower layer, keep up to M neighbours (2M on layer 0) chosen with the
  standard *diversity heuristic*, and connect both ways (pruning a neighbour's list if it overflows).
  Search = greedy descent, then a beam of width `efSearch` on layer 0. M = 16, efConstruction = 200,
  fixed random seed. Single-threaded build (simple and deterministic).
- The graph is built offline and saved to `data/index/hnsw.bin` so the server loads it in a second.
- **Recall test:** 500 random article titles (seed 42), embedded in Java. recall@10 = |HNSW top 10 ∩
  exact top 10| / 10, averaged. efSearch ∈ {50, 100, 200}. **Rule:** the smallest efSearch with
  recall@10 ≥ 0.95. Report build time, graph memory, recall, median/p95 latency.

### 4. int8 vectors
- Per vector: `scale = max|x| / 127`, store `round(x / scale)` as a byte + the float scale.
  Score = `scale × Σ q[i] × b[i]` (query stays float). 4× smaller: 431 MB → 108 MB.
- Measured: (a) quantisation alone: int8 brute-force top 10 vs float32 brute-force top 10 on the same
  500 queries; (b) end to end: Smart MRR on the main 50 with float32 vs int8 (same HNSW graph).
- **Rule:** adopt int8 if (a) recall@10 ≥ 0.97 **and** hybrid MRR drops ≤ 0.01.

### 5. Hybrid + Smart mode
- **Semantic only:** HNSW top 100.
- **Hybrid (RRF):** keyword top 100 + semantic top 100; `score(d) = Σ 1 / (60 + rank)` over the lists
  that contain d (rank starts at 1). Ties → smaller doc id. RRF uses only ranks, so the very different
  score scales (BM25 ≈ 5–40, cosine ≈ 0.2–0.8) never need to be matched.
- **Eval:** the main 50, overall and per category: keyword (current default, full ranking as before),
  semantic, hybrid. For the two new modes only the top 100 exists; an article not in it counts as not
  found (its MRR share would be < 0.01 anyway). Same note in every results file.
- **Rule:** Smart = whichever of semantic / hybrid has the higher overall MRR; within 0.01 → hybrid.
  Sanity only (old held-out 20, not used to choose): Smart must not be below keyword overall.
- **API:** `mode=smart` → Smart pipeline; `engine` field says which; stats add `embedMs`. Snippets use the
  query's keyword terms when the article has them, else the article's opening. The placeholder notice is
  removed. Website: no change except the mode text. Pages 1–10 in Smart mode (top 100).

### 6. Measure and document
- Done when: "how do plants make food" → Photosynthesis in the top 3 (Smart), p95 Smart time < 100 ms
  (embedding included), all tests green.
- Memory: heap used and process RSS (`ps`) for the server with float32 vs int8 vectors.
- `docs/learning/phase-5.md`, `PROGRESS.md`, small commits.

## Strict self-review

| # | Concern | Decision |
| --- | --- | --- |
| 1 | **Doc and query vectors must live in the same space.** Docs are embedded by PyTorch, queries by ONNX in Java. | Two checks bridge it: ONNX-in-Python vs sentence-transformers (≥ 0.9999), and Java vs Python (≥ 0.9999 on 200 queries). |
| 2 | **Tokenizer drift** is the classic silent bug: one different id changes the vector a little, never crashes. | Exact-id test on 1,000 strings, with the nasty cases on purpose. It is a JUnit test, so CI keeps checking it. |
| 3 | **Doc order mismatch** (vector *d* belonging to another article) would ruin results quietly. | Count + title hash in `vectors-meta.json`, checked at load. |
| 4 | **Memory.** float32 = 431 MB. The server heap is 512 MB. | Vectors are memory-mapped (off-heap, like the index), read through a `FloatBuffer`/`ByteBuffer` view. Heap holds only the HNSW graph (~40 MB). Report heap *and* RSS, because mapped pages count in RSS (what the host bills). |
| 5 | **HNSW build time.** 280k inserts × efConstruction 200 is tens of millions of distance computations. | Single thread, measured; built once offline and saved. If it is over ~30 min, report it and consider a parallel build later. |
| 6 | **Recall measured on what?** Random vectors from the collection are easier than real queries. | Real text (titles) embedded by the Java path; the query vector is *not* an indexed vector. Exact top 10 from brute force. Seeded, so repeatable. |
| 7 | **Truncation hides the article body.** Only ~200 words are embedded. | That is the CLAUDE.md recipe; the keyword half of hybrid covers the rest of the text. Noted as a limit. |
| 8 | **Choosing efSearch / int8 / Smart on the same 50 queries** that already tuned keyword ranking (overfitting risk). | Rules were fixed before measuring (this file). efSearch and int8 recall use the 500 random titles, not the eval set. The held-out 20 is only a sanity check; the fresh 30 stays untouched for Phase 6. |
| 9 | **Unknown words** (`[UNK]`) and empty queries. | Queries with no tokens other than `[CLS] [SEP]` still embed; Smart for an empty-term query returns the same friendly notice as Keyword. |
| 10 | **ONNX Runtime is a native library** (C++ inside the jar). | Allowed by CLAUDE.md. One session at startup; failure to load the model = the server refuses to start with a clear message, like a missing index. |
| 11 | **Thread safety.** | `OrtSession.run` is thread-safe; HNSW search allocates its own visited set per call; mapped buffers are read through `duplicate()`. |
| 12 | **Model download.** | Free, from the Hugging Face hub, into `data/` (git-ignored). The exact model revision is recorded in `vectors-meta.json`. |
| 13 | **Python libraries.** sentence-transformers pulls in `transformers`, `tokenizers`, `huggingface_hub`, `numpy` as its own dependencies. | They come with the allowed packages; we call only sentence-transformers, torch, onnx and onnxruntime directly. `optimum` is not needed. |
