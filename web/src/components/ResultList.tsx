import { splitHighlights } from "../highlight";
import type { Result } from "../types";

interface Props {
  results: Result[];
  /** Number of the first result on this page (11 on page 2). */
  firstNumber: number;
}

/** The numbered list of results: title link, address, and the snippet with the query words in bold. */
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
          <p className="result-url">{decodeURI(result.url).replace("https://", "")}</p>
          <p className="result-snippet">
            {splitHighlights(result.snippet, result.highlights).map((segment, i) =>
              segment.bold ? <strong key={i}>{segment.text}</strong> : <span key={i}>{segment.text}</span>,
            )}
          </p>
        </li>
      ))}
    </ol>
  );
}
