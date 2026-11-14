"""Builds loadtest/queries.json: 2,000 realistic, varied search queries for the k6 load test.

Mix (fixed seed, so the file is reproducible):
  700 article titles (some lower-cased)      e.g. "Mount Everest", "jazz"
  450 phrases of 2-4 words from article text e.g. "the river flows"
  300 questions built from titles            e.g. "what is jazz", "who was isaac newton"
  200 typos: one title word with two letters swapped or one letter dropped
  300 single words from article text
   50 main eval queries (eval/queries.json)
No held-out queries (eval/queries-holdout*.json are excluded on purpose).
The k6 script picks queries with a Zipf-like skew (a few are very popular), like real search traffic.

Usage: python3 loadtest/make_queries.py data/articles.jsonl
"""
import json
import random
import re
import sys

rng = random.Random(2026)
titles, phrases, words = [], [], []
with open(sys.argv[1], encoding="utf-8") as f:
    for line in f:
        a = json.loads(line)
        titles.append(a["title"])
        if rng.random() < 0.01:
            w = [x for x in re.split(r"\W+", a["text"]) if x]
            if len(w) > 10:
                s = rng.randint(0, len(w) - 5)
                phrases.append(" ".join(w[s:s + rng.randint(2, 4)]).lower())
                words.append(rng.choice(w).lower())

held_out = set()
for name in ("eval/queries-holdout.json", "eval/queries-holdout-v2.json"):
    try:
        held_out |= {q["query"].lower() for q in json.load(open(name, encoding="utf-8"))}
    except FileNotFoundError:
        pass


def typo(text):
    parts = text.split()
    i = rng.randrange(len(parts))
    w = parts[i]
    if len(w) < 4:
        return None
    j = rng.randrange(1, len(w) - 1)
    w = w[:j] + w[j + 1] + w[j] + w[j + 2:] if rng.random() < 0.5 else w[:j] + w[j + 1:]
    parts[i] = w
    return " ".join(parts).lower()


short_titles = [t for t in titles if len(t) <= 40 and "(" not in t]
pool = []
pool += [t if rng.random() < 0.5 else t.lower() for t in rng.sample(short_titles, 700)]
pool += rng.sample(phrases, 450)
pool += [rng.choice(["what is ", "who was ", "where is ", "history of "]) + t.lower()
         for t in rng.sample(short_titles, 300)]
typos = []
while len(typos) < 200:
    t = typo(rng.choice(short_titles))
    if t:
        typos.append(t)
pool += typos
pool += [w for w in rng.sample(words, 300)]
pool += [q["query"] for q in json.load(open("eval/queries.json", encoding="utf-8"))]
pool = [q for q in pool if q.strip() and q.lower() not in held_out]
rng.shuffle(pool)   # popularity rank is random, not by kind
json.dump(pool, open("loadtest/queries.json", "w", encoding="utf-8"), ensure_ascii=False, indent=0)
print(f"wrote {len(pool)} queries to loadtest/queries.json")
