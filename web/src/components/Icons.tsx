/**
 * The four icons the site needs, drawn by hand as inline SVG (no icon library).
 * They use currentColor, so they follow the text colour in light and dark mode. aria-hidden: the button or
 * text next to each icon already says what it does.
 */

import type { ReactNode } from "react";

interface Props {
  small?: boolean;
  className?: string;
}

function Svg({ small, className, children }: Props & { children: ReactNode }) {
  return (
    <svg
      className={["icon", small && "icon-small", className].filter(Boolean).join(" ")}
      viewBox="0 0 24 24"
      aria-hidden="true"
      focusable="false"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      {children}
    </svg>
  );
}

/** A magnifying glass: a circle and a short handle. */
export function SearchIcon(props: Props) {
  return (
    <Svg {...props}>
      <circle cx="10.5" cy="10.5" r="6.5" />
      <path d="M15.5 15.5 20 20" />
    </Svg>
  );
}

/** ">" — Next page, and the Details arrow (turned 90° when open). */
export function ChevronRightIcon(props: Props) {
  return (
    <Svg {...props}>
      <path d="m9 6 6 6-6 6" />
    </Svg>
  );
}

/** "<" — Previous page. */
export function ChevronLeftIcon(props: Props) {
  return (
    <Svg {...props}>
      <path d="m15 6-6 6 6 6" />
    </Svg>
  );
}

/** A circle with "!" for errors. */
export function AlertIcon(props: Props) {
  return (
    <Svg {...props}>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 7.5v5.5" />
      <path d="M12 16.5h.01" />
    </Svg>
  );
}
