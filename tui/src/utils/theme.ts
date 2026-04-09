/**
 * pocketd TUI color palette and style constants.
 *
 * Design philosophy: monochrome base with a single accent color (cyan).
 * Inspired by Claude Code CLI / Codex CLI — minimal chrome, clear hierarchy,
 * generous use of dim text, bold for emphasis only.
 */

export const theme = {
  // ── Accent ──────────────────────────────────────────────────────────────
  accent: "cyan",
  accentBright: "cyanBright",

  // ── Text hierarchy ──────────────────────────────────────────────────────
  userText: "white",
  aiText: "white",
  systemText: "yellow",
  errorText: "red",
  dimText: "gray",
  mutedText: "gray",

  // ── Structural ─────────────────────────────────────────────────────────
  border: "gray",
  borderFocused: "cyan",
  separator: "gray",

  // ── Message prefixes ──────────────────────────────────────────────────
  userPrefix: "blueBright",
  aiPrefix: "cyan",
  systemPrefix: "yellow",

  // ── Status bar ─────────────────────────────────────────────────────────
  statusBg: "gray",
  statusText: "white",

  // ── Input ──────────────────────────────────────────────────────────────
  promptChar: ">",
  promptCharStreaming: "...",
  inputPlaceholder: "Send a message...",
} as const;

/**
 * Unicode box-drawing characters for the thin separator line.
 * We use a simple horizontal rule rather than heavy borders.
 */
export const chars = {
  horizontalLine: "\u2500", // ─
  dot: "\u00B7",            // ·
  bullet: "\u2022",         // •
  ellipsis: "\u2026",       // ...
  arrow: "\u276F",          // ❯
  thinArrow: "\u203A",      // >
} as const;
