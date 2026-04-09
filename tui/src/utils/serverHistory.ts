/**
 * Persists recently used server URLs to ~/.config/pocketd/servers.json.
 * Keeps the last 10 unique URLs, most recent first.
 */

import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { join } from "node:path";
import { homedir } from "node:os";

const CONFIG_DIR = join(homedir(), ".config", "pocketd");
const HISTORY_FILE = join(CONFIG_DIR, "servers.json");
const MAX_ENTRIES = 10;

export function loadServerHistory(): string[] {
  try {
    const data = readFileSync(HISTORY_FILE, "utf-8");
    const parsed = JSON.parse(data);
    if (Array.isArray(parsed)) return parsed.filter((s) => typeof s === "string");
  } catch {
    // File doesn't exist or is malformed — return empty
  }
  return [];
}

export function saveServerUrl(url: string): void {
  const history = loadServerHistory().filter((u) => u !== url);
  history.unshift(url); // most recent first
  const trimmed = history.slice(0, MAX_ENTRIES);

  try {
    mkdirSync(CONFIG_DIR, { recursive: true });
    writeFileSync(HISTORY_FILE, JSON.stringify(trimmed, null, 2) + "\n");
  } catch {
    // Best-effort — don't crash if we can't write
  }
}
