"""Write 200 check queries with their Python (sentence-transformers) vectors, for the Java embedding check.

The queries: the 50 main eval queries + 150 random article titles and short phrases (fixed seed).
No held-out queries. Output: data/vectors/check-queries.jsonl, one {"text", "vector"} per line.

Usage:  data-prep/.venv/bin/python data-prep/embed_check_queries.py data/articles.jsonl eval/queries.json
            [--model BAAI/bge-small-en-v1.5 --prefix "..." --out ...]
The Python vector is for prefix + text (the prefix is what Java adds from model-config.json).
"""

import argparse
import json
import random
from pathlib import Path

from sentence_transformers import SentenceTransformer

MODEL_NAME = "sentence-transformers/all-MiniLM-L6-v2"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("articles")
    ap.add_argument("queries")
    ap.add_argument("--out", default="data/vectors/check-queries.jsonl")
    ap.add_argument("--model", default=MODEL_NAME)
    ap.add_argument("--prefix", default="")
    args = ap.parse_args()
    rng = random.Random(7)

    texts = [q["query"] for q in json.load(open(args.queries, encoding="utf-8"))]
    titles, phrases = [], []
    with open(args.articles, encoding="utf-8") as f:
        for line in f:
            a = json.loads(line)
            titles.append(a["title"])
            if rng.random() < 0.01:
                words = a["text"].split()
                if len(words) > 10:
                    start = rng.randint(0, len(words) - 10)
                    phrases.append(" ".join(words[start:start + rng.randint(2, 8)]))
    texts += rng.sample(titles, 75) + rng.sample(phrases, 75)

    st = SentenceTransformer(args.model, device="cpu")
    vectors = st.encode([args.prefix + t for t in texts], normalize_embeddings=True, convert_to_numpy=True, batch_size=64)
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        for t, v in zip(texts, vectors):
            f.write(json.dumps({"text": t, "vector": [float(x) for x in v]}, ensure_ascii=False) + "\n")
    print(f"wrote {len(texts)} queries to {args.out}")


if __name__ == "__main__":
    main()
