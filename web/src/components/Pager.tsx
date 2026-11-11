interface Props {
  page: number;
  hasMore: boolean;
  onPage: (page: number) => void;
}

/** Previous / Next buttons with the current page number between them. */
export function Pager({ page, hasMore, onPage }: Props) {
  if (page === 1 && !hasMore) {
    return null; // everything fits on one page
  }
  return (
    <nav className="pager" aria-label="Result pages">
      <button type="button" onClick={() => onPage(page - 1)} disabled={page <= 1}>
        ← Previous
      </button>
      <span aria-current="page">Page {page}</span>
      <button type="button" onClick={() => onPage(page + 1)} disabled={!hasMore || page >= 100}>
        Next →
      </button>
    </nav>
  );
}
