"""Turn a Wikipedia XML dump (.xml.bz2) into clean JSON Lines files.

Outputs (all in the output directory):
  articles.jsonl         one article per line: {"id", "title", "text", "links"}
  articles-sample.jsonl  the first N articles (default 2,000) for quick tests
  redirects.jsonl        one redirect per line: {"from", "to"}  (used by PageRank later)
  parse-stats.json       counts and timing of this run

Usage:
  python -I parse_dump.py <dump.xml.bz2> <output_dir> [--workers N] [--sample-size N] [--limit N]
"""

from __future__ import annotations

import argparse
import bz2
import html
import json
import multiprocessing as mp
import os
import re
import sys
import time
import xml.etree.ElementTree as ET

import mwparserfromhell

# ---------------------------------------------------------------------------
# Title normalisation
# ---------------------------------------------------------------------------

_WHITESPACE = re.compile(r"\s+")


def normalize_title(raw: str) -> str:
    """Turn a link target into the canonical page title MediaWiki would use.

    "python_(programming language)#History" -> "Python (programming language)"
    Returns "" when nothing is left (e.g. a link to "#History" on the same page).
    """
    title = html.unescape(raw)
    title = title.split("#", 1)[0]          # drop the section part
    title = title.replace("_", " ")
    title = _WHITESPACE.sub(" ", title).strip()
    title = title.lstrip(":").strip()       # "[[:Foo]]" means plain link to Foo
    if not title:
        return ""
    # Simple Wikipedia is "first-letter" case: only the first letter is case-insensitive.
    return title[0].upper() + title[1:]


# ---------------------------------------------------------------------------
# Which links point at real articles?
# ---------------------------------------------------------------------------

# Aliases MediaWiki accepts but the <siteinfo> list does not show.
_NAMESPACE_ALIASES = {"image", "image talk", "wp", "wt", "project", "project talk", "t", "cat"}

# Links to sister projects, e.g. [[wikt:dog]] or [[commons:Cat]].
_INTERWIKI_PREFIXES = {
    "w", "wikipedia", "wikt", "wiktionary", "commons", "c", "meta", "m", "mw", "mediawiki",
    "q", "wikiquote", "b", "wikibooks", "s", "wikisource", "species", "wikispecies",
    "n", "wikinews", "v", "wikiversity", "voy", "wikivoyage", "d", "wikidata",
    "foundation", "wmf", "phab", "outreach", "incubator", "simple", "en",
}

# Language links look like [[de:Hund]] or [[zh-yue:...]]: a short lowercase code.
_LANGUAGE_CODE = re.compile(r"^[a-z]{2,3}(-[a-z]{2,8})*$")


def is_article_link(target: str, namespace_names: set[str]) -> bool:
    """True if a raw link target points at a normal article (namespace 0).

    `namespace_names` holds lowercase names like "file", "category", "template".
    Heuristic for language codes: a lowercase prefix like "de:" or "zh-yue:" is a
    language link. A real article title such as "Star Wars: Episode IV" is kept
    because its prefix contains a space and capitals.
    """
    target = target.strip()
    if target.startswith(":"):
        # [[:Category:Dogs]] links to the category page itself (not an article),
        # but [[:Dog]] is a normal link.
        target = target[1:]
    if ":" not in target:
        return True
    prefix = target.split(":", 1)[0].strip()
    lowered = prefix.lower().replace("_", " ")
    if lowered in namespace_names or lowered in _NAMESPACE_ALIASES:
        return False
    if lowered in _INTERWIKI_PREFIXES:
        return False
    if _LANGUAGE_CODE.match(prefix):
        return False
    return True


# ---------------------------------------------------------------------------
# Disambiguation detection
# ---------------------------------------------------------------------------

# Exact template names (lowercase) that mark a page as a disambiguation page.
# Chosen by counting the templates actually used in the 2026-11-01 dump.
# NOT included on purpose: "dablink" (a hatnote on normal articles) and
# "disambiguation needed" (an inline cleanup tag on normal articles).
DISAMBIGUATION_TEMPLATES = {
    "disambiguation", "disambig", "disamb", "dab", "hndis", "geodis",
    "human name disambiguation", "disambiguation cleanup", "year dab",
}


def is_disambiguation(title: str, wikicode) -> bool:
    if title.endswith("(disambiguation)"):
        return True
    for template in wikicode.filter_templates(recursive=False):
        name = _WHITESPACE.sub(" ", str(template.name).replace("_", " ")).strip().lower()
        if name.startswith("template:"):
            name = name[len("template:"):].strip()
        if name in DISAMBIGUATION_TEMPLATES:
            return True
    return False


# ---------------------------------------------------------------------------
# Wikitext -> plain text
# ---------------------------------------------------------------------------

