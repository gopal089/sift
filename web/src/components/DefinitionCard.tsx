import { useId, useLayoutEffect, useRef, useState, type MouseEvent } from "react";
import type { Definition } from "../types";
import { ChevronRightIcon } from "./Icons";
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
 *
 * On phones the card is compact, so the results stay on the first screen: the text is cut to 3 lines with a
 * "More" button (only if something is really cut off), and the related articles open with a second button.
 * On wider screens the CSS hides both buttons and shows everything. Screen readers always get the full text:
 * the cut is only visual.
 */
export function DefinitionCard({ definition, hrefFor, onRelated }: Props) {
  const [expanded, setExpanded] = useState(false); // phone: the whole text is shown
  const [relatedOpen, setRelatedOpen] = useState(false); // phone: the related list is shown
  const [cut, setCut] = useState(false); // the text is taller than its 3-line box
  const text = useRef<HTMLParagraphElement>(null);
  const textId = useId();
  const relatedId = useId();

  // Measure before the browser paints, so the "More" button never pops in late (no layout shift).
  useLayoutEffect(() => {
    const measure = () => {
      const el = text.current;
      if (el) setCut(el.scrollHeight > el.clientHeight + 1);
    };
    measure();
    window.addEventListener("resize", measure);
    return () => window.removeEventListener("resize", measure);
  }, [definition.text]);

  const hasRelated = definition.related.length > 0;
  const showMore = cut || expanded; // once opened, the button stays as "Less"

  return (
    <section className="definition" aria-labelledby="definition-title">
      <h2 className="definition-title" id="definition-title">
        <a href={definition.url} rel="noreferrer">
          {definition.title}
        </a>
      </h2>
      <p className="definition-text" id={textId} ref={text} data-expanded={expanded}>
        {definition.text}
      </p>
      <p className="result-source definition-source">{sourceLine(definition.url)}</p>
      {(showMore || hasRelated) && (
        <div className="definition-controls">
          {showMore && (
            <button
              type="button"
              className="text-button"
              aria-expanded={expanded}
              aria-controls={textId}
              onClick={() => setExpanded((open) => !open)}
            >
              <ChevronRightIcon small className="disclosure-icon" />
              {expanded ? "Less" : "More"}
            </button>
          )}
          {hasRelated && (
            <button
              type="button"
              className="text-button"
              aria-expanded={relatedOpen}
              aria-controls={relatedId}
              onClick={() => setRelatedOpen((open) => !open)}
            >
              <ChevronRightIcon small className="disclosure-icon" />
              Related
            </button>
          )}
        </div>
      )}
      {hasRelated && (
        <>
          <h3 className="related-heading" id="related-heading">
            Related articles
          </h3>
          <ul className="related" id={relatedId} aria-labelledby="related-heading" data-open={relatedOpen}>
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
