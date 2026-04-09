#!/usr/bin/env node

/**
 * pocketd-tui — Terminal UI chat client for pocketd local LLM server.
 *
 * Usage:
 *   pocketd-tui [--server URL] [--model NAME] [--token TOKEN]
 *
 * Defaults:
 *   --server  http://localhost:8080
 *   --model   local
 */

import React from "react";
import { withFullScreen } from "fullscreen-ink";
import { App } from "./components/App.js";

// ── Parse CLI arguments ──────────────────────────────────────────────────

function parseArgs(argv: string[]): {
  server: string;
  model: string;
  token?: string;
} {
  const args = argv.slice(2); // skip node + script path
  let server = process.env["POCKETD_URL"] ?? "http://localhost:8080";
  let model = process.env["POCKETD_MODEL"] ?? "local";
  let token = process.env["POCKETD_TOKEN"];

  for (let i = 0; i < args.length; i++) {
    const arg = args[i];
    const next = args[i + 1];
    if ((arg === "--server" || arg === "-s") && next) {
      server = next;
      i++;
    } else if ((arg === "--model" || arg === "-m") && next) {
      model = next;
      i++;
    } else if ((arg === "--token" || arg === "-t") && next) {
      token = next;
      i++;
    } else if (arg === "--help" || arg === "-h") {
      console.log(`
pocketd-tui — Terminal UI chat client for pocketd

Usage:
  pocketd-tui [options]

Options:
  -s, --server URL    Server URL (default: http://localhost:8080)
  -m, --model NAME    Model name (default: local)
  -t, --token TOKEN   Bearer auth token
  -h, --help          Show this help

Environment variables:
  POCKETD_URL         Server URL
  POCKETD_MODEL       Model name
  POCKETD_TOKEN       Bearer token
`);
      process.exit(0);
    }
  }

  return { server, model, token };
}

// ── Main ─────────────────────────────────────────────────────────────────

const config = parseArgs(process.argv);

withFullScreen(
  <App
    serverUrl={config.server}
    model={config.model}
    bearerToken={config.token}
  />,
).start();
