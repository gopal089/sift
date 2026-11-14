# Phase 6 — Prove quality and speed

## What we did, in simple words
Until now every number was measured on queries I had looked at while choosing settings. Phase 6 asks two
honest questions and answers them with numbers:
1. **Is it good on queries it has never seen?** One final run on 30 frozen held-out queries.
2. **Is it fast when many people use it at once?** A cache for repeated queries, then a load test with k6.

The one-page results table is `eval/results/final-summary.md` (it feeds the README).

## Status: done (2026-11-14)
| Check | Result |
| --- | --- |
| Alias field re-test (main 50, rule: keyword +0.01 and hybrid not −0.01) | keyword +0.0076 → **not adopted** |
| Held-out v2, run once: baseline / keyword / Smart MRR | 0.305 / 0.774 / **0.857** |
| Default-mode rule (Smart ≥ keyword − 0.01) | 0.857 ≥ 0.764 → **Smart is the website default** |
| Cache hit vs miss (HTTP median) | 0.6 ms vs 2.8 ms keyword, 6.0 ms Smart |
| Load test, cache off, 10 users | keyword 1,663 req/s (p95 23 ms), Smart 386 req/s (p95 54 ms), 0 errors |

## New files
| File | What it is for |
| --- | --- |
| `eval/results/final-summary.md` | All final numbers in one place, each with its raw file. |
| `eval/results/holdout-v2-*.txt/.tsv` | The single run on `queries-holdout-v2.json`. |
| `eval/results/phase6-alias-wa0/1.*` | The alias re-test (same index, alias bonus off and on). |
| `api/.../EngineConfig.java` (`searchCache`) | The Caffeine cache: up to 10,000 finished result pages. |
| `api/.../SearchController.java` | Looks in the cache first; on a miss computes the page and stores it. `/stats` shows hits and misses. |
| `eval/measure_cache.py` | Sends each query twice and compares miss vs hit latency. |
| `loadtest/make_queries.py`, `queries.json` | 2,000 realistic queries: titles, phrases, questions, typos, single words. |
| `loadtest/search.js` | The k6 script: each virtual user searches in a loop, queries picked with a Zipf skew. |
| `loadtest/run-all.sh`, `loadtest/results/` | Runs the 8 load tests (2 modes × 2 user counts × cache off/on) against fresh servers. |
| `web/src/urlState.ts` | `DEFAULT_MODE = "smart"`, set by the held-out rule. |

## The key ideas, each with a small worked example

### 1. MRR and hit@k
For each query we find the **rank** of the one expected article. Reciprocal rank = 1 / rank (0 if not found).
MRR = the average over all queries.

Example with 3 queries: ranks 1, 3, not found → (1/1 + 1/3 + 0) / 3 = 1.333 / 3 = **0.444**.
hit@3 for the same queries: rank 1 ✓, rank 3 ✓, not found ✗ → **2/3**.

MRR rewards being *first* much more than being *near the top*: going from #2 to #1 adds 0.5 for that query,
from #10 to #9 adds only 0.011. That matches how people use search: most only click the first result.

**Why no precision@10?** Precision@10 = (relevant results in the top 10) / 10. Our queries each have exactly one
expected article, so the best possible precision@10 is 1/10 = 0.1, and it is just hit@10 divided by 10. It tells
us nothing new. It would make sense with many relevant articles per query, judged by people.

### 2. Held-out sets and overfitting
**Overfitting** = settings that fit the queries you tuned on, but not new ones. Every time I chose something
(title weight, PageRank α, the embedding variant) by looking at the main 50, the main-50 score became a little
optimistic. The proof is in the numbers:

| | Main 50 (tuned on) | Held-out v2 (never seen) |
| --- | --- | --- |
| Keyword | 0.868 | 0.774 |
| Smart | 0.956 | 0.857 |

Both drop by about 0.1 on unseen queries. The held-out number is the one to believe. Rules that keep it honest:
- Write the held-out queries *before* building the feature, and freeze them (commit them).
- Run them **once**, at the end, with a decision rule written before the run.
- Never change anything because of that run. If you peek and tune, it stops being held out. That is why the old
  held-out 20 were retired: I had looked at them several times.

A held-out set of 30 is still small: one query moving from #1 to #2 changes MRR by 0.5 / 30 = 0.017.
That is why the default-mode rule allowed a 0.01 margin and the result was far outside it (0.857 vs 0.774).

### 3. Caching
A cache keeps answers we have already computed. The index never changes while the server runs, so the same
(query, mode, page) always gives the same page, and a stored page never goes stale.

