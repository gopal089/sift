"""Lists the definition card (and its related articles) the running API gives for each main-50 query, in both
modes, and checks it against the expected answer. Main 50 only; never the held-out set.

    python3 eval/card_check.py eval/results/definition-card-main50.json
"""
import json
import sys
import urllib.parse
import urllib.request

API = "http://localhost:8080/api/search?"
rows = []
for q in json.load(open("eval/queries.json")):
    row = {"id": q["id"], "query": q["query"], "expected": q["expected"]}
    for mode in ("smart", "keyword"):
        with urllib.request.urlopen(API + urllib.parse.urlencode({"q": q["query"], "mode": mode})) as r:
            d = json.load(r)["definition"]
        row[mode] = None if d is None else {
            "title": d["title"], "matchedBy": d["matchedBy"], "correct": d["title"] == q["expected"],
            "text": d["text"], "related": [a["title"] for a in d["related"]]}
    rows.append(row)
json.dump(rows, open(sys.argv[1], "w"), indent=1, ensure_ascii=False)
for mode in ("smart", "keyword"):
    cards = [r[mode] for r in rows if r[mode]]
    print(f"{mode}: cards {len(cards)}/50 (title {sum(c['matchedBy'] == 'title' for c in cards)}, "
          f"redirect {sum(c['matchedBy'] == 'redirect' for c in cards)}), wrong {sum(not c['correct'] for c in cards)}, "
          f"with 5 related {sum(len(c['related']) == 5 for c in cards)}")
