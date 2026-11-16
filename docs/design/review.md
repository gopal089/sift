# Website redesign — the 30-point review

Every point was checked with a fresh screenshot or a real measurement, against the production build: one Spring
Boot jar serving the website and the API on `localhost:8080`, the real 280,748-article index, headless Chrome.
The evidence is in `docs/design/review/`:
- screenshots, named by check number;
- `measurements.json`, every number below;
- `lighthouse-summary.json`;
- the scripts that produced them: `review1.mjs` for the states, `review2.mjs` for the measurements, and `cdp.mjs`, a
  small Chrome DevTools driver with no npm packages.

Each fix was followed by a rebuild and a re-run of **all** screenshot and measurement scripts and Lighthouse, so
every point a fix could affect was re-checked. The table shows the final run.

| # | Check | How it was verified | Result | Fix made during the review |
| --- | --- | --- | --- | --- |
| 1 | Home at 1280 px | Screenshot `01-home-1280.png` | Pass | — |
| 2 | Home at 375 px | Screenshot `02-home-375.png` | Pass | — |
| 3 | Results at 1280 px | Screenshot `03-results-1280.png` (`how do plants make food`, Smart) | Pass | — |
| 4 | Results at 375 px | Screenshot `04-results-375.png` | Pass | — |
| 5 | Results at 768 px | Screenshot `05-results-768.png`: the wordmark gutter stays and the column narrows to fit | Pass | — |
| 6 | Dark theme, all states | `*-dark.png` for home, results, 768, autocomplete, did-you-mean, no results, error, both loading states. The contrast table below covers both themes. | Pass | — |
| 7 | Autocomplete open | `07-autocomplete-*.png`. Typed `pho`, then ↓↓: 5 options, the second marked `aria-selected`, the typed part regular and the rest bold. | Pass | The browser's own blue "×" clear button did not match the palette. It is hidden; select the text to replace it. |
| 8 | "Did you mean" shown | `08-did-you-mean-*.png` for `pyhton` → *python* (a link with a real `?q=python` address) | Pass | A long correction made the link a block and left the "?" alone on its own line (`12-long-query-375.png`). In-text links now use padding that does not change the line layout. |
| 9 | No-results state | `09-no-results-*.png` for `qqxzvw` in Keyword mode: the message, two tips, and a link to Smart mode | Pass | At 375 px the "Search in Smart mode" tip wrapped with a big gap. Fixed by the same in-text link change. |
| 10 | Error state (API stopped) | The page was open, the server process was killed, then a new search was made. Shows `role="alert"`: "Sift cannot reach its search server. Check your connection, then try again." The server was restarted, and **Try again** recovered (`Glacier` results). `10-error-*.png` | Pass | The old copy said "Check that it is running", which speaks to a developer, not a visitor. Rewritten in plain words. |
| 11 | Loading state | Network delay set to 3 s. The first search shows "Searching…" (`11-loading-first`). Next page: the old results stay and "Searching…" appears in the stats line (`11-loading-next`). Both appear only if a search takes over 150 ms, so fast searches do not flicker. | Pass | (a) "Searching…" sat 12 px higher than the line it replaces; the line now has a fixed 44 px height. (b) The old results were dimmed to 55%, which drops text below 4.5:1 (2.5:1 worst case; even 80% gives 4.4:1). The dimming was removed. |
| 12 | Very long query (200 chars) | 200 characters (the input's `maxlength`), at 1280 and 375. The box scrolls inside itself, nothing overflows (0 px), and the "Did you mean" wraps cleanly. `12-long-query-*.png` | Pass | See 8 |
| 13 | Very long title wraps | "List of revocations of appointments to orders and awarded decorations and medals of the United Kingdom" at 1280, 375 and 320. It wraps on word breaks, with 0 px overflow. `13-long-title-*.png` | Pass | — |
| 14 | Page 2 and last page | `photosynthesis` (Keyword). Page 2: "Previous · Page 2 of 17 · Next". Page 17: "Previous · Page 17 of 17" with 3 results and no Next. `14-*.png` | Pass | — |
| 15 | Keyboard only: full flow | Tab to the box, type `pho`, ↓↓, Enter → `?q=Photosynthesis`. Then: Tab to the mode, → switches to Keyword; Tab to Details, Enter opens it; Tab to a result; Tab to Next, Enter → page 2 with **focus on the first new result** ("Great Oxygenation Event") and the page scrolled to the top. Escape closes the suggestions. | Pass | — |
| 16 | Visible focus everywhere | Each Tab stop's computed outline was measured. All stops show a 3 px ring in `--color-focus` (8.1:1 in light, 9.4:1 in dark). The box draws its ring around the whole field. The radios draw it around the visible tab. `16-focus-*.png` | Pass | — |
| 17 | Tab order is logical | Home: Sift → search box → Search → mode → 4 examples → footer links. Results: Sift → box → Search → mode → Details → result titles → pager → footer. This is the same order as on screen. | Pass | Making the tabs 44 px wide had moved "Smart" 4 px out of line with the box. The underline is now on the word only, and the tap area is still 44 px. |
| 18 | Screen-reader labels and roles | Chrome's accessibility tree on home, results and autocomplete. 0 unnamed controls. Landmarks: banner, `search`, main, `navigation "Result pages"`, contentinfo. The combobox is named "Search Simple English Wikipedia" with `listbox "Suggestions"` and its options; there is a `group "Search mode"` with 2 radios. A `status` live region gives the count; errors use `alert`. One `h1` per view, and result titles are `h2`. | Pass | — |
| 19 | Text contrast ≥ 4.5:1 | Computed from `tokens.css` with the WCAG formula (table below). The worst text pair is 6.6:1. Lighthouse's colour-contrast audit passes on all 4 pages. | Pass | See 11(b) |
| 20 | Tap targets ≥ 44 px | At 375 px, every link, button, input, tab and option was measured (`getClientRects`) on home, results (Details open), did-you-mean, no results and autocomplete. Final: **0 failing**; the smallest is the wordmark at 44 × 44. In-text links are ≥ 44 px tall. | Pass | First run failed: the wordmark was 35 px wide, in-text links were 36–39 px tall (sized by line height, not letter height), and "Smart" and short example links were under 44 px wide. Fixed with `min-width` and padding from the `--size-tap` token. |
| 21 | No horizontal scroll at 320 px | `scrollWidth − innerWidth` = **0 px** for home, results (Details open), did-you-mean, no results, 200-char query, long title, page 2 and autocomplete. `21-*.png` | Pass | — |
| 22 | Zoom to 200% still usable | A 1280 px window at 200% = 640 CSS px at 2×. Home and results with Details open: 0 px overflow, the phone layout takes over, and everything is reachable. `22-zoom-200-*.png` | Pass | — |
| 23 | No layout shift on load | Layout shifts summed with `PerformanceObserver`, with 0 ms and 800 ms network delay, home and results, desktop and phone: **0** everywhere. Lighthouse CLS: 0. | Pass | First run: results 0.056 (desktop) and 0.111 (phone, slow network). The arriving results pushed the footer down. The footer now waits for the first answer. The home page's article-count line keeps its height before the number arrives. Fonts are system fonts, so nothing re-flows. |
| 24 | Reduced motion respected | Computed styles: normally the dropdown is `drop-in 0.12s` and the Details arrow turns in 0.12s. With `prefers-reduced-motion: reduce` they are `none 0s` and `0s`. Every animation is under 200 ms. | Pass | — |
| 25 | Every CSS value is a token | `styles.test.ts`, which runs with the Vitest suite. Outside `tokens.css` there are no hex colours, no colour functions, no font names, and no numbers with units; only 0, 1, −1, 2 and 1fr for arithmetic. Every token used is defined and every token defined is used. Dark mode only changes `--color-*`. A planted `color: #ff0000; margin: 13px` made it fail. | Pass | Vitest replaces CSS files with empty text by default, so the first version passed without checking anything. Turned on `css: true`, then proved the test fails on a bad value. |
| 26 | No banned item present | Scan of `web/src`, `index.html` and `public/` for gradient, shadow, blur, backdrop, filter, glow, emoji and pictographs, marketing words, lorem, `href="#"`, TODO and `console.log`: none. Radii are 2 px and 6 px only (no pills). There are no cards: results are separated by space. 4 icons, all hand-drawn inline SVG. | Pass | — |
| 27 | No unused code or dependencies | `tsc` with `noUnusedLocals`/`noUnusedParameters`. `styles.test.ts` checks that every CSS class is used by a component. Every export was checked against its importers; every dependency is imported (react, react-dom; devDeps are used by tests and the build). No commented-out code. | Pass | `API_BASE` and `DEFAULT_MODE` were exported but used only in their own file, so they are no longer exported. `documentCount` had no test; 2 tests added. |
| 28 | Back/forward and shareable URLs | Opening `?q=volcano&mode=keyword&page=2` directly fills the box, selects Keyword and shows "Page 2 of 73", with the title "volcano – Sift". Next → page 3, Back → page 2, Forward → page 3. The wordmark → home (Keyword kept), and Back → the search again. Examples, did-you-mean, pager and "Search in Smart mode" are real links (Ctrl/Cmd-click opens a new tab). | Pass | — |
| 29 | Lighthouse ≥ 95 / ≥ 95 / ≥ 90 | Lighthouse 13.6.0 (run with `npx`, not added to the project), home and results, mobile and desktop. Scores are below the table. | Pass | First run had best practices at 96, with one console error: a 404 for `/favicon.ico`. Added a hand-drawn SVG favicon (three lines, like a ranked list), and it is now 100. |
| 30 | Side by side with the old screenshots | `30-before-after.png`, the same query, at 1280 and 375 | Pass, every screen is clearly better | See below |

## Lighthouse (final run, `lighthouse-summary.json`)
| Page | Performance | Accessibility | Best practices | LCP | CLS |
| --- | --- | --- | --- | --- | --- |
| Home, mobile | 100 | 100 | 100 | 0.8 s | 0 |
| Results, mobile | 98 | 100 | 100 | 2.4 s | 0 |
| Home, desktop | 100 | 100 | 100 | 0.2 s | 0 |
| Results, desktop | 100 | 100 | 100 | 0.5 s | 0 |

Mobile results is the only score below 100. Lighthouse simulates a slow phone network. The results can only be
drawn after the page's script has loaded *and* the search request has come back, so the largest text paints at 2.4 s.
These are laptop numbers against `localhost`.

## Contrast (check 19), from `tokens.css`
| Pair | Used for | Needs | Light | Dark | Pass |
| --- | --- | --- | --- | --- | --- |
| `ink` on `paper` | Body text, snippets, headings | 4.5:1 | `#1d1b17` on `#faf8f3`: **16.2:1** | `#eae6dc` on `#171612`: **14.5:1** | yes |
| `ink-2` on `paper` | Source line, stats, tabs, footer | 4.5:1 | `#57534a` on `#faf8f3`: **7.2:1** | `#a8a296` on `#171612`: **7.1:1** | yes |
| `link` on `paper` | Result titles, links | 4.5:1 | `#1a4c8b` on `#faf8f3`: **8.1:1** | `#9cbcf0` on `#171612`: **9.4:1** | yes |
| `ink` on `mark` | Highlighted (matched) word | 4.5:1 | `#1d1b17` on `#efd78c`: **12.1:1** | `#eae6dc` on `#5a4a1c`: **6.9:1** | yes |
| `ink` on `raised` | Typed text, suggestions | 4.5:1 | `#1d1b17` on `#ffffff`: **17.2:1** | `#eae6dc` on `#201e1a`: **13.4:1** | yes |
| `ink-2` on `raised` | Placeholder | 4.5:1 | `#57534a` on `#ffffff`: **7.7:1** | `#a8a296` on `#201e1a`: **6.6:1** | yes |
| `ink` on `selected` | Highlighted suggestion | 4.5:1 | `#1d1b17` on `#ece4cf`: **13.6:1** | `#eae6dc` on `#34302a`: **10.5:1** | yes |
| `error` on `paper` | Error icon and rule (non-text) | 3.0:1 | `#9b2c1f` on `#faf8f3`: **7.1:1** | `#f0a493` on `#171612`: **9.0:1** | yes |
| `field` on `paper` | Borders of the field and buttons (non-text) | 3.0:1 | `#8a8478` on `#faf8f3`: **3.5:1** | `#6f6a60` on `#171612`: **3.4:1** | yes |
| `focus` on `paper` | Focus ring (non-text) | 3.0:1 | `#1a4c8b` on `#faf8f3`: **8.1:1** | `#9cbcf0` on `#171612`: **9.4:1** | yes |

**Highlight without bold:** the matched words are no longer bold, so the yellow highlight now does all the work.
It was made stronger than in the prototype (`#f2e3b3` → `#efd78c` in light, `#4d4120` → `#5a4a1c` in dark), and
the text on it stays at 12.1:1 and 6.9:1.

## Check 30, before vs after (`30-before-after.png`)
- **Phone results:** the first result now starts about **250 px** from the top of the screen, instead of about **490 px**
  (measured on the two 375 px screenshots). The old page spent that space on a tagline, a label, two rows of radio
  buttons, a result-count line and a 6-number stats box. Now the numbers sit on one quiet line, and the full list
  opens under "Details".
- **Desktop results:** the same gain. The serif titles and snippets read like an encyclopaedia, and one left edge
  runs from the search box down through every title.
- **Home:** the old page was a bare form with "Type a word…". The new one has a real heading, says what each mode
  does, shows the real article count, and gives four example searches that work as links.
- **Snippets:** the old bold highlights made busy snippets heavy. The new ones are pale and normal weight, and the
  helper word "do" is no longer marked (an API-side change; see `eval/results/highlight-change.txt`).
- **Not better or worse, just different:** the home page leaves the right half of a wide screen empty. That is the
  price of one left-aligned reading column; it keeps the search box in the same place on the home and results
  pages.

## Notes and limits
- **Fonts:** two text families, both system fonts: a serif for reading and a sans for controls. A third, monospace,
  is used **only** for the values in the Details list, as asked. All three are already on the device, so nothing
  downloads and nothing shifts.
- **Screen readers:** the screen-reader check uses Chrome's accessibility tree, not a live screen reader such as
  VoiceOver. It confirms the roles, names and order a screen reader is given.
- **Not done:** no manual check on a real phone; that comes with the deployment (Phase 7).
- **Running the scripts again:** they need Chrome and the jar on `:8080`, started with its PID in `/tmp/sift.pid`
  and its Java home in `/tmp/sift.javahome`. The error-state step stops and restarts the server.

## Re-check after the definition card (2026-11-16)
The definition card and its "Related articles" list were added above the results. The checks it can affect were
run again with `review/review-card.mjs`. Results are in `review/card/measurements.json`, screenshots in
`review/card/`, and Lighthouse in `review/card/lighthouse-summary.json`.

| Check | Result | Pass |
| --- | --- | --- |
| 3-5, 13 Screens at 1280, 768, 375 and 320, light and dark | `card-photosynthesis-*.png`, `card-redirect-einstein-1280.png`, `card-long-related-375.png` | Pass |
| 15-17 Keyboard and focus | Tab order: search → Smart → Details → card title → the 5 related articles → results. Every stop has the 3 px ring. Enter on "Calvin cycle" searches it, and that page shows its own card | Pass |
| 18 Names and roles | The card is a region named by its title. Headings go h1 → h2 (card) → h3 "Related articles" → h2 (results). 0 unnamed controls | Pass |
| 19 Contrast | No new colours. The card uses ink, ink-2 and link on paper, which are already in the table above (16.2, 7.2 and 8.1 : 1 in light) | Pass |
| 20 Tap targets at 375 px | First run: **2 failures**, short related titles were narrower than 44 px ("Algae" 41 px, "Lion" 31 px). Fixed with `min-width: var(--size-tap)`. Re-run: 0 failures on 5 card pages | Pass after fix |
| 21-22 No sideways scroll at 320 px and at 200 % zoom | 0 px on 5 card pages, including long related titles | Pass |
| 23 Layout shift | 0 with 0 ms and 800 ms network delay, desktop and phone. The card arrives in the same answer as the results | Pass |
| 29 Lighthouse, card page `?q=photosynthesis` | Desktop 100 / 100 / 100. Mobile **97** / 100 / 100 (LCP 2.6 s) | Pass (≥ 95) |

### Compact card on phones (same day, second pass)
The full card pushed the first result to 593 px on a 375 px screen. Below 48rem the card is now compact: the text is
cut to 3 lines with a "More" button (only when something is cut off), the related articles open with a "Related"
button, and the source line is hidden. Desktop is unchanged (checked: no buttons, list and source line shown).

| Check | Result | Pass |
| --- | --- | --- |
| First result on a 375 px screen (target: above 420 px) | **385–410 px** on 6 card pages (no card: 236 px) | Pass |
| 20 Tap targets | 0 failures on 6 card pages with "More" and "Related" both open; the buttons are 44 px tall | Pass |
| 15-17 Keyboard at phone width | Tab reaches the title, "More", "Related". Enter opens each (`aria-expanded` true, "More" becomes "Less"); the next Tab lands on the first related article. 3 px ring on every stop | Pass |
| 21-22 320 px and 200 % zoom | 0 px sideways scroll, closed and open (`phone-open-320.png`, `phone-open-zoom-200.png`) | Pass |
| 23 Layout shift | 0 with 0 ms and 800 ms delay. "More" is measured before the first paint, so it never pops in late | Pass |
| Dark theme | `phone-compact-375-dark.png`, `phone-open-375-dark.png` | Pass |
| 29 Lighthouse, mobile card page | Two runs: 100 (LCP 0.8 s) and 97 (LCP 2.6 s); accessibility and best practices 100. The score varies from run to run | Pass (≥ 95) |

The cut is visual only: the full text stays in the page, so screen readers read all of it.

**Before the compact card:** with a card, the first result started **593 px** from the top on a 375 px screen. On a page
without a card it starts at 236 px. The card answers the query itself, so it earns the space, but on a small phone the
results now start below the first screen for those queries.
