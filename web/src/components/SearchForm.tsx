import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from "react";
import { suggest } from "../api";
import type { Mode } from "../types";
import { SearchIcon } from "./Icons";

interface Props {
  /** The search currently shown (from the address bar). */
  query: string;
  mode: Mode;
  onSearch: (query: string, mode: Mode) => void;
  /** Show the sentence that explains the chosen mode (on the home page). */
  explainMode: boolean;
}

/** How long to wait after the last key press before asking for suggestions. */
const SUGGEST_DELAY_MS = 120;

const MODES: { value: Mode; label: string; note: string }[] = [
  { value: "smart", label: "Smart", note: "Smart finds articles by meaning as well as by words, so questions work." },
  { value: "keyword", label: "Keyword", note: "Keyword finds articles that contain your words, ranked by BM25 and links." },
];

/**
 * The search box with autocomplete, the search button and the Smart / Keyword tabs.
 *
 * Autocomplete follows the standard "combobox" pattern so it works with a keyboard and a screen reader:
 * ↓ / ↑ move through the suggestions, Enter picks one, Escape closes the list.
 * The tabs are real radio buttons (hidden) inside labels that look like tabs, so ← / → switch the mode.
 */
export function SearchForm({ query, mode, onSearch, explainMode }: Props) {
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

  const typedPrefix = draft.trim();

  return (
    <form className="search" role="search" onSubmit={submit}>
      <label className="visually-hidden" htmlFor="q">
        Search Simple English Wikipedia
      </label>
      <div className="field">
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
          placeholder="A word, a name or a question"
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
        <button type="submit" className="field-button" aria-label="Search">
          <SearchIcon />
        </button>
        {open && (
          <ul id="suggestions" className="suggestions" role="listbox" aria-label="Suggestions">
            {suggestions.map((title, i) => (
              <li
                key={title}
                id={`suggestion-${i}`}
                role="option"
                aria-selected={i === active}
                // mousedown (not click): it fires before the input loses focus and the list closes
                onMouseDown={(event) => {
                  event.preventDefault();
                  run(title);
                }}
              >
                <Completion title={title} typed={typedPrefix} />
              </li>
            ))}
          </ul>
        )}
      </div>

      {/* A radio group: screen readers announce "Search mode" and "1 of 2". */}
      <fieldset className="modes">
        <legend className="visually-hidden">Search mode</legend>
        {MODES.map((m) => (
          <label key={m.value} className="mode">
            <input
              className="visually-hidden"
              type="radio"
              name="mode"
              value={m.value}
              checked={mode === m.value}
              onChange={() => onSearch(draft.trim() || query, m.value)}
            />
            <span className="mode-name">{m.label}</span>
          </label>
        ))}
      </fieldset>
      {explainMode && <p className="mode-note">{MODES.find((m) => m.value === mode)!.note}</p>}
    </form>
  );
}

/** "Photosynthesis" while "pho" is typed: the typed part normal, the rest bold, so the new part stands out. */
function Completion({ title, typed }: { title: string; typed: string }) {
  if (!typed || !title.toLowerCase().startsWith(typed.toLowerCase())) {
    return <>{title}</>;
  }
  return (
    <span>
      {title.slice(0, typed.length)}
      <span className="suggestion-rest">{title.slice(typed.length)}</span>
    </span>
  );
}
