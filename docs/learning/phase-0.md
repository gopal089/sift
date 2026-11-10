# Phase 0 — Setup and data preparation

## What we built, in simple words

Before we can search anything, we need **clean documents**. Wikipedia gives us one giant
compressed file (a "dump") with every page written in *wiki markup*, a special formatting
language full of `[[links]]`, `{{templates}}` and `'''bold'''` marks.

In this phase we:
1. Set up the project: folders, a Gradle build for the Java code, and a Python environment.
2. Downloaded one fixed, dated dump (2026-11-01) and checked its fingerprint (SHA-1) to prove it
   downloaded correctly.
3. Wrote a Python script that reads the dump **page by page**. It keeps only real articles,
   removes the markup, and writes one clean JSON line per article.

Result: **280,748 articles** in `data/articles.jsonl`, parsed in **123.8 seconds**.

## Each new file and what it is for

| File | Purpose |
| --- | --- |
| `settings.gradle.kts` | Lists the Java modules (`engine`, `api`) and loads the plugin that auto-downloads Java 21. |
| `build.gradle.kts` | Settings shared by all modules: Java 21 toolchain, JUnit 5. |
| `engine/build.gradle.kts` | Makes `engine` a Java *library* (code other modules use). |
| `api/build.gradle.kts` | The future web server; depends on `engine`. Spring Boot comes in Phase 2. |
| `engine/src/test/.../BuildSetupTest.java` | Smoke test: proves JUnit runs and that tests really run on Java 21. |
| `gradlew`, `gradle/wrapper/*` | The **Gradle wrapper**: a script that downloads one fixed Gradle version, so every machine (and CI) builds the same way. |
| `.gitignore` | Keeps big or generated files (`data/`, build output, `.venv`) out of Git. |
| `data-prep/requirements.txt` | The one Python library we use: `mwparserfromhell==0.7.2`. |
| `data-prep/parse_dump.py` | The dump → JSON Lines converter. |
| `data-prep/test_parse_dump.py` | 22 unit tests for the converter. |
| `PROGRESS.md` | Phase status, measured numbers, open issues, notes for later. |

**Output files** (in `data/`, never committed):

| File | One line looks like |
| --- | --- |
| `articles.jsonl` | `{"id": 3518, "title": "Photosynthesis", "text": "Photosynthesis is a process…", "links": ["Carbon dioxide", …]}` |
| `articles-sample.jsonl` | Same format, only the first 2,000 articles, for fast tests in later phases |
| `redirects.jsonl` | `{"from": "Albigensian", "to": "Catharism"}` |
| `parse-stats.json` | Counts and timing of the run |

"JSON Lines" (`.jsonl`) means one complete JSON object per line. You can read it one line at a
time without loading the whole file. That matters when the file is 397 MB.

## The key ideas, with small worked examples

### 1. Streaming XML parsing (`iterparse`)

The decompressed dump is several gigabytes of XML. Loading it all into memory as one tree
would need many GB of RAM. Instead, `ElementTree.iterparse` reads the file **like a conveyor
belt** and tells us each time an element *ends*:

```xml
<mediawiki>
  <page><title>Dog</title><ns>0</ns><id>10</id> … </page>     ← "end page" event: process Dog, then clear()
  <page><title>Doggy</title><ns>0</ns><redirect title="Dog"/> … </page>  ← record redirect Doggy→Dog, clear()
  <page><title>Category:Animals</title><ns>14</ns> … </page>  ← ns 14 is not an article: skip, clear()
</mediawiki>
```

After each page we call `root.clear()`, which throws that page away. So at any moment only
**one page** is in memory, no matter how big the dump is.

### 2. Deciding what is an article

| Check | Rule | Example |
| --- | --- | --- |
| Namespace | keep only `<ns>0</ns>` (normal articles) | `Category:Animals` (ns 14) skipped |
| Redirect | page has `<redirect title="…"/>` → save to `redirects.jsonl`, don't keep as article | `Doggy` → `Dog` |
| Disambiguation | uses an exact template like `{{disambiguation}}`, `{{disambig}}`, `{{hndis}}`, `{{geodis}}`, `{{dab}}`, or title ends in `(disambiguation)` | "Mercury can mean: …" skipped |

We chose the template list by **counting real templates in the dump**. `{{dablink}}` looks
similar but is just a "see also" note on normal articles, so matching "contains *dab*" would
have thrown away good articles.

### 3. Cleaning wiki markup

