import type { Highlight } from "./types";

/** A piece of snippet text that is either normal or marked (a matched query word). */
export interface Segment {
  text: string;
  marked: boolean;
}

/**
 * Cuts a snippet into normal and marked pieces using the highlight ranges from the API.
 *
 *   splitHighlights("Plants make food", [{start: 0, end: 6}])
 *     → [{text: "Plants", marked: true}, {text: " make food", marked: false}]
 *
 * The result is plain data; React turns each piece into text, so nothing in a snippet can ever run as HTML.
 * Ranges that are empty, outside the text or overlapping an earlier range are skipped instead of crashing.
 */
export function splitHighlights(text: string, highlights: Highlight[]): Segment[] {
  const segments: Segment[] = [];
  const sorted = [...highlights].sort((a, b) => a.start - b.start);
  let cursor = 0; // everything before this index is already in `segments`
  for (const { start, end } of sorted) {
    if (start < cursor || end <= start || end > text.length) {
      continue;
    }
    if (start > cursor) {
      segments.push({ text: text.slice(cursor, start), marked: false });
    }
    segments.push({ text: text.slice(start, end), marked: true });
    cursor = end;
  }
  if (cursor < text.length) {
    segments.push({ text: text.slice(cursor), marked: false });
  }
  return segments;
}
