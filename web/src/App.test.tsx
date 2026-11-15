import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { App } from "./App";
import { sourceLine } from "./components/ResultList";
import type { SearchResponse } from "./types";
import { readState, writeState } from "./urlState";

const answer: SearchResponse = {
  query: "plants",
  mode: "keyword",
  modeUsed: "keyword",
  notice: null,
  didYouMean: null,
  page: 1,
  pageSize: 10,
  totalMatches: 25,
  hasMore: true,
  definition: null,
  results: [
    {
      id: 1,
      title: "Plant",
      url: "https://simple.wikipedia.org/wiki/Plant",
      snippet: "Plants <b>make</b> food.",
      highlights: [{ start: 0, end: 6 }],
      score: 3.2,
    },
  ],
  stats: {
    tookMs: 0.42,
    documentsSearched: 280748,
    totalMatches: 25,
    terms: ["plant"],
    matchMode: "AND",
    cache: "miss",
    embedMs: null,
    vectorMs: null,
    cardMs: null,
  },
};

const card: SearchResponse["definition"] = {
  id: 7,
  title: "Photosynthesis",
  url: "https://simple.wikipedia.org/wiki/Photosynthesis",
  text: "Photosynthesis is a process in which green plants make their own food from sunlight.",
  matchedBy: "title",
  related: [
    { id: 8, title: "Calvin cycle", url: "https://simple.wikipedia.org/wiki/Calvin_cycle" },
    { id: 9, title: "Algae", url: "https://simple.wikipedia.org/wiki/Algae" },
  ],
};

type Answer = SearchResponse;

/**
 * A fake server: /health says 280,748 documents, /suggest answers with `suggestions`, /search with `search`
 * (an object, or a function of the request address). Returns the mock, so tests can look at the requests.
 */
function fakeServer(search: Partial<Answer> | ((url: string) => Partial<Answer>), suggestions: string[] = []) {
  const fetchMock = vi.fn(async (url: string) => {
    if (url.startsWith("/api/health")) return new Response(JSON.stringify({ status: "ok", documents: 280748 }));
    if (url.startsWith("/api/suggest")) return new Response(JSON.stringify({ prefix: "", suggestions }));
    const body = typeof search === "function" ? search(url) : search;
    return new Response(JSON.stringify({ ...answer, ...body }), { status: 200 });
  });
  vi.stubGlobal("fetch", fetchMock);
  vi.stubGlobal("scrollTo", vi.fn());
  return fetchMock;
}

const searchCalls = (fetchMock: ReturnType<typeof fakeServer>) =>
  fetchMock.mock.calls.map((c) => c[0]).filter((url) => url.startsWith("/api/search"));

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  window.history.replaceState(null, "", "/");
});

describe("home page", () => {
  it("shows the heading, the article count from the server, and example searches as real links", async () => {
    fakeServer({});
    render(<App />);
    expect(screen.getByRole("heading", { level: 1 }).textContent).toBe("Search Simple English Wikipedia");
    expect(await screen.findByText("280,748 articles, from the 1 November 2026 dump.")).toBeTruthy();
    const examples = within(screen.getByRole("list", { name: "Example searches" })).getAllByRole("link");
    expect(examples.map((a) => a.getAttribute("href"))).toEqual([
      "?q=photosynthesis",
      "?q=how+do+plants+make+food",
      "?q=world+war+2",
      "?q=pyhton",
    ]);
    expect(screen.getByText(/Smart finds articles by meaning/)).toBeTruthy();
  });

  it("an example link searches without reloading the page", async () => {
    const fetchMock = fakeServer({});
    render(<App />);
    fireEvent.click(screen.getByRole("link", { name: "world war 2" }));
    await screen.findByRole("link", { name: "Plant" });
    expect(window.location.search).toBe("?q=world+war+2");
    expect(searchCalls(fetchMock)).toEqual(["/api/search?q=world+war+2&mode=smart&page=1"]);
  });
});

