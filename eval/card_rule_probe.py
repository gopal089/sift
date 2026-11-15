"""Read-only probe of candidate "confident top result" rules for the definition card, main 50 queries only
(never the held-out set). Needs the API on localhost:8080.
Usage: python3 eval/card_rule_probe.py eval/results/definition-card-probe.json"""
import json, urllib.request, urllib.parse, sys
B = "http://localhost:8080/api/search?"
def get(q, mode):
    with urllib.request.urlopen(B + urllib.parse.urlencode({"q": q, "mode": mode})) as r:
        return json.load(r)
terms_cache = {}
def terms(text):
    if text not in terms_cache:
        terms_cache[text] = get(text, "keyword")["stats"]["terms"]
    return terms_cache[text]
qs = json.load(open("eval/queries.json"))
rows = []
for q in qs:
    k = get(q["query"], "keyword"); s = get(q["query"], "smart")
    row = {"id": q["id"], "query": q["query"], "expected": q["expected"]}
    for name, d in (("keyword", k), ("smart", s)):
        r = d["results"]
        row[name + "_top"] = r[0]["title"] if r else None
        row[name + "_ok"] = bool(r) and r[0]["title"] == q["expected"]
        row[name + "_ratio"] = (r[0]["score"] / r[1]["score"]) if len(r) > 1 and r[1]["score"] > 0 else None
    row["qterms"] = terms(q["query"])
    top = row["smart_top"]
    row["smart_title_terms"] = terms(top) if top else []
    top = row["keyword_top"]
    row["keyword_title_terms"] = terms(top) if top else []
    rows.append(row)
json.dump(rows, open(sys.argv[1], "w"), indent=1)
