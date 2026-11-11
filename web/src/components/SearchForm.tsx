import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from "react";
import { suggest } from "../api";
import type { Mode } from "../types";

interface Props {
  /** The search currently shown (from the address bar). */
  query: string;
  mode: Mode;
  onSearch: (query: string, mode: Mode) => void;
}

/** How long to wait after the last key press before asking for suggestions. */
const SUGGEST_DELAY_MS = 120;

/**
 * The search box with autocomplete, the Search button and the Keyword / Smart switch.
 *
 * Autocomplete follows the standard "combobox" pattern so it works with a keyboard and a screen reader:
 * ↓ / ↑ move through the suggestions, Enter picks one, Escape closes the list.
 */
export function SearchForm({ query, mode, onSearch }: Props) {
  // "draft" is what is typed in the box; it only becomes a search when the form is submitted.
  const [draft, setDraft] = useState(query);
  const [suggestions, setSuggestions] = useState<string[]>([]);
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(-1); // which suggestion is highlighted; -1 = none
  const typed = useRef(false); // true only after the user typed (not when the box is filled from the address bar)

  useEffect(() => setDraft(query), [query]); // back/forward changed the search: show it in the box

  // Fetch suggestions a moment after the user stops typing.
  useEffect(() => {
    const prefix = draft.trim();
    if (!typed.current || !prefix) {
      setSuggestions([]);
      setOpen(false);
      return;
    }
    const controller = new AbortController();
    const timer = window.setTimeout(() => {
      suggest(prefix, controller.signal)
        .then((titles) => {
          setSuggestions(titles);
          setOpen(titles.length > 0);
          setActive(-1);
        })
        .catch(() => undefined); // cancelled because the user typed again
    }, SUGGEST_DELAY_MS);
    // Typing again cancels both the waiting timer and a request already on its way.
    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [draft]);

  function run(text: string) {
    typed.current = false;
    setOpen(false);
    setDraft(text);
    if (text) onSearch(text, mode);
  }

  function submit(event: FormEvent) {
    event.preventDefault(); // stay on the page instead of letting the browser reload it
    run(open && active >= 0 ? suggestions[active] : draft.trim());
  }

  function onKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === "Escape") {
      setOpen(false);
    } else if (open && event.key === "ArrowDown") {
      event.preventDefault(); // keep the text cursor where it is
      setActive((i) => (i + 1) % suggestions.length);
    } else if (open && event.key === "ArrowUp") {
      event.preventDefault();
      setActive((i) => (i <= 0 ? suggestions.length - 1 : i - 1));
    }
  }

  return (
    <form className="search" role="search" onSubmit={submit}>
      <label className="search-label" htmlFor="q">
        Search Simple English Wikipedia
      </label>
      <div className="search-row">
        <div className="search-box">
          <input
            id="q"
            name="q"
            type="search"
            value={draft}
            onChange={(event) => {
              typed.current = true;
              setDraft(event.target.value);
            }}
            onKeyDown={onKeyDown}
            onBlur={() => setOpen(false)}
            placeholder="e.g. photosynthesis"
            maxLength={200}
            autoComplete="off"
            autoCapitalize="none"
            spellCheck={false}
            enterKeyHint="search"
            role="combobox"
            aria-autocomplete="list"
            aria-expanded={open}
            aria-controls="suggestions"
            aria-activedescendant={open && active >= 0 ? `suggestion-${active}` : undefined}
          />
          {open && (
            <ul id="suggestions" className="suggestions" role="listbox" aria-label="Suggestions">
              {suggestions.map((title, i) => (
                <li
                  key={title}
                  id={`suggestion-${i}`}
                  role="option"
                  aria-selected={i === active}
                  className={i === active ? "active" : undefined}
                  // mousedown (not click): it fires before the input loses focus and the list closes
                  onMouseDown={(event) => {
                    event.preventDefault();
                    run(title);
                  }}
                >
                  {title}
                </li>
              ))}
            </ul>
          )}
        </div>
        <button type="submit">Search</button>
      </div>

      {/* A radio group: screen readers announce "Search mode" and "1 of 2". */}
      <div className="modes" role="radiogroup" aria-labelledby="mode-label">
        <span id="mode-label" className="muted">
          Search mode
        </span>
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
      </div>
    </form>
  );
}