_MAGIC_WORD = re.compile(r"__[A-Z]+__")            # __NOTOC__, __TOC__, ...
_SPACES = re.compile(r"[ \t ]+")
_SPACE_BEFORE_PUNCT = re.compile(r" +([,.;:!?)])")
_EMPTY_BRACKETS = re.compile(r"\(\s*[,;]?\s*\)")    # "( )" left after removing a template

# Safety net for markup the parser could not understand (usually broken source wikitext).
_LEFTOVER_REF = re.compile(r"<ref[^>]*/>|<ref[^>]*>.*?</ref>", re.IGNORECASE | re.DOTALL)
_LEFTOVER_TAG = re.compile(r"</?[a-zA-Z][^<>]*>")
_LEFTOVER_QUOTES = re.compile(r"'{2,}")
_LEFTOVER_HEADING = re.compile(r"^=+\s*(.*?)\s*=+$")
_TABLE_ROW_STARTS = ("{|", "|}", "|-", "|", "!")


def _remove_node(wikicode, node) -> None:
    try:
        wikicode.remove(node)
    except ValueError:
        pass  # already removed together with a parent node


# Tags whose contents are not readable prose. Tables are mostly numbers and layout;
# <ref> holds citations (mwparserfromhell 0.7 keeps their text, so we remove them ourselves).
_NON_PROSE_TAGS = {
    "table", "ref", "references", "gallery", "math", "chem", "timeline", "score",
    "imagemap", "graph", "syntaxhighlight", "source", "templatedata", "mapframe", "maplink",
}


def clean_wikitext(wikicode, namespace_names: set[str]) -> str:
    """Return readable plain text: one paragraph per line, no markup."""
    # 1. Remove tags that hold tables, footnotes, formulas, galleries, ...
    for tag in wikicode.filter_tags(matches=lambda n: str(n.tag).strip().lower() in _NON_PROSE_TAGS):
        _remove_node(wikicode, tag)
    # 2. Image, category and language links: their text is captions or nothing useful.
    for link in wikicode.filter_wikilinks():
        if not is_article_link(str(link.title), namespace_names):
            _remove_node(wikicode, link)
    # 3. strip_code drops templates, <ref> footnotes and comments, and turns
    #    [[target|shown text]] into "shown text" and '''bold''' into "bold".
    text = wikicode.strip_code(normalize=True, collapse=True)
    text = _MAGIC_WORD.sub("", text)
    text = _LEFTOVER_REF.sub("", text)
    text = _LEFTOVER_TAG.sub("", text)
    text = _LEFTOVER_QUOTES.sub("", text)

    lines = []
    for line in text.split("\n"):
        stripped = line.strip()
        if stripped.startswith(_TABLE_ROW_STARTS):
            continue                         # row of a table the parser did not recognise
        line = _LEFTOVER_HEADING.sub(r"\1", stripped)
        line = _EMPTY_BRACKETS.sub("", line)
        line = _SPACES.sub(" ", line)
        line = _SPACE_BEFORE_PUNCT.sub(r"\1", line)
        line = line.lstrip(" *#:;")         # list / indent markers at the start of a line
        line = line.strip()
        if line:
            lines.append(line)
    return "\n".join(lines)


def extract_links(wikicode, namespace_names: set[str]) -> list[str]:
    """Titles of linked articles, normalised, without duplicates, in first-seen order."""
    seen: dict[str, None] = {}
    for link in wikicode.filter_wikilinks():
        raw = str(link.title)
        if not is_article_link(raw, namespace_names):
            continue
        title = normalize_title(raw)
        if title:
            seen.setdefault(title, None)
    return list(seen)


# ---------------------------------------------------------------------------
# One page -> one record   (runs inside worker processes)
# ---------------------------------------------------------------------------

_namespace_names: set[str] = set()


def _init_worker(namespace_names: set[str]) -> None:
    global _namespace_names
    _namespace_names = namespace_names


def process_article(page: tuple[int, str, str]) -> tuple[str, dict | None]:
    """Clean one namespace-0, non-redirect page.

    Returns ("article", record), ("disambiguation", None) or ("empty", None).
    """
    page_id, title, wikitext = page
    wikicode = mwparserfromhell.parse(wikitext)
    if is_disambiguation(title, wikicode):
        return "disambiguation", None
    links = extract_links(wikicode, _namespace_names)   # before cleaning removes nodes
    text = clean_wikitext(wikicode, _namespace_names)
    if not text:
        return "empty", None
    return "article", {"id": page_id, "title": title, "text": text, "links": links}


# ---------------------------------------------------------------------------
# Streaming the XML   (runs in the main process)
# ---------------------------------------------------------------------------


def _local(tag: str) -> str:
    """'{http://www.mediawiki.org/xml/export-0.11/}page' -> 'page'"""
    return tag.rsplit("}", 1)[-1]


