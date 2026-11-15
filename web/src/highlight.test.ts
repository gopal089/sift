import { describe, expect, it } from "vitest";
import { splitHighlights } from "./highlight";

describe("splitHighlights", () => {
  it("returns the whole text as one normal piece when nothing is highlighted", () => {
    expect(splitHighlights("Plants make food", [])).toEqual([{ text: "Plants make food", marked: false }]);
  });

  it("cuts the text into normal and marked pieces", () => {
    const text = "Plants make food from sunlight.";
    expect(
      splitHighlights(text, [
        { start: 0, end: 6 },
        { start: 22, end: 30 },
      ]),
    ).toEqual([
      { text: "Plants", marked: true },
      { text: " make food from ", marked: false },
      { text: "sunlight", marked: true },
      { text: ".", marked: false },
    ]);
  });

  it("puts the text back together exactly, including accents and emoji", () => {
    const text = "🙂 Zürich is a café city";
    const zurich = text.indexOf("Zürich");
    const cafe = text.indexOf("café");
    const pieces = splitHighlights(text, [
      { start: cafe, end: cafe + 4 },
      { start: zurich, end: zurich + 6 }, // out of order on purpose
    ]);
    expect(pieces.map((p) => p.text).join("")).toBe(text);
    expect(pieces.filter((p) => p.marked).map((p) => p.text)).toEqual(["Zürich", "café"]);
  });

  it("handles two highlights that touch", () => {
    expect(
      splitHighlights("abcd", [
        { start: 0, end: 2 },
        { start: 2, end: 4 },
      ]),
    ).toEqual([
      { text: "ab", marked: true },
      { text: "cd", marked: true },
    ]);
  });

  it("skips ranges that are empty, overlapping or outside the text", () => {
    const pieces = splitHighlights("hello world", [
      { start: 0, end: 5 },
      { start: 3, end: 8 }, // overlaps the first
      { start: 6, end: 6 }, // empty
      { start: 6, end: 99 }, // past the end
      { start: -2, end: 3 }, // starts before the text
    ]);
    expect(pieces).toEqual([
      { text: "hello", marked: true },
      { text: " world", marked: false },
    ]);
  });

  it("keeps HTML-looking text as plain text", () => {
    const text = "<script>alert(1)</script>";
    expect(splitHighlights(text, [{ start: 8, end: 13 }]).map((p) => p.text).join("")).toBe(text);
  });

  it("returns nothing for an empty snippet", () => {
    expect(splitHighlights("", [])).toEqual([]);
  });
});
