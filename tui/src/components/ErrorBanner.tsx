/**
 * ErrorBanner — shown inline when an API error occurs.
 * Dismisses automatically on next send, or can be cleared.
 */

import React from "react";
import { Box, Text } from "ink";
import { theme } from "../utils/theme.js";

interface ErrorBannerProps {
  message: string;
}

export function ErrorBanner({ message }: ErrorBannerProps) {
  return (
    <Box paddingX={2} paddingY={0}>
      <Text color={theme.errorText} bold>
        error:{" "}
      </Text>
      <Text color={theme.errorText}>{message}</Text>
    </Box>
  );
}
