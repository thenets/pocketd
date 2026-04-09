/**
 * Header — minimal top bar with app name and a thin separator.
 */

import React from "react";
import { Box, Text } from "ink";
import { theme, chars } from "../utils/theme.js";

interface HeaderProps {
  width?: number;
}

export function Header({ width = 60 }: HeaderProps) {
  return (
    <Box flexDirection="column">
      <Box paddingX={1} paddingTop={0} flexDirection="row">
        <Text color={theme.accent} bold>
          pocketd
        </Text>
        <Text color={theme.dimText}> {chars.dot} local LLM chat</Text>
      </Box>
      <Box paddingX={1}>
        <Text color={theme.dimText}>
          {chars.horizontalLine.repeat(width)}
        </Text>
      </Box>
    </Box>
  );
}