```
Input:  {{Infobox animal|name=Dog}}
        '''Dogs''' are [[mammal]]s.<ref>Smith 2001</ref> They are [[Canis|canines]].
        [[File:Dog.jpg|thumb|A dog]] [[Category:Animals]]

Steps:  1. remove non-prose tags: <ref>…</ref>, tables, <gallery>, <math> …
        2. remove File:, Category:, and language links (e.g. [[de:Hund]])
        3. strip_code(): drop templates, [[Canis|canines]] → "canines", '''Dogs''' → "Dogs"
        4. safety net for broken markup, tidy spaces, one paragraph per line

Output: Dogs are mammals. They are canines.
```

Our tests caught a real surprise here: this library version **keeps the text inside
`<ref>` footnotes**. Without step 1, "Smith 2001" would have ended up in the article text
and in search results.

### 4. Normalising link titles

Two links written differently can point to the same page. MediaWiki treats them as equal
after a few rules, so we apply the same rules:

```
"python_(programming language)#History"
  → drop "#History"                 "python_(programming language)"
  → "_" becomes " "                 "python (programming language)"
  → uppercase the first letter only "Python (programming language)"
```

Only the *first* letter, because Simple Wikipedia is "first-letter" case: `iPod` and `IPod` are the
same page, but `Python` and `PYTHON` are different. We need this so that PageRank in Phase 4
can match a link to the right article.

### 5. Parallel work with deterministic output

Parsing wikitext is the slow part, so we use a **pool of 10 worker processes**. The main
process reads pages from the XML and hands them to the workers. We use `pool.imap`, which
returns results **in the same order the pages went in** (unlike `imap_unordered`). So
`articles.jsonl` is identical every time we run the script, and article IDs and line order
are stable for later phases.

## Time and space complexity

Let **B** = bytes in the dump, **P** = number of pages, **L** = links in one page, **W** = workers.

| Operation | Time | Extra memory |
| --- | --- | --- |
| Stream + decompress the dump | O(B) | O(size of largest page) |
| Clean one page | O(page length) | O(page length) |
| Extract + deduplicate links of a page | O(L) (dictionary keeps order and removes duplicates) | O(L) |
| Whole parse | O(B) total, spread across W workers → measured 123.8 s with W = 10 | O(W × largest page) + chunk buffers. **Not** O(B). |
| Write output | O(output size) = 397 MB | O(one line) |

## Measured results (from `data/parse-stats.json`)

| | |
| --- | --- |
| Pages in dump | 563,515 |
| Articles written | 280,748 |
| Redirects saved | 115,575 |
| Disambiguation pages skipped | 5,306 |
| Parse time | 123.8 s (Apple M5, 10 workers) |
| Articles with any leftover markup | 3,496 (1.25%) |
| Links to existing articles / redirects / missing pages | 59.4% / 8.5% / 32.1% |

## 5 interview questions (with short model answers)

**1. The dump is several GB of XML. How did you process it without running out of memory?**
I streamed it with `iterparse`, which emits an event when each `<page>` element closes. I
processed that page and then cleared it from the tree, so memory holds about one page at a
time: O(largest page), not O(file size). The `.bz2` file is also decompressed as a stream,
never fully on disk.

**2. Why did you skip redirects but still save them to a file?**
A redirect like "USA → United States" isn't a real document, so indexing it would create
duplicate results. But other articles *link* to redirect titles. For PageRank, a link to "USA"
must count as a link to "United States", so I keep a `from → to` map. In the measured data,
8.5% of all links point to a redirect.

**3. 32% of links point to pages that don't exist. Isn't that a bug?**
I checked before assuming. The unresolved titles are real pages on English Wikipedia that
Simple Wikipedia doesn't have ("red links"), plus links to disambiguation pages I skipped on
purpose. Only 0.3% would match if I ignored case, so my normalisation isn't the cause.
PageRank will just drop links whose target isn't an article.

**4. You used 10 processes. How did you keep the output the same on every run?**
I used `Pool.imap`, which returns results in input order even though workers finish at
different times. Deterministic output means line numbers, and later document IDs, don't change
between runs, so measurements can be repeated.

**5. How do you know the cleaned text is actually clean?**
Three ways. (a) 22 unit tests with hand-written wikitext and exact expected output; they found
two real bugs (footnote text leaking in, and a double space). (b) I measured leftover markup on
the full output: 1.25% of articles contain any markup character, and some of those are legitimate.
(c) I read sample articles by eye. I also pinned the dump date and verified its SHA-1, so anyone
can reproduce the same 280,748 articles.

## How to run it yourself

```bash
cd data-prep
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
.venv/bin/python -m unittest -v                     # 22 tests
cd ..
data-prep/.venv/bin/python -I data-prep/parse_dump.py data/dump/simplewiki-20261101-pages-articles.xml.bz2 data
./gradlew build                                      # Java build + smoke test
```
