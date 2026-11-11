import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, search, searchUrl } from "./api";

/** Replaces the browser's fetch with a fake that answers with the given status and body. */
function fakeFetch(status: number, body: unknown) {
  const fetchMock = vi.fn(async () => new Response(typeof body === "string" ? body : JSON.stringify(body), { status }));
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

afterEach(() => vi.unstubAllGlobals());

describe("searchUrl", () => {
  it("escapes spaces, symbols and emoji", () => {
    expect(searchUrl("world war 2", "keyword", 1)).toBe("/api/search?q=world+war+2&mode=keyword&page=1");
    expect(searchUrl("a&b=c 🙂", "smart", 3)).toBe("/api/search?q=a%26b%3Dc+%F0%9F%99%82&mode=smart&page=3");
  });
});

describe("search", () => {
  it("returns the parsed answer", async () => {
    const answer = { query: "volcano", totalMatches: 1, results: [] };
    const fetchMock = fakeFetch(200, answer);
    await expect(search("volcano", "keyword", 2)).resolves.toEqual(answer);
    expect(fetchMock).toHaveBeenCalledWith("/api/search?q=volcano&mode=keyword&page=2", { signal: undefined });
  });

  it("turns the server's error JSON into an ApiError with its message", async () => {
    fakeFetch(400, { error: { code: "QUERY_TOO_LONG", message: "Your search is too long." } });
    const error = await search("x", "keyword", 1).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ code: "QUERY_TOO_LONG", message: "Your search is too long.", status: 400 });
  });

  it("gives a friendly message when the server cannot be reached", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => {
        throw new TypeError("Failed to fetch");
      }),
    );
    await expect(search("x", "keyword", 1)).rejects.toMatchObject({ code: "NETWORK" });
  });

  it("gives a friendly message when the answer is not JSON", async () => {
    fakeFetch(502, "<html>Bad Gateway</html>");
    await expect(search("x", "keyword", 1)).rejects.toMatchObject({ code: "BAD_RESPONSE", status: 502 });
  });

  it("lets a cancelled request through as AbortError so the caller can ignore it", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => {
        throw new DOMException("Aborted", "AbortError");
      }),
    );
    await expect(search("x", "keyword", 1)).rejects.toMatchObject({ name: "AbortError" });
  });
});
