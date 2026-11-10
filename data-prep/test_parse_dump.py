"""Unit tests for parse_dump.py.   Run:  cd data-prep && .venv/bin/python -m unittest -v"""

import io
import unittest

import mwparserfromhell

import parse_dump as pd

NAMESPACES = {"file", "category", "template", "wikipedia", "help", "user", "talk", "module"}


def parse(text):
    return mwparserfromhell.parse(text)


class NormalizeTitleTest(unittest.TestCase):
    def test_capitalises_first_letter_only(self):
        self.assertEqual(pd.normalize_title("python (programming language)"),
                         "Python (programming language)")

    def test_underscores_section_and_spaces(self):
        self.assertEqual(pd.normalize_title("  World_War_II#Causes "), "World War II")
        self.assertEqual(pd.normalize_title("New   York"), "New York")

    def test_leading_colon_and_html_entities(self):
        self.assertEqual(pd.normalize_title(":Dog"), "Dog")
        self.assertEqual(pd.normalize_title("AT&amp;T"), "AT&T")

    def test_same_page_section_link_is_empty(self):
        self.assertEqual(pd.normalize_title("#History"), "")


class IsArticleLinkTest(unittest.TestCase):
    def test_normal_titles_are_articles(self):
        for title in ["Dog", "Star Wars: Episode IV", "Mission: Impossible", ":Dog"]:
            self.assertTrue(pd.is_article_link(title, NAMESPACES), title)

    def test_namespaces_and_aliases_are_not_articles(self):
        for title in ["File:Cat.jpg", "Image:Cat.jpg", "Category:Dogs", ":Category:Dogs",
                      "template:Infobox", "Wikipedia:About"]:
            self.assertFalse(pd.is_article_link(title, NAMESPACES), title)

    def test_interwiki_and_language_links_are_not_articles(self):
        for title in ["wikt:dog", "commons:Cat", "de:Hund", "zh-yue:狗", "en:Dog"]:
            self.assertFalse(pd.is_article_link(title, NAMESPACES), title)


class DisambiguationTest(unittest.TestCase):
    def test_detects_disambiguation_templates(self):
        for text in ["'''Mercury''' can mean:\n{{disambiguation}}", "{{Disambig}}", "{{hndis|Smith}}",
                     "{{ geodis }}", "{{Template:Dab}}"]:
            self.assertTrue(pd.is_disambiguation("Mercury", parse(text)), text)

    def test_detects_disambiguation_title(self):
        self.assertTrue(pd.is_disambiguation("Mercury (disambiguation)", parse("text")))

    def test_hatnotes_and_cleanup_tags_are_not_disambiguation(self):
        for text in ["{{dablink|For the planet, see [[Mercury (planet)]].}} Mercury is a metal.",
                     "Paris{{disambiguation needed}} is a city.", "Plain article."]:
            self.assertFalse(pd.is_disambiguation("Mercury", parse(text)), text)


class CleanWikitextTest(unittest.TestCase):
    def clean(self, text):
        return pd.clean_wikitext(parse(text), NAMESPACES)

    def test_links_bold_and_templates(self):
        text = "{{Infobox animal|name=Dog}}\n'''Dogs''' are [[mammal]]s. They are [[Canis|canines]]."
        self.assertEqual(self.clean(text), "Dogs are mammals. They are canines.")

    def test_references_comments_and_magic_words_removed(self):
        text = "Water is wet.<ref>Smith 2001</ref><!-- hidden --> __NOTOC__ Ice is cold.<ref name=a/>"
        self.assertEqual(self.clean(text), "Water is wet. Ice is cold.")

    def test_files_categories_and_language_links_removed(self):
        text = ("[[File:Dog.jpg|thumb|200px|A [[dog]] running]]\nDogs bark.\n"
                "[[Category:Animals]]\n[[de:Hund]]")
        self.assertEqual(self.clean(text), "Dogs bark.")

    def test_tables_removed_and_headings_kept(self):
        text = "Intro.\n== History ==\nOld.\n{| class=\"wikitable\"\n|-\n| 1 || 2\n|}\nEnd."
        self.assertEqual(self.clean(text), "Intro.\nHistory\nOld.\nEnd.")

    def test_broken_table_rows_are_dropped_by_safety_net(self):
        # A table with no closing "|}" is not recognised by the parser.
        text = "Intro.\n{| class=\"wikitable\"\n! Name\n|-\n| Row\n"
        self.assertEqual(self.clean(text), "Intro.")

    def test_list_markers_removed_and_trailing_colon_kept(self):
        text = "Colours include:\n* red\n* [[blue]]\n# green"
        self.assertEqual(self.clean(text), "Colours include:\nred\nblue\ngreen")

    def test_empty_brackets_and_space_before_punctuation(self):
        text = "Paris ({{IPA|paʁi}}) is a city , in [[France]] ."
        self.assertEqual(self.clean(text), "Paris is a city, in France.")


