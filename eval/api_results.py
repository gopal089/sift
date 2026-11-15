"""Saves what the running API returns for every query in a query file, and its MRR, so two runs can be compared.

Used to prove that a change in snippets or highlighting does not change ranking: run it before and after,
then compare the two files with --compare. Ranking is the same if every query returns the same article ids
with the same scores in the same order, in both modes.

    python3 eval/api_results.py save eval/results/x-before.json            # server on localhost:8080
    python3 eval/api_results.py compare eval/results/x-before.json eval/results/x-after.json

Snippets are stored as a short hash (enough to see if one changed) and a count of highlighted words.
Only the first 10 pages (top 100) are fetched, like the Java evaluation, which ranks the top 100.
"""
import hashlib
import json
import sys
import urllib.parse
import urllib.request

BASE = "http://localhost:8080/api/search"
QUERIES = "eval/queries.json"
PAGES = 10


def fetch(query, mode, page):
    url = f"{BASE}?{urllib.parse.urlencode({'q': query, 'mode': mode, 'page': page})}"
    with urllib.request.urlopen(url) as response:
        return json.load(response)


def save(out_path):
    queries = json.load(open(QUERIES))
    out = {"queries": QUERIES, "modes": {}}
    for mode in ["keyword", "smart"]:
        rows = []
        for q in queries:
            results = []
            for page in range(1, PAGES + 1):
                body = fetch(q["query"], mode, page)
                results += [{"id": r["id"], "title": r["title"], "score": r["score"],
                             "snippet": hashlib.sha1(r["snippet"].encode()).hexdigest()[:12],
                             "marks": len(r["highlights"])} for r in body["results"]]
                if not body["hasMore"]:
                    break
            titles = [r["title"] for r in results]
            rank = titles.index(q["expected"]) + 1 if q["expected"] in titles else None
            rows.append({"query": q["query"], "expected": q["expected"], "rank": rank, "results": results})
        mrr = sum(1 / r["rank"] for r in rows if r["rank"]) / len(rows)
        out["modes"][mode] = {"mrr": mrr, "rows": rows}
        print(f"{mode}: MRR {mrr:.5f} over {len(rows)} queries")
    json.dump(out, open(out_path, "w"), separators=(",", ":"))


def compare(before_path, after_path):
    before, after = json.load(open(before_path)), json.load(open(after_path))
    same = True
    for mode in ["keyword", "smart"]:
        b, a = before["modes"][mode], after["modes"][mode]
        ranking_diff = sum(1 for x, y in zip(b["rows"], a["rows"])
                           if [(r["id"], r["score"]) for r in x["results"]] != [(r["id"], r["score"]) for r in y["results"]])
        snippet_diff = sum(1 for x, y in zip(b["rows"], a["rows"]) for r, s in zip(x["results"], y["results"])
                           if r["snippet"] != s["snippet"])
        marks_before = sum(r["marks"] for x in b["rows"] for r in x["results"])
        marks_after = sum(r["marks"] for x in a["rows"] for r in x["results"])
        print(f"{mode}: MRR {b['mrr']:.5f} -> {a['mrr']:.5f} | queries with different ids/scores/order: {ranking_diff}"
              f" | results with a different snippet text: {snippet_diff} | highlights {marks_before} -> {marks_after}")
        same = same and ranking_diff == 0 and b["mrr"] == a["mrr"]
    print("RANKING IDENTICAL" if same else "RANKING CHANGED")
    return 0 if same else 1


if __name__ == "__main__":
    if sys.argv[1] == "save":
        save(sys.argv[2])
    else:
        sys.exit(compare(sys.argv[2], sys.argv[3]))