describe("results", () => {
  it("searches, marks query words, shows the quiet stats line and the address bar state", async () => {
    const fetchMock = fakeServer({});
    window.history.replaceState(null, "", "/?mode=keyword");
    render(<App />);

    fireEvent.change(screen.getByLabelText("Search Simple English Wikipedia"), { target: { value: " plants " } });
    fireEvent.click(screen.getByRole("button", { name: "Search" }));

    await screen.findByRole("link", { name: "Plant" });
    expect(searchCalls(fetchMock)).toEqual(["/api/search?q=plants&mode=keyword&page=1"]);
    expect(window.location.search).toBe("?q=plants&mode=keyword");
    expect(document.title).toBe("plants – Sift");
    expect(screen.getByRole("status").textContent).toBe("25 results");
    expect(screen.getByText("Plants").tagName).toBe("MARK");
    expect(screen.getByText("simple.wikipedia.org › Plant")).toBeTruthy();
    // HTML in a snippet is shown as text, never run
    expect(screen.getByText("<b>make</b> food.", { exact: false })).toBeTruthy();
    expect(document.querySelector(".result-snippet b")).toBeNull();
  });

  it("Details opens the numbers behind the search; Smart adds the model and vector times", async () => {
    fakeServer({ mode: "smart", stats: { ...answer.stats, tookMs: 4.2, matchMode: "HYBRID", embedMs: 2.1, vectorMs: 0.8 } });
    window.history.replaceState(null, "", "/?q=plants");
    render(<App />);
    await screen.findByRole("link", { name: "Plant" });

    const details = screen.getByRole("button", { name: "Details" });
    const list = document.getElementById(details.getAttribute("aria-controls")!)!;
    expect(details.getAttribute("aria-expanded")).toBe("false");
    expect(list.hidden).toBe(true);

    fireEvent.click(details);
    expect(details.getAttribute("aria-expanded")).toBe("true");
    expect(list.hidden).toBe(false);
    const values = within(list).getAllByRole("definition").map((dd) => dd.textContent);
    expect(values).toEqual(["4.20 ms", "2.10 ms", "0.80 ms", "280,748", "25", "miss", "plant"]);
  });

  it("Details shows the card time when the page has one", async () => {
    fakeServer({ definition: card, stats: { ...answer.stats, cardMs: 1.25 } });
    window.history.replaceState(null, "", "/?q=photosynthesis");
    render(<App />);
    await screen.findByRole("region", { name: "Photosynthesis" });
    const details = screen.getByRole("button", { name: "Details" });
    fireEvent.click(details);
    const list = document.getElementById(details.getAttribute("aria-controls")!)!;
    const rows = within(list).getAllByRole("term").map((dt) => dt.textContent);
    expect(rows).toContain("Card and related");
    expect(within(list).getAllByRole("definition").map((dd) => dd.textContent)).toContain("1.25 ms");
  });

  it("keyword mode has no model or vector time", async () => {
    fakeServer({});
    window.history.replaceState(null, "", "/?q=plants&mode=keyword");
    render(<App />);
    await screen.findByRole("link", { name: "Plant" });
    expect(screen.queryByText("Meaning model")).toBeNull();
  });

  it("switching the mode tab searches again in that mode", async () => {
    const fetchMock = fakeServer({});
    window.history.replaceState(null, "", "/?q=plants");
    render(<App />);
    await screen.findByRole("link", { name: "Plant" });
    expect(screen.getByRole("radio", { name: "Smart" })).toHaveProperty("checked", true);

    fireEvent.click(screen.getByRole("radio", { name: "Keyword" }));
    await waitFor(() => expect(window.location.search).toBe("?q=plants&mode=keyword"));
    expect(searchCalls(fetchMock).at(-1)).toBe("/api/search?q=plants&mode=keyword&page=1");
  });

  it("keeps the old results on screen while the next page loads, and says “Searching…”", async () => {
    let release: () => void = () => undefined;
    const slow = new Promise<void>((resolve) => (release = resolve));
    const fetchMock = vi.fn(async (url: string) => {
      if (url.includes("page=2")) await slow;
      return new Response(JSON.stringify({ ...answer, page: url.includes("page=2") ? 2 : 1 }));
    });
    vi.stubGlobal("fetch", fetchMock);
    vi.stubGlobal("scrollTo", vi.fn());
    window.history.replaceState(null, "", "/?q=plants");
    render(<App />);
    await screen.findByRole("link", { name: "Plant" });

    fireEvent.click(screen.getByRole("link", { name: "Next" }));
    expect(screen.getByRole("main").getAttribute("aria-busy")).toBe("true");
    expect(screen.getByRole("link", { name: "Plant" })).toBeTruthy(); // still there
    expect(screen.getByText("Searching…")).toBeTruthy();

    await act(async () => release());
    await waitFor(() => expect(screen.getByRole("main").getAttribute("aria-busy")).toBe("false"));
    expect(screen.queryByText("Searching…")).toBeNull();
  });
});

describe("no layout shift", () => {
  it("the footer waits until the first results arrive, so they never push it down", async () => {
    let release: () => void = () => undefined;
    const slow = new Promise<void>((resolve) => (release = resolve));
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => {
        await slow;
        return new Response(JSON.stringify(answer));
      }),
    );
    window.history.replaceState(null, "", "/?q=plants");
    render(<App />);
    expect(screen.queryByRole("contentinfo")).toBeNull();
    await act(async () => release());
    await screen.findByRole("link", { name: "Plant" });
    expect(screen.getByRole("contentinfo").textContent).toContain("CC BY-SA 4.0");
  });
});

