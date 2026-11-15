import { describe, expect, it } from "vitest";
import styles from "./styles.css?raw";
import tokens from "./tokens.css?raw";

// Every component's source code, to check that each CSS class is really used somewhere.
const sources = Object.values(
  import.meta.glob(["./**/*.tsx", "!./**/*.test.tsx"], { query: "?raw", import: "default", eager: true }),
) as string[];

const withoutComments = (css: string) => css.replace(/\/\*[\s\S]*?\*\//g, "");

/** All "property: value;" pairs, with the @media conditions removed (CSS does not allow var() there). */
function declarations(css: string): { property: string; value: string }[] {
  const body = withoutComments(css).replace(/@media[^{]+\{/g, "{");
  return [...body.matchAll(/([a-z-]+)\s*:\s*([^;{}]+);/g)].map((m) => ({ property: m[1], value: m[2].trim() }));
}

/**
 * What is left of a value once the var(--...) parts are taken out must be plain words (auto, none, grid...),
 * or the small numbers used for arithmetic and fractions: 0, 1, -1, 2, 1fr.
 */
function rawValues(value: string): string[] {
  const rest = value.replace(/var\(--[a-z0-9-]+\)/g, " ");
  const problems: string[] = [];
  if (/#[0-9a-fA-F]{3,8}\b/.test(rest)) problems.push("hex colour");
  if (/\b(rgb|rgba|hsl|hsla|oklch|color-mix)\(/.test(rest)) problems.push("colour function");
  if (/["']/.test(rest) || /\b[A-Z]/.test(rest)) problems.push("font name or string");
  for (const n of rest.match(/-?\d*\.?\d+[a-z%]*/g) ?? []) {
    if (!["0", "1", "-1", "2", "1fr"].includes(n)) problems.push(n);
  }
  if (/\b(white|black|red|blue|green|gray|grey|yellow|orange)\b/.test(rest)) problems.push("named colour");
  return problems;
}

describe("design tokens", () => {
  it("every value in styles.css comes from a token", () => {
    const bad = declarations(styles)
      .map((d) => ({ ...d, problems: rawValues(d.value) }))
      .filter((d) => d.problems.length > 0)
      .map((d) => `${d.property}: ${d.value} (${d.problems.join(", ")})`);
    expect(bad).toEqual([]);
  });

  it("the only @media rules are the breakpoint and the system settings", () => {
    const media = [...withoutComments(styles + tokens).matchAll(/@media([^{]+)\{/g)].map((m) => m[1].trim());
    for (const query of media) {
      expect(["(max-width: 47.99rem)", "(prefers-reduced-motion: reduce)", "(prefers-color-scheme: dark)"]).toContain(query);
    }
  });

  it("every token used is defined, and every token defined is used", () => {
    const defined = new Set([...withoutComments(tokens).matchAll(/(--[a-z0-9-]+)\s*:/g)].map((m) => m[1]));
    const used = new Set([...withoutComments(styles).matchAll(/var\((--[a-z0-9-]+)\)/g)].map((m) => m[1]));
    expect([...used].filter((t) => !defined.has(t))).toEqual([]);
    expect([...defined].filter((t) => !used.has(t))).toEqual([]);
  });

  it("dark mode only changes colours", () => {
    const dark = /@media \(prefers-color-scheme: dark\)\s*\{([\s\S]*)\}\s*$/.exec(withoutComments(tokens))![1];
    const names = [...dark.matchAll(/(--[a-z0-9-]+)\s*:/g)].map((m) => m[1]);
    expect(names.length).toBeGreaterThan(0);
    expect(names.filter((n) => !n.startsWith("--color-"))).toEqual([]);
  });

  it("at most two font families for text, plus the monospace used only by the Details list", () => {
    const fonts = [...withoutComments(tokens).matchAll(/(--font-[a-z]+)\s*:/g)].map((m) => m[1]);
    expect(fonts).toEqual(["--font-serif", "--font-sans", "--font-mono"]);
    const monoUsers = declarations(styles).filter((d) => d.value.includes("var(--font-mono)"));
    expect(monoUsers).toHaveLength(1);
    expect(withoutComments(styles)).toMatch(/\.details dd \{[^}]*var\(--font-mono\)/);
  });
});

describe("no unused CSS", () => {
  it("every class in styles.css is used by a component", () => {
    const classes = new Set([...withoutComments(styles).matchAll(/\.([a-z][a-z0-9-]*)/g)].map((m) => m[1]));
    const unused = [...classes].filter((c) => !sources.some((s) => new RegExp(`["\\s]${c}["\\s]`).test(s)));
    expect(unused).toEqual([]);
  });
});
