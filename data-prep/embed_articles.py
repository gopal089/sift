"""Embed every article ("title. first 200 words") with all-MiniLM-L6-v2 and save the vectors for Java.

Output (in --out, default data/vectors):
  vectors-f32.bin    row d = article number d in articles.jsonl (= doc id d in the index),
                     384 little-endian float32 numbers, already scaled to length 1
  vectors-meta.json  count, dimensions, model + revision, text recipe, sha256 of the titles in order,
                     time taken. Java checks count and title hash before using the file.

Usage:  data-prep/.venv/bin/python data-prep/embed_articles.py data/articles.jsonl [--batch 128]
"""

import argparse
import hashlib
import json
import sys
import time
from pathlib import Path

import numpy as np
import torch
from sentence_transformers import SentenceTransformer

MODEL_NAME = "sentence-transformers/all-MiniLM-L6-v2"
WORDS = 200
CHUNK = 8192   # articles per encode() call: lets us print progress and write as we go


def doc_text(title, text, words=WORDS):
    """The text we embed: the title, then the first `words` whitespace-separated words of the article."""
    return title + ". " + " ".join(text.split()[:words])


def read_articles(path):
    with open(path, encoding="utf-8") as f:
        for line in f:
            a = json.loads(line)
            yield a["title"], a["text"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("articles")
    ap.add_argument("--out", default="data/vectors")
    ap.add_argument("--batch", type=int, default=128)
    ap.add_argument("--limit", type=int, default=0, help="only the first N articles (for a quick test)")
    args = ap.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    device = "mps" if torch.backends.mps.is_available() else "cpu"
    st = SentenceTransformer(MODEL_NAME, device=device)
    dims = st.get_sentence_embedding_dimension()
    print(f"device={device} max_seq_length={st.max_seq_length} dims={dims}", flush=True)

    titles_hash = hashlib.sha256()
    count = 0
    start = time.time()
    tmp = out / "vectors-f32.bin.part"
    with open(tmp, "wb") as f:
        chunk = []

        def flush():
            nonlocal count
            vecs = st.encode(chunk, batch_size=args.batch, normalize_embeddings=True,
                             convert_to_numpy=True, show_progress_bar=False).astype("<f4")
            f.write(vecs.tobytes())
            count += len(chunk)
            rate = count / (time.time() - start)
            print(f"  {count:,} articles  {rate:,.0f}/s", flush=True)
            chunk.clear()

        for title, text in read_articles(args.articles):
            if count + len(chunk) > 0:
                titles_hash.update(b"\n")
            titles_hash.update(title.encode("utf-8"))
            chunk.append(doc_text(title, text))
            if len(chunk) == CHUNK:
                flush()
            if args.limit and count + len(chunk) >= args.limit:
                break
        if chunk:
            flush()
    seconds = time.time() - start
    final = out / "vectors-f32.bin"
    tmp.replace(final)

    revision_ref = Path(__import__("huggingface_hub").constants.HF_HUB_CACHE) / (
        "models--" + MODEL_NAME.replace("/", "--")) / "refs" / "main"
    meta = {
        "count": count,
        "dimensions": dims,
        "dtype": "float32 little-endian, L2-normalised",
        "model": MODEL_NAME,
        "revision": revision_ref.read_text().strip() if revision_ref.exists() else "unknown",
        "text": f"title + '. ' + first {WORDS} whitespace words; model cuts at {st.max_seq_length} word pieces",
        "titles_sha256": titles_hash.hexdigest(),
        "device": device,
        "batch": args.batch,
        "seconds": round(seconds, 1),
        "bytes": final.stat().st_size,
    }
    (out / "vectors-meta.json").write_text(json.dumps(meta, indent=2) + "\n")
    print(json.dumps(meta, indent=2))
    assert final.stat().st_size == count * dims * 4


if __name__ == "__main__":
    sys.exit(main())
