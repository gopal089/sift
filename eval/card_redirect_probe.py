"""Tests the definition-card extension on the main 50 (never the held-out set): also show the card when the
query's terms exactly equal the terms of a REDIRECT title that points to the top result ("einstein" -> the
redirect "Einstein" -> Albert Einstein). Terms come from the running API (stats.terms), so they are made by the
same Java tokenizer as the real rule.

    python3 eval/card_redirect_probe.py eval/results/definition-card-probe.json eval/results/definition-card-redirects.json
"""
import json
import sys
import urllib.parse
import urllib.request

API = "http://localhost:8080/api/search?"
_terms = {}


def terms(text):
    if text not in _terms:
        with urllib.request.urlopen(API + urllib.parse.urlencode({"q": text, "mode": "keyword"})) as r:
            _terms[text] = json.load(r)["stats"]["terms"]
    return _terms[text]


def final_target(title, nxt, hops=10):
    while title in nxt and hops > 0:
        title, hops = nxt[title], hops - 1
    return title


rows = json.load(open(sys.argv[1]))
nxt = {}
for line in open("data/redirects.jsonl"):
    r = json.loads(line)
    nxt[r["from"]] = r["to"]
redirects_to = {}
for src in nxt:
    redirects_to.setdefault(final_target(src, nxt), []).append(src)

out = []
for row in rows:
    entry = {"id": row["id"], "query": row["query"], "expected": row["expected"]}
    for mode in ("smart", "keyword"):
        top = row[mode + "_top"]
        q = set(row["qterms"])
        exact = top is not None and q == set(row[mode + "_title_terms"])
        via = [s for s in redirects_to.get(top, []) if q and set(terms(s)) == q] if top and not exact else []
        entry[mode] = {"top": top, "correct": row[mode + "_ok"], "exact_title": exact, "via_redirects": via}
    out.append(entry)
json.dump(out, open(sys.argv[2], "w"), indent=1)

for mode in ("smart", "keyword"):
    exact = [e for e in out if e[mode]["exact_title"]]
    extra = [e for e in out if e[mode]["via_redirects"]]
    wrong = [e for e in exact + extra if not e[mode]["correct"]]
    print(f"{mode}: exact-title cards {len(exact)}, extra cards via redirects {len(extra)}, wrong cards {len(wrong)}")
    for e in extra:
        print(f"   + {e['query']!r} -> {e[mode]['top']!r} via {e[mode]['via_redirects'][:3]} correct={e[mode]['correct']}")
    for e in wrong:
        print(f"   WRONG {e['query']!r} -> {e[mode]['top']!r} (expected {e['expected']!r})")
