/**
 * MessageView — renders a single chat message with role-specific styling.
 *
 * User messages:
 *   > This is what the user typed
 *   Prefix ">" in blue-bright, text in white, slight left padding.
 *
 * Assistant messages:
 *   Text in white, left border accent in cyan (using a Box borderLeft),
 *   or a simple "  " indent with a cyan prefix character.
 *   While streaming, show a blinking cursor character at the end.
 *
 * System/error messages:
 *   Prefixed with a colored label, text in the corresponding color.
 */

import React from "react";
import { Box, Text } from "ink";
import { theme, chars } from "../utils/theme.js";
import type { DisplayMessage } from "../hooks/useChat.js";

interface MessageViewProps {
  message: DisplayMessage;
}

export function MessageView({ message }: MessageViewProps) {
  const { role, content, isStreaming } = message;

  if (role === "user") {
    return (
      <Box paddingLeft={1} marginBottom={1}>
        <Text color={theme.userPrefix} bold>
          {chars.arrow}{" "}
        </Text>
        <Text color={theme.userText}>{content}</Text>
      </Box>
    );
  }

  if (role === "assistant") {
    const displayContent = content || (isStreaming ? "" : "(empty response)");
    const cursor = isStreaming ? "\u2588" : ""; // Block cursor while streaming

    return (
      <Box
        paddingLeft={2}
        marginBottom={1}
        borderStyle="single"
        borderLeft
        borderRight={false}
        borderTop={false}
        borderBottom={false}
        borderColor={isStreaming ? "yellow" : theme.accent}
      >
        <Box flexDirection="column">
          <Text color={theme.aiText}>
            {displayContent}
            {isStreaming && (
              <Text color="yellow">{cursor}</Text>
            )}
          </Text>
        </Box>
      </Box>
    );
  }

  // system messages
  return (
    <Box paddingLeft={1} marginBottom={1}>
      <Text color={theme.systemPrefix} bold>
        system:{" "}
      </Text>
      <Text color={theme.systemText}>{content}</Text>
    </Box>
  );
}
