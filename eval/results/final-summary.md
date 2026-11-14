# Sift — final results (Phase 6)

Every number below comes from a real run on one laptop (Apple M5, 10 cores, 16 GB, Java 21). The raw file is
named in each row. MB = 1,048,576 bytes. Server numbers are **laptop numbers**; they are re-measured on the
server in Phase 7.

## Ranking quality

MRR = mean reciprocal rank of the one expected article (1.0 = always first). hit@k = queries whose expected article
is in the top k.

| Query set | Ranking | MRR | hit@1 | hit@3 | hit@10 | Raw file |
| --- | --- | --- | --- | --- | --- | --- |
| **Main 50** (used to choose settings) | Baseline BM25 (Phase 1) | 0.275 | 9/50 | 15/50 | 21/50 | `baseline-bm25.txt` |
| | Final keyword (BM25 sum + PageRank) | 0.868 | 42/50 | 44/50 | 45/50 | `smart-main-bge60.txt` |
| | Final Smart (hybrid RRF, bge-small) | **0.956** | 47/50 | 48/50 | 50/50 | `smart-main-bge60.txt` |
| **Held-out v2, 30** (run once, never tuned on) | Baseline BM25 | 0.305 | 4/30 | 15/30 | 18/30 | `holdout-v2-baseline.txt` |
| | Final keyword | 0.774 | 21/30 | 24/30 | 28/30 | `holdout-v2-final.txt` |
| | Final Smart | **0.857** | 23/30 | 29/30 | 30/30 | `holdout-v2-final.txt` |
| Old held-out 20 (seen several times, history only) | Baseline / keyword / Smart (MiniLM) | 0.312 / 0.763 / 0.748 | | | | `holdout-final.txt`, `smart-holdout-sanity.txt` |

Held-out v2 per category (MRR):

| Ranking | famous-single (9) | famous-multi (7) | obscure (4) | question (6) | abbreviation (4) |
| --- | --- | --- | --- | --- | --- |
| Baseline BM25 | 0.165 | 0.370 | 0.583 | 0.172 | 0.428 |
| Final keyword | 1.000 | 0.929 | 1.000 | 0.236 | 0.573 |
| Final Smart | 0.944 | 1.000 | 1.000 | 0.561 | 0.708 |

The main 50 score is higher than held-out because settings were chosen on the main 50 (overfitting). The held-out
v2 number is the honest one. Precision@10 is not reported: each query has exactly one expected article, so
precision@10 is at most 0.1 and only repeats hit@10 / 10.

Decisions made with rules fixed **before** these runs:
- **Alias field** (main 50): keyword MRR 0.86760 → 0.87519 (+0.0076, needed ≥ +0.01), so **not adopted**
  (`phase6-alias-wa0.txt`, `phase6-alias-wa1.txt`).
- **Website default mode**: Smart 0.857 ≥ keyword 0.774 − 0.01 on held-out v2, so **Smart is the default**.

## Spelling ("did you mean")
| Measure | Value | Raw file |
| --- | --- | --- |
| Typo set (15 misspelled queries, frozen first) | **12 / 15 correct (80%)** | `typo-accuracy.txt` |
| Slowest suggestion | 12.1 ms | same |

## Speed (single requests)
| Measure | Keyword | Smart | Raw file |
| --- | --- | --- | --- |
| Inside the server, no cache (1,200 queries) | median 0.57 ms, p95 5.5 ms | median 5.3 ms, p95 11.9 ms | `phase5-fix-api-memory.json` |
| HTTP round trip, no cache | median 1.2 ms, p95 6.1 ms | median 6.2 ms, p95 13.0 ms | same |
| Cache miss, HTTP (200 queries) | median 2.8 ms, p95 8.4 ms | median 6.0 ms, p95 12.1 ms | `phase6-cache.json` |
| Cache hit, HTTP | median 0.67 ms, p95 0.84 ms | median 0.62 ms, p95 0.78 ms | same |
| Cache hit, inside the server | 0.006 ms | 0.006 ms | same |
| Server start (index + model + graph) | 2.3 s | | `phase5-fix-api-memory.json` |

## Load test (k6, 60 s per run, 2,000-query realistic mix, Zipf popularity)
k6 and the server ran on the same laptop and shared its 10 cores. Each run used a fresh server.
**Cache off** = every request is fully computed: this is what the engine can do. **Cache on** = with only 2,000
distinct queries the cache holds all of them after a few seconds (99.9% hits), so it is a best case, not typical.

| Mode | Cache | Virtual users | Requests/s | p50 | p95 | p99 | Errors | Raw file |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Keyword | off | 10 | 1,663 | 1.65 ms | 23.2 ms | 32.3 ms | 0% | `loadtest/results/cache-off-keyword-10.json` |
| Keyword | off | 50 | 1,800 | 10.8 ms | 130.2 ms | 208.8 ms | 0% | `cache-off-keyword-50.json` |
| Smart | off | 10 | 386 | 22.7 ms | 54.2 ms | 68.9 ms | 0% | `cache-off-smart-10.json` |
| Smart | off | 50 | 364 | 114.6 ms | 282.1 ms | 545.1 ms | 0% | `cache-off-smart-50.json` |
| Keyword | on | 10 | 51,288 | 0.13 ms | 0.24 ms | 0.42 ms | 0% | `cache-on-keyword-10.json` |
| Keyword | on | 50 | 54,065 | 0.70 ms | 1.65 ms | 3.27 ms | 0% | `cache-on-keyword-50.json` |
| Smart | on | 10 | 45,278 | 0.15 ms | 0.22 ms | 0.34 ms | 0% | `cache-on-smart-10.json` |
| Smart | on | 50 | 47,690 | 0.77 ms | 1.73 ms | 3.29 ms | 0% | `cache-on-smart-50.json` |

At 50 users without the cache, the CPU is full: requests wait in a queue, so latency grows while requests/s stays
flat (50 users ÷ 364 req/s ≈ 137 ms average, which matches the measured p50 of 115 ms). An earlier cache-off run (before a
fix that made "size 0" skip the cache completely; its files were overwritten by the rerun) gave Smart at 10 users
441 req/s and p95 41 ms. Treat laptop runs as varying by roughly 15%.

## Memory
| Measure | Value | Raw file |
| --- | --- | --- |
| Server RSS after start / after 1,200 queries | 784 MB / 1,059 MB | `phase5-fix-api-memory.json` |
| Java heap after GC | 275 MB (limit 512 MB) | same |
| Server RSS after the 60 s load tests | 1,146–1,331 MB | `loadtest/results/*-server.json` |

RSS includes the memory-mapped index and vector files that the OS has loaded.

## Sizes
| Part | Size | Raw file |
| --- | --- | --- |
| Articles | 280,748 | `data/parse-stats.json` |
| Index on disk (all files) | 413.7 MB, of which article text 267.0 MB | `phase4-index-build.txt` |
| Posting lists, before → after gap + varint | 364.4 MB → 111.9 MB (30.7%) | same |
| Vectors, float32 → int8 | 411.3 MB → 103.9 MB | `hnsw-build-bge60.txt` |
| HNSW graph file | 36.8 MB | same |
| bge-small ONNX model | 126.9 MB | `data/model-bge/model.onnx` |
