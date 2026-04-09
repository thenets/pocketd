/**
 * MessageList — scrollable container for all chat messages.
 *
 * Ink does not have native scroll. We implement a "tail" view:
 * only render the last N messages that fit the viewport.
 * The user always sees the most recent content.
 *
 * When there are no messages, show a welcome/placeholder.
 */

import React from "react";
import { Box, Text } from "ink";
import { theme, chars } from "../utils/theme.js";
import { MessageView } from "./MessageView.js";
import type { DisplayMessage } from "../hooks/useChat.js";

interface MessageListProps {
  messages: DisplayMessage[];
  maxVisible?: number;
}

export function MessageList({
  messages,
  maxVisible = 50,
}: MessageListProps) {
  if (messages.length === 0) {
    return (
      <Box
        flexDirection="column"
        alignItems="center"
        justifyContent="center"
        flexGrow={1}
        padding={2}
      >
        <Text color={theme.accent} bold>
          pocketd
        </Text>
        <Text color={theme.dimText}> </Text>
        <Text color={theme.dimText}>
          Local LLM chat {chars.dot} on-device inference
        </Text>
        <Text color={theme.dimText}> </Text>
        <Text color={theme.dimText} italic>
          Type a message below to begin.
        </Text>
      </Box>
    );
  }

  // Tail: show only the last maxVisible messages
  const visible = messages.slice(-maxVisible);
  const hiddenCount = messages.length - visible.length;

  return (
    <Box flexDirection="column" flexGrow={1} paddingTop={1}>
      {hiddenCount > 0 && (
        <Box paddingLeft={2} marginBottom={1}>
          <Text color={theme.dimText} italic>
            {chars.ellipsis} {hiddenCount} earlier message
            {hiddenCount > 1 ? "s" : ""} hidden
          </Text>
        </Box>
      )}
      {visible.map((msg) => (
        <MessageView key={msg.id} message={msg} />
      ))}
    </Box>
  );
}
