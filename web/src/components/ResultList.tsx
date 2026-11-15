import { splitHighlights } from "../highlight";
import type { Result } from "../types";

interface Props {
  results: Result[];
  /** Number of the first result on this page (11 on page 2). */
  firstNumber: number;
}

/** "https://simple.wikipedia.org/wiki/World_War_II" → "simple.wikipedia.org › World War II" */
export function sourceLine(url: string): string {
  const match = /^https?:\/\/([^/]+)\/wiki\/(.+)$/.exec(url);
  if (!match) return url.replace(/^https?:\/\//, "");
  let name = match[2];
  try {
    name = decodeURIComponent(name);
  } catch {
    // a broken %-escape: show the name as it is
  }
  return `${match[1]} › ${name.replace(/_/g, " ")}`;
}

/** The list of results: title link, a quiet source line, and the snippet with the query words marked. */
export function ResultList({ results, firstNumber }: Props) {
  return (
    <ol className="results" start={firstNumber}>
      {results.map((result) => (
        <li key={result.id} className="result">
          <h2 className="result-title">
            <a href={result.url} rel="noreferrer">
              {result.title}
            </a>
          </h2>
          <p className="result-source">{sourceLine(result.url)}</p>
          <p className="result-snippet">
            {splitHighlights(result.snippet, result.highlights).map((segment, i) =>
              segment.marked ? <mark key={i}>{segment.text}</mark> : <span key={i}>{segment.text}</span>,
            )}
          </p>
        </li>
      ))}
    </ol>
  );
}