def iter_pages(xml_stream, on_siteinfo, on_redirect, counters, limit=None):
    """Yield (id, title, wikitext) for every namespace-0, non-redirect page.

    Uses iterparse so only one <page> is in memory at a time. Redirects are
    reported through `on_redirect(from_title, to_title)` instead of yielded.
    """
    context = ET.iterparse(xml_stream, events=("start", "end"))
    _, root = next(context)                  # the <mediawiki> element
    yielded = 0
    for event, elem in context:
        if event != "end":
            continue
        tag = _local(elem.tag)
        if tag == "siteinfo":
            on_siteinfo(elem)
            root.clear()
        elif tag == "page":
            counters["pages_seen"] += 1
            ns = id_text = title = redirect_to = text = None
            for child in elem:
                ctag = _local(child.tag)
                if ctag == "ns":
                    ns = child.text
                elif ctag == "id":
                    id_text = child.text
                elif ctag == "title":
                    title = child.text or ""
                elif ctag == "redirect":
                    redirect_to = child.get("title", "")
                elif ctag == "revision":
                    for rchild in child:
                        if _local(rchild.tag) == "text":
                            text = rchild.text or ""
            root.clear()                     # free the page we just read

            if ns != "0":
                counters["skipped_non_article_namespace"] += 1
                continue
            if redirect_to is not None:
                counters["redirects"] += 1
                target = normalize_title(redirect_to)
                if target:
                    on_redirect(title, target)
                continue
            yield int(id_text), title, text
            yielded += 1
            if limit is not None and yielded >= limit:
                return


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("dump", help="path to *-pages-articles.xml.bz2")
    parser.add_argument("out_dir", help="directory for the output files")
    parser.add_argument("--workers", type=int, default=os.cpu_count() or 1)
    parser.add_argument("--sample-size", type=int, default=2000)
    parser.add_argument("--limit", type=int, default=None, help="stop after N candidate pages (for testing)")
    args = parser.parse_args(argv)

    os.makedirs(args.out_dir, exist_ok=True)
    counters = {
        "pages_seen": 0, "skipped_non_article_namespace": 0, "redirects": 0,
        "skipped_disambiguation": 0, "skipped_empty_text": 0, "articles": 0,
    }
    namespace_names: set[str] = set()

    def on_siteinfo(elem):
        for ns in elem.iter():
            if _local(ns.tag) == "namespace" and ns.get("key") != "0" and ns.text:
                namespace_names.add(ns.text.lower())

    articles_path = os.path.join(args.out_dir, "articles.jsonl")
    sample_path = os.path.join(args.out_dir, "articles-sample.jsonl")
    redirects_path = os.path.join(args.out_dir, "redirects.jsonl")

    start = time.perf_counter()
    with bz2.open(args.dump, "rb") as xml_stream, \
            open(articles_path, "w", encoding="utf-8") as articles_out, \
            open(sample_path, "w", encoding="utf-8") as sample_out, \
            open(redirects_path, "w", encoding="utf-8") as redirects_out:

        def on_redirect(source, target):
            redirects_out.write(json.dumps({"from": source, "to": target}, ensure_ascii=False) + "\n")

        pages = iter_pages(xml_stream, on_siteinfo, on_redirect, counters, args.limit)
        # Read the first page now so <siteinfo> (namespace names) is known
        # before the worker processes start.
        first = next(pages, None)
        if first is None:
            print("No article pages found.", file=sys.stderr)
            return 1

        def all_pages():
            yield first
            yield from pages

        with mp.Pool(args.workers, initializer=_init_worker, initargs=(namespace_names,)) as pool:
            # imap keeps the original order, so output is identical on every run.
            for kind, record in pool.imap(process_article, all_pages(), chunksize=64):
                if kind == "disambiguation":
                    counters["skipped_disambiguation"] += 1
                    continue
                if kind == "empty":
                    counters["skipped_empty_text"] += 1
                    continue
                line = json.dumps(record, ensure_ascii=False) + "\n"
                articles_out.write(line)
                if counters["articles"] < args.sample_size:
                    sample_out.write(line)
                counters["articles"] += 1
                if counters["articles"] % 25000 == 0:
                    elapsed = time.perf_counter() - start
                    print(f"  {counters['articles']:,} articles written ({elapsed:,.0f} s)", flush=True)

    elapsed = time.perf_counter() - start
    stats = {
        "dump": os.path.basename(args.dump),
        "workers": args.workers,
        "seconds": round(elapsed, 1),
        **counters,
        "sample_articles": min(counters["articles"], args.sample_size),
    }
    with open(os.path.join(args.out_dir, "parse-stats.json"), "w", encoding="utf-8") as f:
        json.dump(stats, f, indent=2)

    print(json.dumps(stats, indent=2))
    print(f"Parse took {elapsed:,.1f} s ({elapsed / 60:,.1f} min) with {args.workers} workers.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
