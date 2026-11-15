# Frontend redesign — learning notes

## What we built, in simple words
The website worked, but it looked like a default template: a tagline, boxes around everything, a big stats box
pushing the first result halfway down a phone screen. We redesigned it to look like a carefully set reference
book: one reading column, a serif font for the text you read, quiet grey for the numbers, and a pale yellow
background on the words that matched your search.

Only the look changed. The API, the ranking and the results are the same. The one exception, approved
beforehand: the API no longer highlights helper words like "do" or "from" in snippets (ranking still uses them,
and the main-50 MRR stayed exactly the same).

Screenshots: `docs/screenshots/` (home, results in light and dark, phone). Old design:
`docs/design/screenshots/before-*.png`. The full 30-point review with measurements: `docs/design/review.md`.

## Each new or changed file and what it is for

| File | Purpose |
| --- | --- |
| `web/src/tokens.css` | Every colour, size, space, font and timing as a named variable ("token"). Dark mode lives here too. |
| `web/src/styles.css` | All the page's styles, rewritten so that every value is a token. |
| `web/src/styles.test.ts` | Tests that guard the CSS: no raw values, no unused tokens or classes, dark mode only changes colours. |
| `web/src/components/Icons.tsx` | The 4 hand-written SVG icons (search, two arrows, alert). |
| `web/src/components/SearchForm.tsx` | Search box with autocomplete, plus the Smart / Keyword tabs (real radio buttons underneath). |
| `web/src/components/StatsPanel.tsx` | The quiet "184 results · 24 ms · Details" line and the Details list. |
| `web/src/components/ResultList.tsx` | Result titles, the `simple.wikipedia.org › Title` source line, snippets with `<mark>`. |
| `web/src/components/Pager.tsx` | Previous / Page N of M / Next. |
| `web/public/favicon.svg` | The tab icon: three lines, like a ranked list. |
| `engine/.../StopWords.java`, `SnippetBuilder.java` | `highlightTerms`: which query words get highlighted. |
| `docs/design/` | The two directions, prototypes, the review, its screenshots and measurement scripts. |

## Key idea 1: design tokens
A **design token** is a named value, like `--color-ink: #1d1b17` or `--space-4: 1rem`. The CSS never writes
`#1d1b17` or `16px` directly; it writes `var(--color-ink)` or `var(--space-4)`.

Why it helps:
- **Consistency.** There are 8 spacing steps, not 40 random paddings. Things line up because they share values.
- **Dark mode for free.** Dark mode only re-defines the `--color-*` tokens. No other CSS changes:
  ```css
  :root { --color-paper: #faf8f3; --color-ink: #1d1b17; }
  @media (prefers-color-scheme: dark) {
    :root { --color-paper: #171612; --color-ink: #eae6dc; }
  }
  body { background: var(--color-paper); color: var(--color-ink); } /* works in both themes */
  ```
- **A test can enforce it.** `styles.test.ts` reads the CSS as text and fails if it finds a raw colour, size or
  time outside `tokens.css`. It also fails if a token is defined but never used, or if a class is not used by any
  component, so dead CSS cannot pile up.

## Key idea 2: contrast
**Contrast ratio** says how different two colours look in brightness, from 1:1 (same colour) to 21:1 (black on
white). The accessibility rules (WCAG) ask for **4.5:1 for normal text** and **3:1 for borders and icons**.

