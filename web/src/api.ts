import type { Mode, SearchResponse, SuggestResponse } from "./types";

/** Where the API lives. "/api" is forwarded to the Spring server by Vite (see vite.config.ts). */
export const API_BASE: string = import.meta.env.VITE_API_BASE ?? "/api";

/** An error we can show to the user as it is. `code` is the server's error code, or NETWORK / BAD_RESPONSE. */
export class ApiError extends Error {
  constructor(
    public readonly code: string,
    message: string,
    public readonly status: number = 0,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

/** Builds the address of a search request. URLSearchParams escapes spaces, &, emoji and so on for us. */
export function searchUrl(query: string, mode: Mode, page: number): string {
  const params = new URLSearchParams({ q: query, mode, page: String(page) });
  return `${API_BASE}/search?${params}`;
}

/**
 * Asks the API for one page of results.
 * `signal` lets the caller cancel the request (when the user has already started a newer search).
 * Throws ApiError with a friendly message; a cancelled request throws the browser's AbortError instead.
 */
export async function search(query: string, mode: Mode, page: number, signal?: AbortSignal): Promise<SearchResponse> {
  let response: Response;
  try {
    response = await fetch(searchUrl(query, mode, page), { signal });
  } catch (cause) {
    if (cause instanceof DOMException && cause.name === "AbortError") {
      throw cause;
    }
    throw new ApiError("NETWORK", "Cannot reach the search server. Check that it is running, then try again.");
  }

  let body: unknown;
  try {
    body = await response.json();
  } catch {
    throw new ApiError("BAD_RESPONSE", "The search server sent an answer we could not read.", response.status);
  }

  if (!response.ok) {
    // The server's errors look like {"error": {"code": "...", "message": "..."}}
    const error = (body as { error?: { code?: string; message?: string } }).error;
    throw new ApiError(error?.code ?? "HTTP_" + response.status, error?.message ?? "The search failed.", response.status);
  }
  return body as SearchResponse;
}

/**
 * Asks the API for up to 5 titles that start with what the user has typed so far.
 * Suggestions are a nice extra, so any failure simply gives an empty list (a cancelled request still throws).
 */
export async function suggest(prefix: string, signal?: AbortSignal): Promise<string[]> {
  try {
    const response = await fetch(`${API_BASE}/suggest?${new URLSearchParams({ prefix })}`, { signal });
    if (!response.ok) return [];
    const body = (await response.json()) as SuggestResponse;
    return Array.isArray(body.suggestions) ? body.suggestions : [];
  } catch (cause) {
    if (cause instanceof DOMException && cause.name === "AbortError") throw cause;
    return [];
  }
}
