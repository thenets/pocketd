/**
 * StatusBar — bottom-anchored single line showing connection info.
 *
 * Layout:
 *   [dot] server:port  |  model: local  |  tokens: 142  |  ctrl+c exit  ctrl+l clear
 *
 * The dot is green when connected, red when disconnected, yellow when checking.
 * All text is dim except the dot.
 */

import React from "react";
import { Box, Text } from "ink";
import { theme, chars } from "../utils/theme.js";
import type { ServerStatus } from "../hooks/useServerStatus.js";

interface StatusBarProps {
  serverUrl: string;
  status: ServerStatus;
  totalTokens: number;
  isStreaming: boolean;
}

export function StatusBar({
  serverUrl,
  status,
  totalTokens,
  isStreaming,
}: StatusBarProps) {
  const dotColor = status.checking
    ? "yellow"
    : status.connected
      ? "green"
      : "red";

  const statusLabel = status.checking
    ? "checking"
    : status.connected
      ? "connected"
      : "disconnected";

  const sep = ` ${chars.dot} `;

  return (
    <Box flexDirection="row" paddingX={1}>
      <Text color={dotColor} bold>{chars.bullet}</Text>
      <Text color={theme.dimText}> {statusLabel}</Text>
      <Text color={theme.dimText}>{sep}</Text>
      <Text color={theme.dimText}>{serverUrl}</Text>
      <Text color={theme.dimText}>{sep}</Text>
      <Text color={theme.dimText}>model: </Text>
      <Text color={theme.accent}>{status.modelName}</Text>
      <Text color={theme.dimText}>{sep}</Text>
      <Text color={theme.dimText}>tokens: </Text>
      <Text color={theme.accent}>{totalTokens}</Text>
      {isStreaming && (
        <>
          <Text color={theme.dimText}>{sep}</Text>
          <Text color="yellow">streaming...</Text>
        </>
      )}
      <Box flexGrow={1} />
      <Text color={theme.dimText} italic>
        ctrl+c exit{sep}ctrl+l clear
      </Text>
    </Box>
  );
}
