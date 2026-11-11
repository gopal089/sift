import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { App } from "./App";
import { readState, writeState } from "./urlState";

const answer = {
  query: "plants",
  mode: "keyword",
  modeUsed: "keyword",
  notice: null,
  didYouMean: null,
  page: 1,
  pageSize: 10,
  totalMatches: 25,
  hasMore: true,
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
  stats: { tookMs: 0.42, documentsSearched: 280748, totalMatches: 25, terms: ["plant"], matchMode: "AND", cache: "off" },
};

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  window.history.replaceState(null, "", "/");
});

describe("App", () => {
  it("searches, shows bold query words, stats and the address bar state", async () => {
    const fetchMock = vi.fn(async (_url: string) => new Response(JSON.stringify(answer), { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);
    vi.stubGlobal("scrollTo", vi.fn());
    render(<App />);

    fireEvent.change(screen.getByLabelText("Search Simple English Wikipedia"), { target: { value: " plants " } });
    fireEvent.click(screen.getByRole("button", { name: "Search" }));

    await screen.findByRole("link", { name: "Plant" });
    expect(fetchMock.mock.calls[0][0]).toBe("/api/search?q=plants&mode=keyword&page=1");
    expect(window.location.search).toBe("?q=plants");
    expect(screen.getByRole("status").textContent).toContain("25 results for “plants”");
    expect(screen.getByText("Plants").tagName).toBe("STRONG");
    // HTML in a snippet is shown as text, never run
    expect(screen.getByText("<b>make</b> food.", { exact: false })).toBeTruthy();
    expect(document.querySelector(".result-snippet b")).toBeNull();
    expect(screen.getByText("280,748")).toBeTruthy();
    expect(screen.getByText("0.42 ms")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "Next →" }));
    await waitFor(() => expect(window.location.search).toBe("?q=plants&page=2"));
  });

  it("shows the server's message and a retry button when the search fails", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response(JSON.stringify({ error: { code: "X", message: "Server is busy." } }), { status: 500 })),
    );
    window.history.replaceState(null, "", "/?q=plants");
    render(<App />);
    const alert = await screen.findByRole("alert");
    expect(alert.textContent).toContain("Server is busy.");
    expect(screen.getByRole("button", { name: "Try again" })).toBeTruthy();
  });
});

describe("autocomplete and did you mean", () => {
  it("shows suggestions while typing; arrow down + Enter searches the chosen title", async () => {
    const fetchMock = vi.fn(async (url: string) =>
      url.startsWith("/api/suggest")
        ? new Response(JSON.stringify({ prefix: "pyt", suggestions: ["Python", "Pythagoras"] }), { status: 200 })
        : new Response(JSON.stringify({ ...answer, query: "Python" }), { status: 200 }),
    );
    vi.stubGlobal("fetch", fetchMock);
    vi.stubGlobal("scrollTo", vi.fn());
    render(<App />);

    const box = screen.getByRole("combobox");
    fireEvent.change(box, { target: { value: "pyt" } });
    const options = await screen.findAllByRole("option");
    expect(options.map((o) => o.textContent)).toEqual(["Python", "Pythagoras"]);
    expect(fetchMock.mock.calls[0][0]).toBe("/api/suggest?prefix=pyt");
    expect(box.getAttribute("aria-expanded")).toBe("true");

    fireEvent.keyDown(box, { key: "ArrowDown" });
    expect(options[0].getAttribute("aria-selected")).toBe("true");
    fireEvent.submit(box.closest("form")!);

    await waitFor(() => expect(window.location.search).toBe("?q=Python"));
    expect(screen.queryByRole("listbox")).toBeNull(); // the list closes after choosing
  });

  it("offers the corrected query and searches it when clicked", async () => {
    const fetchMock = vi.fn(async (_url: string) =>
      new Response(JSON.stringify({ ...answer, query: "pyhton", didYouMean: "python", totalMatches: 0, results: [] }), {
        status: 200,
      }),
    );
    vi.stubGlobal("fetch", fetchMock);
    vi.stubGlobal("scrollTo", vi.fn());
    window.history.replaceState(null, "", "/?q=pyhton");
    render(<App />);

    fireEvent.click(await screen.findByRole("button", { name: "python" }));
    await waitFor(() => expect(window.location.search).toBe("?q=python"));
  });
});

describe("address bar state", () => {
  it("reads and writes the search, leaving out defaults", () => {
    expect(readState("?q=world+war+2&mode=smart&page=3")).toEqual({ query: "world war 2", mode: "smart", page: 3 });
    expect(readState("?page=abc&mode=magic")).toEqual({ query: "", mode: "keyword", page: 1 });
    expect(readState("?q=x&page=101")).toEqual({ query: "x", mode: "keyword", page: 1 });
    expect(writeState({ query: "world war 2", mode: "keyword", page: 1 })).toBe("?q=world+war+2");
    expect(writeState({ query: "", mode: "keyword", page: 1 })).toBe("");
  });
});
