# Definition card and related articles — learning notes

## What we built, in simple words
When you search for the name of a thing ("photosynthesis", "einstein", "world war 2"), Sift now shows a short
**card** above the results. It has the article's title, its first one or two sentences, and five **related
articles**: the articles closest in meaning to it. It is like the box a big search engine shows for a name, but
built only from our own index, with no calls to other websites.

The card appears only when Sift is sure. The query must have **exactly the same words** as the top article's title,
or as a redirect name for it ("Einstein" is a redirect to *Albert Einstein*). On the 50 main test queries the card
appears for 40 (Smart mode), and it is never about the wrong article.

The results list does not change at all. The card only reads the top result after ranking is finished.

We also researched a "Discussions" tab (Hacker News, Mastodon, Bluesky, Wikipedia page views). We decided **not**
to build it (DESIGN.md §15): only one source was free and open, its results were often off-topic, and it was more than
100 times slower than our own search (median 802 ms against about 6 ms).

## Each new file and what it is for

| File | Purpose |
| --- | --- |
| `engine/.../card/DefinitionRule.java` | Decides if the card is shown: query terms = title terms, or = a redirect's terms |
| `engine/.../card/OpeningSentences.java` | Cuts the first 1–2 sentences from the article text (own sentence splitter) |
| `engine/.../card/RelatedArticles.java` | The 5 nearest articles in meaning, using the article's own vector and our HNSW |
| `api/.../SearchController.java` (changed) | Builds the card on page 1, after ranking; adds `definition` and `stats.cardMs` |
| `api/.../EngineConfig.java` (changed) | Loads the redirects (`data/redirects.jsonl`) and shares the vector store |
| `web/src/components/DefinitionCard.tsx` | The card: title link, text, source line, related articles as Sift searches |
| `eval/card_rule_probe.py`, `card_redirect_probe.py`, `card_check.py` | Measure the rule and the real cards on the main 50 |
| `docs/design/review/review-card.mjs` | Re-runs the design checks (keyboard, tap size, 320 px, layout shift) on card pages |

## Key idea 1: an exact rule instead of a score threshold
A tempting rule is "show the card if the top score is much bigger than the second". But scores mean different
things in Keyword and Smart mode, and any threshold must be tuned, so it can be over-fitted to our 50 queries.

Our rule has nothing to tune: **the set of query terms equals the set of title terms.** Both sides go through the
same `Tokenizer.queryTerms` (lowercase, drop stop words and question words, Porter stem).

Worked example:

| Query | Query terms | Top title | Title terms | Card? |
| --- | --- | --- | --- | --- |
| the history of india | {histori, india} | History of India | {histori, india} | yes (title) |
| fibonacci numbers | {fibonacci, number} | Fibonacci number | {fibonacci, number} | yes: stemming makes plural = singular |
| einstein | {einstein} | Albert Einstein | {albert, einstein} | not by title… |
| | | redirect "Einstein" → Albert Einstein | {einstein} | …yes (redirect) |
| what is the capital of australia | {capit, australia} | Australia | {australia} | **no**, which is right: the answer is Canberra |

Rules I measured and rejected (main 50, Smart):
- "Title words ⊆ query words": 36 cards, 1 wrong (the Australia case above).
- "Keyword and Smart agree on #1": 43 cards, 1 wrong.

A wrong card looks like an answer, so precision matters more than showing more cards.

## Key idea 2: finding sentence ends
"Split at every full stop" breaks on "Martin Luther King, Jr. (born…", "J. R. R. Tolkien", "U.S." and "Ph.D.". Our
splitter ends a sentence at `.` `!` or `?` only if:
1. A space and then a capital letter, digit, quote or bracket come next, **and**
2. the word before the dot is **not** a single letter (an initial), does not contain a dot ("U.S", "Ph.D"), and is
   not a known short form ("Jr", "Dr", "St", "c", …).

Example: "Tokyo is the capital and largest city of Japan. It is on the island of Honshu in the region of Kanto.
Tokyo is…"
- Sentence 1 has 9 words. That is fewer than 12, so we also take sentence 2 (21 words in total) and stop. We never
  take more than 2 sentences or 50 words.
- "in 1844. It was…" **is** a sentence end. A year before the dot is normal, so numbers are not treated as
  abbreviations.

## Key idea 3: related articles without embedding a query
Smart search turns the query into a vector with the neural network, which takes a few milliseconds. For related articles we don't need
that. The top article **already has a vector** in our index, so we use it directly as the query for HNSW:

1. Copy article *d*'s stored vector (int8 → float).
2. HNSW search for `5 + (articles on the page) + 1` nearest vectors. The extra ones cover the ones we will skip.
3. Skip *d* itself (its similarity with itself is 1, so it is always first) and every article already on the page.
4. Keep the first 5.

Each related title links to a Sift search for that title. That search usually shows that article's own card, so
you can walk from *Photosynthesis* → *Calvin cycle* → … through meaning.

## Time and space complexity
| Operation | Time | Space |
| --- | --- | --- |
| Card rule | O(q + t + Σ redirect lengths) for one article: tokenize the title and that article's redirects, compare hash sets | Redirect map: O(R), R = 115,575 redirects → +26.7 MB heap (measured) |
| Opening sentences | O(p), one pass over the first paragraph | O(p) |
| Related articles | One HNSW search: roughly O(efSearch × M × log N) dot products (efSearch = 400), the same cost as Smart mode's vector step | O(k + excluded) |

**Measured:**
- Card plus related: median about 1 ms, p95 about 1.7 ms per page-1 query.
- Smart p95 over HTTP went from about 13.2 ms to 13.8 ms (the limit is 100 ms).
- Keyword p95 went from about 7 ms to 8.3 ms.

Ranking is identical: the same ids, scores and order, and MRR 0.86758 / 0.95622 before and after.

## 5 interview questions

1. **How do you decide when to show the card, and how did you check the rule?**
   I compare sets of normalised terms: the query's terms must exactly equal the terms of the top article's title,
   or of a redirect to it. I tested several rules on the 50 main queries and counted "wrong cards" (card about an
   article that is not the expected answer). The exact rule gave 40 cards in Smart mode and 0 wrong. Looser rules
   gave more cards but also wrong ones, so I chose precision. I never looked at the held-out set.

2. **Why use the same tokenizer for the title and the query?**
   If the title were processed differently (no stemming, different stop words), "fibonacci numbers" would never
   equal "Fibonacci number". One shared function guarantees both sides are normalised identically. It is the same
   reason indexing and querying share one tokenizer.

3. **How do you find related articles without running the neural network?**
   Every article already has a stored, normalised vector. I use the top article's vector as the query to our
   HNSW index and skip the article itself and the ones already on the page. That costs one graph search (about
   1 ms) and no model inference.

4. **How did you make sure this didn't change ranking or slow search down?**
   The card is computed after ranking and only reads the top hit. I saved the API's top-100 results for 50 queries
   in both modes before and after: ids, scores and order identical, MRR identical. For speed I ran the same latency
   script before and after (cache off, 1,200 requests per mode, twice). Smart p95 moved from about 13.2 to 13.8 ms,
   far under the 100 ms limit.

5. **What are the weaknesses?**
   - Questions never get a card, even when the top result is right.
   - The sentence splitter is rule-based, so an unusual abbreviation can make a card one sentence too long.
   - Related articles are only as good as the embeddings: some are vague ("haiku" → Japanese-writing topics).
   - On a phone the card pushes the first result below the first screen.
   - The redirect map adds 27 MB of memory to a server that is close to a 1 GiB limit.
