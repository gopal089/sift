import type { MouseEvent } from "react";
import type { Definition } from "../types";
import { sourceLine } from "./ResultList";

interface Props {
  definition: Definition;
  /** Address of a Sift search for a related article's title (same mode). */
  hrefFor: (title: string) => string;
  onRelated: (title: string, event: MouseEvent<HTMLAnchorElement>) => void;
}

/**
 * The short "meaning" block above the results: the top article's title, its first one or two sentences and,
 * under it, up to 5 articles nearest in meaning. A related title searches Sift for that title, so the reader
 * gets that article's own card and results; the title above links to the article itself.
 */
export function DefinitionCard({ definition, hrefFor, onRelated }: Props) {
  return (
    <section className="definition" aria-labelledby="definition-title">
      <h2 className="definition-title" id="definition-title">
        <a href={definition.url} rel="noreferrer">
          {definition.title}
        </a>
      </h2>
      <p className="definition-text">{definition.text}</p>
      <p className="result-source">{sourceLine(definition.url)}</p>
      {definition.related.length > 0 && (
        <>
          <h3 className="related-heading" id="related-heading">
            Related articles
          </h3>
          <ul className="related" aria-labelledby="related-heading">
            {definition.related.map((article) => (
              <li key={article.id}>
                <a href={hrefFor(article.title)} onClick={(event) => onRelated(article.title, event)}>
                  {article.title}
                </a>
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  );
}
