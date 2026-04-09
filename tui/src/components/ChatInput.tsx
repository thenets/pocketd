/**
 * ChatInput — the bottom input area.
 *
 * Shows a prompt character ">" followed by a text input field.
 * When the AI is streaming, the input is disabled and shows a spinner.
 * The user presses Enter to submit.
 */

import React, { useState, useCallback } from "react";
import { Box, Text } from "ink";
import { TextInput, Spinner } from "@inkjs/ui";
import { theme, chars } from "../utils/theme.js";

interface ChatInputProps {
  isStreaming: boolean;
  onSubmit: (message: string) => void;
}

export function ChatInput({ isStreaming, onSubmit }: ChatInputProps) {
  // TextInput from @inkjs/ui is uncontrolled.
  // To clear after submit we remount it by bumping the key.
  const [inputKey, setInputKey] = useState(0);

  const handleSubmit = useCallback(
    (text: string) => {
      if (!text.trim() || isStreaming) return;
      onSubmit(text);
      setInputKey((k) => k + 1); // remount to clear
    },
    [isStreaming, onSubmit],
  );

  if (isStreaming) {
    return (
      <Box paddingX={1} paddingY={0}>
        <Box>
          <Spinner label="Generating..." />
        </Box>
      </Box>
    );
  }

  return (
    <Box paddingX={1} paddingY={0} flexDirection="row">
      <Text color={theme.accent} bold>
        {chars.arrow}{" "}
      </Text>
      <Box flexGrow={1}>
        <TextInput
          key={inputKey}
          placeholder={theme.inputPlaceholder}
          onSubmit={handleSubmit}
        />
      </Box>
    </Box>
  );
}
