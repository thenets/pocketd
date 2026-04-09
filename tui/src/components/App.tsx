/**
 * App — root component that composes the full TUI layout.
 *
 * On startup, checks server connectivity. If unreachable, shows a
 * ConnectionSetup screen where the user can enter a different URL.
 * Once connected, shows the chat interface.
 *
 * Keyboard shortcuts:
 *   Ctrl+C — exit
 *   Ctrl+L — clear conversation
 */

import React, { useState, useCallback } from "react";
import { Box, Text, useInput } from "ink";
import { FullScreenBox, useScreenSize } from "fullscreen-ink";
import { PocketdClient } from "../utils/api.js";
import { theme, chars } from "../utils/theme.js";
import { useChat } from "../hooks/useChat.js";
import { useServerStatus } from "../hooks/useServerStatus.js";
import { Header } from "./Header.js";
import { MessageList } from "./MessageList.js";
import { ChatInput } from "./ChatInput.js";
import { StatusBar } from "./StatusBar.js";
import { ErrorBanner } from "./ErrorBanner.js";
import { ConnectionSetup } from "./ConnectionSetup.js";

interface AppProps {
  serverUrl: string;
  bearerToken?: string;
  model: string;
}

export function App({ serverUrl, bearerToken, model }: AppProps) {
  const [connectedUrl, setConnectedUrl] = useState<string | null>(null);
  const [client, setClient] = useState<PocketdClient | null>(null);

  const handleConnected = useCallback((url: string, c: PocketdClient) => {
    setConnectedUrl(url);
    setClient(c);
  }, []);

  if (!connectedUrl || !client) {
    return (
      <FullScreenBox flexDirection="column">
        <ConnectionSetup
          initialUrl={serverUrl}
          bearerToken={bearerToken}
          onConnected={handleConnected}
        />
      </FullScreenBox>
    );
  }

  return (
    <FullScreenBox flexDirection="column">
      <ChatScreen
        client={client}
        serverUrl={connectedUrl}
        model={model}
      />
    </FullScreenBox>
  );
}

// ── Chat screen (shown after successful connection) ───────────────────────

interface ChatScreenProps {
  client: PocketdClient;
  serverUrl: string;
  model: string;
}

function ChatScreen({ client, serverUrl, model }: ChatScreenProps) {
  const { width } = useScreenSize();

  const { messages, isStreaming, error, sendMessage, clearMessages, totalTokens } =
    useChat(client, model);
  const status = useServerStatus(client);

  // Global keyboard shortcuts
  useInput((_input, key) => {
    if (key.ctrl && _input === "l") {
      clearMessages();
    }
  });

  const sepWidth = Math.max(width - 2, 20);

  return (
    <>
      <Header width={sepWidth} />

      <Box flexDirection="column" flexGrow={1}>
        <MessageList messages={messages} />
      </Box>

      <Box paddingX={1}>
        <Text color={theme.dimText}>
          {chars.horizontalLine.repeat(sepWidth)}
        </Text>
      </Box>

      {error && <ErrorBanner message={error} />}

      <ChatInput isStreaming={isStreaming} onSubmit={sendMessage} />

      <Box paddingX={1}>
        <Text color={theme.dimText}>
          {chars.horizontalLine.repeat(sepWidth)}
        </Text>
      </Box>

      <StatusBar
        serverUrl={serverUrl}
        status={status}
        totalTokens={totalTokens}
        isStreaming={isStreaming}
      />
    </>
  );
}