describe("pager", () => {
  it("page 1 has Next only; Next goes to page 2 and moves focus to the first new result", async () => {
    fakeServer((url) => ({ page: url.includes("page=2") ? 2 : 1 }));
    window.history.replaceState(null, "", "/?q=plants");
    render(<App />);
    await screen.findByRole("link", { name: "Plant" });
    const pager = screen.getByRole("navigation", { name: "Result pages" });
    expect(within(pager).queryByRole("link", { name: "Previous" })).toBeNull();
    expect(pager.textContent).toContain("Page 1 of 3");

    fireEvent.click(within(pager).getByRole("link", { name: "Next" }));
    await waitFor(() => expect(window.location.search).toBe("?q=plants&page=2"));
    await waitFor(() => expect(document.activeElement?.textContent).toBe("Plant"));
  });

  it("the last page has Previous only", async () => {
    fakeServer({ page: 3, hasMore: false });
    window.history.replaceState(null, "", "/?q=plants&page=3");
    render(<App />);
    await screen.findByRole("link", { name: "Plant" });
    const pager = screen.getByRole("navigation", { name: "Result pages" });
    expect(pager.textContent).toContain("Page 3 of 3");
    expect(within(pager).getByRole("link", { name: "Previous" }).getAttribute("href")).toBe("?q=plants&page=2");
    expect(within(pager).queryByRole("link", { name: "Next" })).toBeNull();
  });

  it("is not shown when everything fits on one page", async () => {
    fakeServer({ totalMatches: 4, hasMore: false });
    window.history.replaceState(null, "", "/?q=plants");
    render(<App />);
    await screen.findByRole("link", { name: "Plant" });
    expect(screen.queryByRole("navigation", { name: "Result pages" })).toBeNull();
  });
});

describe("messages", () => {
  it("shows the server's message and a retry button when the search fails", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response(JSON.stringify({ error: { code: "X", message: "Server is busy." } }), { status: 500 })),
    );
    window.history.replaceState(null, "", "/?q=plants");
    render(<App />);
    const alert = await screen.findByRole("alert");
    expect(alert.textContent).toContain("Server is busy.");
    expect(within(alert).getByRole("button", { name: "Try again" })).toBeTruthy();
  });

  it("no results in keyword mode: tips and a link to try Smart mode", async () => {
    fakeServer({ query: "zzqx", totalMatches: 0, hasMore: false, results: [] });
    window.history.replaceState(null, "", "/?q=zzqx&mode=keyword");
    render(<App />);
    expect(await screen.findByText("No articles match “zzqx”.")).toBeTruthy();
    expect(screen.getByRole("status").textContent).toBe("No results");
    const smart = screen.getByRole("link", { name: "Search in Smart mode" });
    expect(smart.getAttribute("href")).toBe("?q=zzqx");
    fireEvent.click(smart);
    await waitFor(() => expect(window.location.search).toBe("?q=zzqx"));
  });
});

describe("autocomplete and did you mean", () => {
  it("shows suggestions while typing; arrow down + Enter searches the chosen title", async () => {
    const fetchMock = fakeServer((url) => ({ query: decodeURIComponent(url) }), ["Python", "Pythagoras"]);
    render(<App />);

    const box = screen.getByRole("combobox");
    fireEvent.change(box, { target: { value: "pyt" } });
    const options = await screen.findAllByRole("option");
    expect(options.map((o) => o.textContent)).toEqual(["Python", "Pythagoras"]);
    expect(options[0].querySelector(".suggestion-rest")?.textContent).toBe("hon"); // the part not typed yet
    expect(fetchMock.mock.calls.map((c) => c[0])).toContain("/api/suggest?prefix=pyt");
    expect(box.getAttribute("aria-expanded")).toBe("true");

    fireEvent.keyDown(box, { key: "ArrowDown" });
    expect(options[0].getAttribute("aria-selected")).toBe("true");
    expect(box.getAttribute("aria-activedescendant")).toBe("suggestion-0");
    fireEvent.submit(box.closest("form")!);

    await waitFor(() => expect(window.location.search).toBe("?q=Python"));
    expect(screen.queryByRole("listbox")).toBeNull(); // the list closes after choosing
  });

  it("Escape closes the suggestions", async () => {
    fakeServer({}, ["Python"]);
    render(<App />);
    const box = screen.getByRole("combobox");
    fireEvent.change(box, { target: { value: "py" } });
    await screen.findByRole("listbox");
    fireEvent.keyDown(box, { key: "Escape" });
    expect(screen.queryByRole("listbox")).toBeNull();
  });

  it("offers the corrected query as a link and searches it when clicked", async () => {
    fakeServer({ query: "pyhton", didYouMean: "python", totalMatches: 0, results: [] });
    window.history.replaceState(null, "", "/?q=pyhton");
    render(<App />);

    const link = await screen.findByRole("link", { name: "python" });
    expect(link.getAttribute("href")).toBe("?q=python");
    fireEvent.click(link);
    await waitFor(() => expect(window.location.search).toBe("?q=python"));
  });
});