- **Key** = mode + page + query (after trimming spaces). Different pages or modes are different entries.
- **Value** = the finished page: results, snippets and "did you mean". On a hit we skip ranking, the model and
  the snippets; only the timing in `stats` is new.
- **Size limit** = 10,000 pages. A page is about 5–10 KB, so at most ~100 MB. When full, Caffeine drops the entries
  least likely to be used again (it tracks how often *and* how recently each key was used).

Worked example: "photosynthesis" in Smart mode. First request: a miss, the server computes it in ~5 ms
(2.5 ms of that is the neural model) and stores it. Second request: a hit, 0.006 ms inside the server. The
remaining 0.6 ms of the HTTP round trip is Tomcat, JSON and the network stack.

A bug I found while load testing: with the size set to 0 ("off"), a few requests were still hits, because
Caffeine removes entries in the background a moment after adding them. The server now skips the cache completely
when the size is 0, so "off" really is off.

### 4. Load testing
A **virtual user** (VU) in k6 is a loop: send a request, wait for the answer, send the next. 10 VUs = 10
requests in flight at all times. There is no pause between requests, so this is the most load 10 busy users can
make, not 10 real humans (who would pause to read).

Two lessons from our runs:
- **Throughput stops growing when the CPU is full.** Keyword: 10 VUs → 1,663 req/s, 50 VUs → 1,800 req/s.
  Five times the users, almost the same throughput. The extra requests just wait in a queue.
- **Then latency grows instead.** Little's law: users = throughput × time per request, so
  time ≈ 50 / 364 req/s ≈ 137 ms for Smart at 50 VUs (measured p50 115 ms, p95 282 ms). A single Smart query takes
  ~6 ms; the rest is waiting. The fix for that is more CPU (more instances), or the cache.

The query mix matters. Real traffic has a few very popular queries and a long tail, so the script picks
query number r with probability ∝ 1/r (a Zipf distribution). With only 2,000 distinct queries and ~50,000
requests per second, the cache held all of them after seconds (99.9% hits), so the cache-on run shows the
cache's best case, not a typical day. The cache-off run is the honest engine capacity.

### 5. p50, p95, p99
Sort all request times. p50 (median) = the time half of the requests beat. p95 = 95% beat it. p99 = 99% beat it.

Example, 10 request times in ms: 2, 2, 3, 3, 3, 4, 4, 5, 9, 40. p50 = 3 ms (5th value), the **average** = 7.5 ms.
One slow request (40 ms) pulls the average up, so the average describes no real user. p95 shows the slow tail:
a user who searches 100 times meets the p99 time about once. That is why the targets are written as "p95 < 100 ms".

## Complexity
| Operation | Time | Space |
| --- | --- | --- |
| MRR / hit@k over Q queries, ranking up to N docs each | O(Q · search) + O(Q) | O(Q) |
| Cache lookup or insert | O(1) on average (hash map) | O(C · page size), C ≤ 10,000 |
| Zipf pick in k6 (binary search over cumulative weights) | O(log Q) per request | O(Q) per VU |
| Percentiles over R measurements | O(R log R) (sort) | O(R) |

## Interview questions
1. **Your MRR is 0.956 on one set and 0.857 on another. Which do you report?**
   The held-out 0.857. The 0.956 set was used to choose settings, so it is optimistic. That is
   overfitting to the evaluation set. The held-out queries were written before the features, frozen in git
   and run once, with the decision rule written down first.
2. **Why MRR and not precision@10?**
   Each query has one known best article, and what matters is how high it is. MRR measures exactly that and
   rewards first place strongly. Precision@10 needs several relevant documents per query. With one, it is capped
   at 0.1 and is just hit@10 / 10.
3. **How do you make sure a cache never serves a wrong answer?**
   The cached value must depend only on the key. Here the index is read-only while the server runs, so
   (mode, page, query) fully decides the page. If the index could change, I would version the key with the
   index build id, or clear the cache on reload. The size is bounded so memory cannot grow forever, and the API
   test checks that a hit returns exactly the same body as the miss.
4. **Throughput hardly changed from 10 to 50 users, but p95 went from 23 ms to 130 ms. Why?**
   At 10 users the CPU is already nearly busy. More concurrent users cannot get more CPU, so they queue.
   By Little's law, at a fixed throughput, more requests in flight means each waits longer. To serve 50 busy
   users with a low p95, you add instances or cut CPU per request (the cache does that for repeated queries).
5. **Why report p95 and p99 instead of the average?**
   Latency is skewed: most requests are fast, a few are slow (garbage collection, a rare long query, queueing).
   The average mixes both and describes nobody. p95 and p99 describe what the slowest 5% and 1% of requests
   experience, and a user who makes many requests will meet them.
