"""Export all-MiniLM-L6-v2 to ONNX for the Java server, with its WordPiece vocabulary.

The ONNX file contains only the transformer: word-piece ids in, one 384-number vector per word piece out.
The two steps that turn those into ONE sentence vector (mean pooling and L2 normalising) are done by our
own code, in Python here (embed_articles.py) and in Java (QueryEmbedder). This script checks that
ONNX + our pooling gives the same vector as sentence-transformers itself.

Usage:  data-prep/.venv/bin/python data-prep/export_model.py [--out data/model]
            [--model sentence-transformers/all-MiniLM-L6-v2] [--query-prefix "..."]

Pooling (how token vectors become one sentence vector) is read from the model: MiniLM uses the mean of all
word pieces, bge uses the first one ([CLS]). Both are then scaled to length 1.
"""

import argparse
import json
import os
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch
from sentence_transformers import SentenceTransformer

MODEL_NAME = "sentence-transformers/all-MiniLM-L6-v2"

CHECK_SENTENCES = [
    "how do plants make food",
    "Photosynthesis. Photosynthesis is the process by which plants use light to make sugar.",
    "world war 2",
    "Café Müller in São Paulo, 1999 — naïve façade!",
    "who was the first person on the moon",
    "日本の首都は東京です",
    "x",
]


class TokenVectors(torch.nn.Module):
    """Wraps the BERT model so its ONNX output is just the token vectors (last hidden state)."""

    def __init__(self, bert):
        super().__init__()
        self.bert = bert

    def forward(self, input_ids, attention_mask, token_type_ids):
        return self.bert(input_ids=input_ids, attention_mask=attention_mask,
                         token_type_ids=token_type_ids).last_hidden_state


def pool_normalise(token_vectors, attention_mask, pooling):
    """'mean': average the vectors of the real word pieces (mask = 1); 'cls': take the first ([CLS]) vector.
    Then scale the result to length 1."""
    if pooling == "cls":
        pooled = token_vectors[:, 0, :]
    else:
        mask = attention_mask[..., None].astype(np.float32)
        pooled = (token_vectors * mask).sum(axis=1) / np.clip(mask.sum(axis=1), 1e-9, None)
    return pooled / np.clip(np.linalg.norm(pooled, axis=1, keepdims=True), 1e-12, None)


def model_revision(model_name):
    """The exact model version (git commit on the Hugging Face hub) that the local cache holds."""
    from huggingface_hub import constants
    ref = Path(constants.HF_HUB_CACHE) / ("models--" + model_name.replace("/", "--")) / "refs" / "main"
    return ref.read_text().strip() if ref.exists() else "unknown"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="data/model")
    ap.add_argument("--model", default=MODEL_NAME)
    ap.add_argument("--query-prefix", default="", help="text put in front of every query (bge: an instruction)")
    args = ap.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    st = SentenceTransformer(args.model, device="cpu")
    tok = st.tokenizer
    bert = st[0].auto_model.eval()
    max_len = st.max_seq_length
    pooling = st[1].get_config_dict()["pooling_mode"]
    assert pooling in ("mean", "cls"), f"unsupported pooling {pooling}"

    # --- vocabulary: one word piece per line, line number = id
    vocab = tok.get_vocab()
    by_id = sorted(vocab.items(), key=lambda kv: kv[1])
    assert [i for _, i in by_id] == list(range(len(by_id))), "vocabulary ids are not 0..n-1"
    (out / "vocab.txt").write_text("".join(w + "\n" for w, _ in by_id), encoding="utf-8")

    # --- ONNX export (the classic TorchScript-based exporter, so no extra libraries are needed)
    sample = tok(["hello world"], return_tensors="pt")
    onnx_path = out / "model.onnx"
    torch.onnx.export(
        TokenVectors(bert),
        (sample["input_ids"], sample["attention_mask"], sample["token_type_ids"]),
        str(onnx_path),
        input_names=["input_ids", "attention_mask", "token_type_ids"],
        output_names=["token_vectors"],
        dynamic_axes={name: {0: "batch", 1: "length"} for name in
                      ["input_ids", "attention_mask", "token_type_ids", "token_vectors"]},
        opset_version=17,
        dynamo=False,
    )

    # --- check: ONNX + our pooling == sentence-transformers
    session = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    enc = tok(CHECK_SENTENCES, padding=True, truncation=True, max_length=max_len, return_tensors="np")
    feeds = {k: enc[k].astype(np.int64) for k in ["input_ids", "attention_mask", "token_type_ids"]}
    ours = pool_normalise(session.run(None, feeds)[0], feeds["attention_mask"], pooling)
    ref = st.encode(CHECK_SENTENCES, normalize_embeddings=True, convert_to_numpy=True)
    cos = (ours * ref).sum(axis=1)
    for s, c in zip(CHECK_SENTENCES, cos):
        print(f"  cosine {c:.7f}  {s!r}")
    assert cos.min() >= 0.9999, f"ONNX export does not match sentence-transformers (min cosine {cos.min()})"

    revision = model_revision(args.model)
    config = {
        "model": args.model,
        "revision": revision,
        "dimensions": int(ref.shape[1]),
        "max_length": max_len,
        "lowercase": True,
        "strip_accents": True,
        "pooling": pooling,
        "query_prefix": args.query_prefix,
        "onnx_inputs": ["input_ids", "attention_mask", "token_type_ids"],
        "onnx_output": "token_vectors",
        "vocab_size": len(by_id),
        "special_tokens": {t: vocab[t] for t in ["[PAD]", "[UNK]", "[CLS]", "[SEP]"]},
    }
    (out / "model-config.json").write_text(json.dumps(config, indent=2) + "\n")
    print(f"saved {onnx_path} ({os.path.getsize(onnx_path) / 1e6:.1f} MB), vocab.txt ({len(by_id)} pieces),"
          f" model-config.json; min cosine vs sentence-transformers {cos.min():.7f}")


if __name__ == "__main__":
    main()