describe("definition card", () => {
  it("shows the top article's opening above the results, with related articles as Sift searches", async () => {
    const fetchMock = fakeServer({ query: "photosynthesis", definition: card });
    window.history.replaceState(null, "", "/?q=photosynthesis");
    render(<App />);

    const region = await screen.findByRole("region", { name: "Photosynthesis" });
    const title = within(region).getByRole("link", { name: "Photosynthesis" });
    expect(title.getAttribute("href")).toBe("https://simple.wikipedia.org/wiki/Photosynthesis");
    expect(within(region).getByText(card!.text)).toBeTruthy();
    expect(within(region).getByText("simple.wikipedia.org › Photosynthesis")).toBeTruthy();

    // the card comes before the results list in reading (and tab) order
    const results = document.querySelector("ol.results")!;
    expect(region.compareDocumentPosition(results) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();

    const related = within(region).getByRole("list", { name: "Related articles" });
    const links = within(related).getAllByRole("link");
    expect(links.map((a) => [a.textContent, a.getAttribute("href")])).toEqual([
      ["Calvin cycle", "?q=Calvin+cycle"],
      ["Algae", "?q=Algae"],
    ]);

    fireEvent.click(links[0]);
    await waitFor(() => expect(window.location.search).toBe("?q=Calvin+cycle"));
    await waitFor(() => expect(searchCalls(fetchMock).at(-1)).toContain("q=Calvin+cycle"));
  });

  it("keeps the mode when following a related article", async () => {
    fakeServer({ query: "photosynthesis", mode: "keyword", definition: card });
    window.history.replaceState(null, "", "/?q=photosynthesis&mode=keyword");
    render(<App />);
    const region = await screen.findByRole("region", { name: "Photosynthesis" });
    expect(within(region).getByRole("link", { name: "Algae" }).getAttribute("href")).toBe("?q=Algae&mode=keyword");
  });

  it("is not shown when the server sends none, and hides its related heading when the list is empty", async () => {
    fakeServer({ definition: null });
    window.history.replaceState(null, "", "/?q=plants");
    render(<App />);
    await screen.findByRole("link", { name: "Plant" });
    expect(screen.queryByRole("region")).toBeNull();

    cleanup();
    fakeServer({ query: "photosynthesis", definition: { ...card!, related: [] } });
    window.history.replaceState(null, "", "/?q=photosynthesis");
    render(<App />);
    const region = await screen.findByRole("region", { name: "Photosynthesis" });
    expect(within(region).queryByText("Related articles")).toBeNull();
    expect(within(region).queryByRole("list")).toBeNull();
  });
});

describe("address bar state", () => {
  it("reads and writes the search, leaving out defaults", () => {
    expect(readState("?q=world+war+2&mode=smart&page=3")).toEqual({ query: "world war 2", mode: "smart", page: 3 });
    expect(readState("?page=abc&mode=magic")).toEqual({ query: "", mode: "smart", page: 1 });
    expect(readState("?q=x&page=101&mode=keyword")).toEqual({ query: "x", mode: "keyword", page: 1 });
    expect(writeState({ query: "world war 2", mode: "smart", page: 1 })).toBe("?q=world+war+2");
    expect(writeState({ query: "world war 2", mode: "keyword", page: 1 })).toBe("?q=world+war+2&mode=keyword");
    expect(writeState({ query: "", mode: "smart", page: 1 })).toBe("");
  });

  it("back and forward show the earlier search again", async () => {
    fakeServer((url) => ({ query: new URL(url, "http://x").searchParams.get("q")! }));
    window.history.replaceState(null, "", "/?q=plants");
    render(<App />);
    await screen.findByRole("link", { name: "Plant" });
    window.history.pushState(null, "", "/?q=volcano");
    act(() => window.dispatchEvent(new PopStateEvent("popstate")));
    await waitFor(() => expect((screen.getByRole("combobox") as HTMLInputElement).value).toBe("volcano"));
  });
});

describe("source line", () => {
  it("shows the site and the article name, decoded", () => {
    expect(sourceLine("https://simple.wikipedia.org/wiki/World_War_II")).toBe("simple.wikipedia.org › World War II");
    expect(sourceLine("https://simple.wikipedia.org/wiki/C%2B%2B")).toBe("simple.wikipedia.org › C++");
    expect(sourceLine("https://simple.wikipedia.org/wiki/100%_bad")).toBe("simple.wikipedia.org › 100% bad");
    expect(sourceLine("https://example.org/page")).toBe("example.org/page");
  });
});