class ExtractLinksTest(unittest.TestCase):
    def test_normalised_deduplicated_in_order(self):
        text = ("[[dog]] and [[Dog|dogs]] and [[cat#Food]], [[File:X.jpg|thumb|[[bird]]]], "
                "[[Category:Pets]], [[#Top]], [[wikt:pet]], {{Infobox|x=[[Fish]]}}")
        self.assertEqual(pd.extract_links(parse(text), NAMESPACES), ["Dog", "Cat", "Bird", "Fish"])


DUMP = """<mediawiki xmlns="http://www.mediawiki.org/xml/export-0.11/">
  <siteinfo>
    <namespaces>
      <namespace key="0" case="first-letter" />
      <namespace key="6" case="first-letter">File</namespace>
      <namespace key="14" case="first-letter">Category</namespace>
    </namespaces>
  </siteinfo>
  <page><title>Dog</title><ns>0</ns><id>10</id>
    <revision><id>1</id><text>'''Dogs''' are [[mammal]]s.</text></revision></page>
  <page><title>Doggy</title><ns>0</ns><id>11</id><redirect title="Dog" />
    <revision><id>2</id><text>#REDIRECT [[Dog]]</text></revision></page>
  <page><title>Category:Animals</title><ns>14</ns><id>12</id>
    <revision><id>3</id><text>Animals.</text></revision></page>
  <page><title>Cat</title><ns>0</ns><id>13</id>
    <revision><id>4</id><text>Cats meow.</text></revision></page>
</mediawiki>"""


class IterPagesTest(unittest.TestCase):
    def run_pages(self, limit=None):
        namespaces, redirects = set(), []
        counters = {"pages_seen": 0, "skipped_non_article_namespace": 0, "redirects": 0}

        def on_siteinfo(elem):
            for ns in elem.iter():
                if pd._local(ns.tag) == "namespace" and ns.text:
                    namespaces.add(ns.text.lower())

        pages = list(pd.iter_pages(io.BytesIO(DUMP.encode()), on_siteinfo,
                                   lambda a, b: redirects.append((a, b)), counters, limit))
        return pages, namespaces, redirects, counters

    def test_yields_articles_and_records_redirects(self):
        pages, namespaces, redirects, counters = self.run_pages()
        self.assertEqual([(p[0], p[1]) for p in pages], [(10, "Dog"), (13, "Cat")])
        self.assertEqual(pages[0][2], "'''Dogs''' are [[mammal]]s.")
        self.assertEqual(namespaces, {"file", "category"})
        self.assertEqual(redirects, [("Doggy", "Dog")])
        self.assertEqual(counters, {"pages_seen": 4, "skipped_non_article_namespace": 1, "redirects": 1})

    def test_limit_stops_early(self):
        pages, *_ = self.run_pages(limit=1)
        self.assertEqual(len(pages), 1)


class ProcessArticleTest(unittest.TestCase):
    def setUp(self):
        pd._init_worker(NAMESPACES)

    def test_article_record(self):
        kind, record = pd.process_article((10, "Dog", "'''Dogs''' are [[mammal]]s. [[Category:Pets]]"))
        self.assertEqual(kind, "article")
        self.assertEqual(record, {"id": 10, "title": "Dog", "text": "Dogs are mammals.", "links": ["Mammal"]})

    def test_disambiguation_and_empty_pages_skipped(self):
        self.assertEqual(pd.process_article((1, "Mercury", "{{disambig}}")), ("disambiguation", None))
        self.assertEqual(pd.process_article((2, "Blank", "{{stub}}")), ("empty", None))


if __name__ == "__main__":
    unittest.main()