How it is computed:
1. Turn each colour channel (0–255) into "linear light" (undo the screen's gamma curve).
2. **Relative luminance** L = 0.2126 R + 0.7152 G + 0.0722 B (green counts most, because our eyes are most
   sensitive to it).
3. Ratio = (L of lighter + 0.05) / (L of darker + 0.05).

Worked example: the grey for the source line, `#57534a`, on the paper colour `#faf8f3`.
- `#57534a` = (87, 83, 74) → L = 0.0871
- `#faf8f3` = (250, 248, 243) → L = 0.9393
- Ratio = (0.9393 + 0.05) / (0.0871 + 0.05) = 0.9893 / 0.1371 = **7.2:1**. Passes 4.5:1 easily.

The border colour `#8a8478` gives 3.5:1 on paper: fine for a border (needs 3:1), **not** enough for text. That is
why it is only used for lines. Every pair is in the table in `docs/design/review.md`; the weakest text pair is 6.6:1.

**Highlight without bold.** Matched words used to be bold *and* yellow. A snippet with 6 matches turned into a
wall of bold. Now they are normal weight on a slightly stronger yellow, and black text on that yellow is 12.1:1.

## Key idea 3: responsive layout
**Responsive** means one page that adapts to the screen width, instead of a separate phone site.
- Desktop: a CSS grid with two columns: 8.5rem for the wordmark, then a 40rem reading column.
  40rem is about 70 characters per line; much longer lines are tiring to read.
- Below 48rem (768 px): `@media (max-width: 47.99rem)` switches the grid to one column.
- `minmax(0, 40rem)` lets the column shrink below 40rem on small screens, so nothing overflows. Checked at 320 px
  wide and at 200% zoom: 0 px horizontal scroll.
- Sizes are in `rem` (relative to the user's font size), so if someone sets a bigger default font, everything
  grows together.
- The input is 16 px: iPhones zoom the page in when you tap an input smaller than that.

## Key idea 4: accessibility
**Accessibility** means people using a keyboard, a screen reader or a phone with big fingers can all use the site.
- **Tap targets of 44 × 44 px** for everything you can press. Small links inside sentences get invisible padding
  (`padding-block: calc((44px - 1em) / 2)`) so the text stays the same size but the touch area is big.
- **Real HTML controls.** The Smart / Keyword tabs are radio buttons inside a `<fieldset>`, just hidden visually.
  The browser gives us arrow-key switching and "Search mode, Smart, 1 of 2" for screen readers for free.
- **Autocomplete as a combobox.** `role="combobox"` on the input and `role="listbox"` on the list, with
  `aria-activedescendant` telling the screen reader which suggestion is highlighted while focus stays in the input.
- **Announcements.** The result count has `role="status"`, so a screen reader says "184 results" after a search.
  Errors have `role="alert"`.
- **Visible focus.** A 3 px ring in the link colour (8.1:1 on paper) on every focusable thing.
- **Reduced motion.** `@media (prefers-reduced-motion: reduce)` turns the small animations off for people who
  get dizzy from motion. All animations are under 200 ms anyway.
- **No layout shift.** The footer appears only after the first answer, so arriving results don't push it down.
  System fonts never swap after load. Measured shift (CLS): 0 on every page.

## Complexity (the one algorithmic change)
`highlightTerms(terms)`: for q query words, one hash-set lookup each → **O(q)** time, O(q) space. The set of
helper-word stems is built once when the class loads. The snippet builder then scans the article's positions as
before; nothing about ranking changes.

## 5 interview questions

1. **What is a design token and why use them instead of writing values in CSS?**
   A named value (colour, size, spacing) defined once. All styles refer to the name. It keeps the design
   consistent, makes dark mode a matter of re-defining colour tokens, and lets a test check that no stray values
   slip in.

2. **How do you check if text is readable on its background?**
   Compute the WCAG contrast ratio from the two colours' relative luminance: (L₁ + 0.05)/(L₂ + 0.05). Normal
   text needs at least 4.5:1, large text and borders 3:1. I computed every pair from the token file and also ran
   Lighthouse's contrast audit.

3. **Why did you choose system fonts instead of a nice web font?**
   They load instantly (0 bytes), and the text never re-flows when a web font arrives, so there is no layout
   shift. The trade-off is that the serif looks a bit different on each operating system. For a search page,
   speed and stability mattered more.

4. **You changed what gets highlighted. How did you prove search results didn't change?**
   Ranking uses the full list of query words; only the highlight list is filtered. I saved the API's top 100
   results (ids, scores, order) for the 50 main queries in both modes before and after the change and compared
   them: identical, and MRR identical to 5 decimals. Only snippet text and highlight counts changed.

5. **How did you make the site work for keyboard and screen-reader users?**
   Real HTML controls where possible (radio buttons for the mode tabs, buttons, links), the ARIA combobox
   pattern for autocomplete, `role="status"` for the result count, a visible 3 px focus ring, 44 px tap targets,
   and focus moved to the first result after changing page. I checked the tab order and focus rings with a script
   and Lighthouse accessibility scored 100.
