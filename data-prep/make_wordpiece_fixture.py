"""Make the test fixture that proves the Java WordPiece tokenizer gives exactly the model's word-piece ids.

Writes 1,000 strings and the ids the model's own tokenizer gives them (with [CLS]/[SEP], cut at 256) to
engine/src/test/resources/wordpiece/cases.jsonl, and copies vocab.txt next to it.
The mix (fixed seed): article titles, sentences, numbers, accented text, heavy punctuation, and hand-made
hard cases (emoji, Chinese/Japanese/Korean, control characters, odd spaces, very long words, > 256 pieces).

Usage:  data-prep/.venv/bin/python data-prep/make_wordpiece_fixture.py data/articles.jsonl
        [--model BAAI/bge-small-en-v1.5 --cases cases-bge.jsonl]
The same 1,000 strings are used for every model; only the expected ids (and the length limit) change.
"""

import argparse
import json
import random
import shutil
from pathlib import Path

from sentence_transformers import SentenceTransformer

MODEL_NAME = "sentence-transformers/all-MiniLM-L6-v2"
OUT = Path("engine/src/test/resources/wordpiece")

HARD_CASES = [
    "", " ", "\t\n\r", "a", "A", "I", "x y z",
    "Café Müller", "naïve façade", "São Paulo", "Ångström", "Đorđe", "Łódź", "Zürich", "Øresund",
    "İstanbul", "ısık", "Straße", "ǅemal", "ﬁnance ﬂow", "Ａｂｃ１２３", "½ ¼ ³ ²", "x²+y²=z²",
    "日本の首都は東京です", "中华人民共和国", "한국어 텍스트", "ภาษาไทย", "Ελληνικά ΟΔΥΣΣΕΥΣ", "Москва, Россия",
    "العربية", "עברית", "हिन्दी भाषा", "தமிழ்", "Tiếng Việt có dấu",
    "emoji 😀 test 👍🏽 family 👨‍👩‍👧", "🙂", "flag 🇺🇸", "❤️ love",
    "zero​width", "non breaking", "thin space", "line sep", "ideographic　space",
    "soft­hyphen", "bom﻿here", "nul\x00char", "bell\x07char", "vt\x0bff\x0c", "del\x7fchar",
    "repl�char", "nel\x85char", "privateuse", "rtl‏mark",
    "can't won't it's", "U.S.A.", "e-mail", "C++ & C#", "f(x) = 2x + 1", "50% off!!!", "$100,000.00",
    "#hashtag @user", "path/to/file.txt", "http://example.com/a?b=c&d=e", "a--b---c", "...", "?!?!",
    "«quoted» “smart” ‘single’", "em—dash en–dash", "1,234,567", "3.14159", "1e-9", "2026-11-11",
    "12:30pm", "No. 5", "#1", "100°C", "€50 £20 ¥1000", "~tilde^caret`back|pipe",
    "supercalifragilisticexpialidocious", "pneumonoultramicroscopicsilicovolcanoconiosis",
    "x" * 99, "x" * 100, "x" * 101, "ab" * 60, "unaffable unbelievable",
    "word " * 300,
    "The quick brown fox jumps over the lazy dog. " * 40,
]

PUNCT = list("!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~") + ["—", "–", "…", "¡", "¿", "«", "»", "“", "”", "§", "¶", "•"]


def sentences(text):
    parts = [p.strip() for p in text.replace("\n", " ").split(". ") if p.strip()]
    return parts


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("articles")
    ap.add_argument("--model", default=MODEL_NAME)
    ap.add_argument("--cases", default="cases.jsonl")
    args = ap.parse_args()
    rng = random.Random(42)

    titles, sents, accented = [], [], []
    with open(args.articles, encoding="utf-8") as f:
        for line in f:
            a = json.loads(line)
            titles.append(a["title"])
            if rng.random() < 0.02:
                for s in sentences(a["text"])[:3]:
                    sents.append(s)
                    if any(ord(c) > 127 for c in s):
                        accented.append(s)
            if any(ord(c) > 127 for c in a["title"]):
                accented.append(a["title"])

    cases = list(HARD_CASES)
    cases += rng.sample(titles, 300)
    cases += [" ".join(s.split()[:rng.randint(3, 60)]) for s in rng.sample(sents, 300)]
    cases += rng.sample(accented, 150)
    for _ in range(100):   # numbers, dates, units
        parts = [str(rng.randint(0, 10 ** rng.randint(1, 9))), f"{rng.uniform(0, 1000):.{rng.randint(1, 4)}f}",
                 f"{rng.randint(1, 31)}/{rng.randint(1, 12)}/{rng.randint(1000, 2100)}",
                 f"{rng.randint(1, 999):,}", f"{rng.randint(1, 99)}%", f"{rng.randint(1, 9)}th century"]
        cases.append(" ".join(rng.sample(parts, rng.randint(1, len(parts)))))
    words = [w for s in rng.sample(sents, 200) for w in s.split()]
    while len(cases) < 1000:   # heavy punctuation glued to words
        cases.append("".join(rng.choice([rng.choice(words), rng.choice(PUNCT), " "])
                             for _ in range(rng.randint(2, 15))))
    cases = cases[:1000]

    st = SentenceTransformer(args.model, device="cpu")
    tok = st.tokenizer
    max_len = st.max_seq_length
    OUT.mkdir(parents=True, exist_ok=True)
    with open(OUT / args.cases, "w", encoding="utf-8") as f:
        for text in cases:
            ids = tok(text, truncation=True, max_length=max_len)["input_ids"]
            f.write(json.dumps({"text": text, "ids": ids, "max_length": max_len}, ensure_ascii=False) + "\n")
    if args.model == MODEL_NAME:
        shutil.copy("data/model/vocab.txt", OUT / "vocab.txt")
    else:   # other models must share the vocabulary, or the Java test would need a second vocab file
        vocab = [w for w, _ in sorted(tok.get_vocab().items(), key=lambda kv: kv[1])]
        assert vocab == (OUT / "vocab.txt").read_text(encoding="utf-8").splitlines(), "vocabulary differs"
    print(f"wrote {len(cases)} cases to {OUT / args.cases} (max_length {max_len})")


if __name__ == "__main__":
    main()
