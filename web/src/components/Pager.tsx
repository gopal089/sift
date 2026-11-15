import type { MouseEvent } from "react";
import { ChevronLeftIcon, ChevronRightIcon } from "./Icons";

/** The API serves at most 100 pages of results. */
export const MAX_PAGE = 100;

interface Props {
  page: number;
  /** How many pages there are (at most MAX_PAGE). */
  pageCount: number;
  /** The address of another page, so the links can be opened in a new tab or shared. */
  hrefFor: (page: number) => string;
  onPage: (page: number, event: MouseEvent<HTMLAnchorElement>) => void;
}

/** Previous / "Page 2 of 19" / Next. Previous is left out on page 1, Next on the last page. */
export function Pager({ page, pageCount, hrefFor, onPage }: Props) {
  if (pageCount <= 1) {
    return null; // everything fits on one page
  }
  return (
    <nav className="pager" aria-label="Result pages">
      {page > 1 ? (
        <a className="button pager-previous" href={hrefFor(page - 1)} rel="prev" onClick={(e) => onPage(page - 1, e)}>
          <ChevronLeftIcon small />
          Previous
        </a>
      ) : (
        <span />
      )}
      <span aria-current="page">
        Page {page} of {pageCount}
      </span>
      {page < pageCount ? (
        <a className="button pager-next" href={hrefFor(page + 1)} rel="next" onClick={(e) => onPage(page + 1, e)}>
          Next
          <ChevronRightIcon small />
        </a>
      ) : (
        <span />
      )}
    </nav>
  );
}
