import { useEffect, useState, type FormEvent } from "react";
import type { Mode } from "../types";

interface Props {
  /** The search currently shown (from the address bar). */
  query: string;
  mode: Mode;
  onSearch: (query: string, mode: Mode) => void;
}

/** The search box, the Search button and the Keyword / Smart switch. */
export function SearchForm({ query, mode, onSearch }: Props) {
  // "draft" is what is typed in the box; it only becomes a search when the form is submitted.
  const [draft, setDraft] = useState(query);
  useEffect(() => setDraft(query), [query]); // back/forward changed the search: show it in the box

  function submit(event: FormEvent) {
    event.preventDefault(); // stay on the page instead of letting the browser reload it
    const text = draft.trim();
    if (text) onSearch(text, mode);
  }

  return (
    <form className="search" role="search" onSubmit={submit}>
      <label className="search-label" htmlFor="q">
        Search Simple English Wikipedia
      </label>
      <div className="search-row">
        <input
          id="q"
          name="q"
          type="search"
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          placeholder="e.g. photosynthesis"
          maxLength={200}
          autoComplete="off"
          autoCapitalize="none"
          spellCheck={false}
          enterKeyHint="search"
        />
        <button type="submit">Search</button>
      </div>

      <fieldset className="modes">
        <legend>Search mode</legend>
        <label>
          <input
            type="radio"
            name="mode"
            checked={mode === "keyword"}
            onChange={() => onSearch(draft.trim() || query, "keyword")}
          />
          Keyword
        </label>
        <label>
          <input
            type="radio"
            name="mode"
            checked={mode === "smart"}
            onChange={() => onSearch(draft.trim() || query, "smart")}
          />
          Smart <span className="muted">(meaning)</span>
        </label>
      </fieldset>
    </form>
  );
}
