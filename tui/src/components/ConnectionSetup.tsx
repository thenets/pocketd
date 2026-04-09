/**
 * ConnectionSetup — shown when the server is unreachable.
 *
 * Flow:
 *   1. Auto-test the initial URL
 *   2. If it fails and there are saved URLs, show a Select list
 *   3. Always allow typing a new URL manually
 *   4. On success, save the URL to history and proceed
 */

import React, { useState, useCallback } from "react";
import { Box, Text } from "ink";
import { TextInput, Spinner, Select } from "@inkjs/ui";
import { theme, chars } from "../utils/theme.js";
import { PocketdClient } from "../utils/api.js";
import { loadServerHistory, saveServerUrl } from "../utils/serverHistory.js";

interface ConnectionSetupProps {
  initialUrl: string;
  bearerToken?: string;
  onConnected: (url: string, client: PocketdClient) => void;
}

type TestState =
  | { phase: "idle" }
  | { phase: "testing"; url: string }
  | { phase: "success"; url: string; modelName: string }
  | { phase: "failed"; url: string; error: string };

type InputMode = "select" | "manual";

export function ConnectionSetup({
  initialUrl,
  bearerToken,
  onConnected,
}: ConnectionSetupProps) {
  const [inputKey, setInputKey] = useState(0);
  const [testState, setTestState] = useState<TestState>({ phase: "idle" });
  const [inputMode, setInputMode] = useState<InputMode>("select");
  const [history] = useState(() => loadServerHistory());

  const testConnection = useCallback(
    async (url: string) => {
      const trimmed = url.trim();
      if (!trimmed) return;

      setTestState({ phase: "testing", url: trimmed });

      const client = new PocketdClient(trimmed, bearerToken);
      try {
        const healthy = await client.isHealthy();
        if (!healthy) {
          setTestState({ phase: "failed", url: trimmed, error: "Server returned non-200 response" });
          return;
        }
        let modelName = "local";
        try {
          const models = await client.listModels();
          if (models.length > 0) modelName = models[0]!.id;
        } catch { /* keep default */ }

        setTestState({ phase: "success", url: trimmed, modelName });
        saveServerUrl(trimmed);

        setTimeout(() => onConnected(trimmed, client), 800);
      } catch (err) {
        const msg = err instanceof Error ? err.message : "Connection failed";
        setTestState({ phase: "failed", url: trimmed, error: msg });
      }
    },
    [bearerToken, onConnected],
  );

  const handleManualSubmit = useCallback(
    (url: string) => {
      if (testState.phase === "testing") return;
      testConnection(url);
      setInputKey((k) => k + 1);
    },
    [testConnection, testState],
  );

  const handleSelectUrl = useCallback(
    (url: string) => {
      if (url === "__manual__") {
        setInputMode("manual");
        return;
      }
      testConnection(url);
    },
    [testConnection],
  );

  // Auto-test the initial URL on first render
  React.useEffect(() => {
    testConnection(initialUrl);
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // Build select options from history (excluding initialUrl which was already tried)
  const historyOptions = history
    .filter((u) => u !== initialUrl)
    .map((u) => ({ label: u, value: u }));

  const selectOptions = [
    ...historyOptions,
    { label: "Enter a new URL...", value: "__manual__" },
  ];

  const showSelector =
    testState.phase === "failed" &&
    inputMode === "select" &&
    historyOptions.length > 0;

  const showManualInput =
    testState.phase === "failed" &&
    (inputMode === "manual" || historyOptions.length === 0);

  return (
    <Box flexDirection="column" alignItems="center" justifyContent="center" flexGrow={1} padding={2}>
      <Text color={theme.accent} bold>pocketd</Text>
      <Text color={theme.dimText}> </Text>
      <Text color={theme.dimText}>Local LLM chat {chars.dot} on-device inference</Text>
      <Text color={theme.dimText}> </Text>

      {/* Status */}
      {testState.phase === "idle" && (
        <Text color={theme.dimText}>Checking server connection...</Text>
      )}
      {testState.phase === "testing" && (
        <Box>
          <Spinner label={`Testing ${testState.url}...`} />
        </Box>
      )}
      {testState.phase === "success" && (
        <Box flexDirection="column" alignItems="center">
          <Text color="green" bold>Connected to {testState.url}</Text>
          <Text color={theme.dimText}>Model: {testState.modelName}</Text>
        </Box>
      )}
      {testState.phase === "failed" && (
        <Box flexDirection="column" alignItems="center">
          <Text color="red" bold>Cannot connect to {testState.url}</Text>
          <Text color="red">{testState.error}</Text>
        </Box>
      )}

      {/* Server selector from history */}
      {showSelector && (
        <Box flexDirection="column" alignItems="center" marginTop={1}>
          <Text color={theme.dimText}>Select a server or enter a new URL:</Text>
          <Box marginTop={1}>
            <Select
              options={selectOptions}
              onChange={handleSelectUrl}
            />
          </Box>
        </Box>
      )}

      {/* Manual URL input */}
      {showManualInput && (
        <Box flexDirection="column" alignItems="center" marginTop={1}>
          <Text color={theme.dimText}>Enter a server URL:</Text>
          <Box marginTop={1} flexDirection="row">
            <Text color={theme.accent} bold>{chars.arrow} </Text>
            <TextInput
              key={inputKey}
              placeholder={testState.phase === "failed" ? testState.url : initialUrl}
              onSubmit={handleManualSubmit}
            />
          </Box>
          {historyOptions.length > 0 && (
            <Box marginTop={1}>
              <Text
                color={theme.dimText}
                italic
              >
                (press Esc to go back to saved servers)
              </Text>
            </Box>
          )}
        </Box>
      )}

      <Text color={theme.dimText}> </Text>
      <Text color={theme.dimText} italic>Press Ctrl+C to exit</Text>
    </Box>
  );
}
