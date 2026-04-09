/**
 * Core chat state hook.
 * Manages message history, streaming state, and API communication.
 */

import { useState, useCallback, useRef } from "react";
import { PocketdClient, type ChatMessage } from "../utils/api.js";

export type MessageRole = "user" | "assistant" | "system";

export interface DisplayMessage {
  id: string;
  role: MessageRole;
  content: string;
  timestamp: Date;
  isStreaming?: boolean;
  tokenCount?: number;
}

export interface UseChatReturn {
  messages: DisplayMessage[];
  isStreaming: boolean;
  error: string | null;
  sendMessage: (content: string) => void;
  clearMessages: () => void;
  totalTokens: number;
}

let messageIdCounter = 0;
function nextId(): string {
  return `msg-${++messageIdCounter}-${Date.now()}`;
}

export function useChat(client: PocketdClient, model: string): UseChatReturn {
  const [messages, setMessages] = useState<DisplayMessage[]>([]);
  const [isStreaming, setIsStreaming] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [totalTokens, setTotalTokens] = useState(0);
  const abortRef = useRef<AbortController | null>(null);

  const sendMessage = useCallback(
    (content: string) => {
      if (isStreaming || !content.trim()) return;

      setError(null);

      // Add user message
      const userMsg: DisplayMessage = {
        id: nextId(),
        role: "user",
        content: content.trim(),
        timestamp: new Date(),
      };

      // Prepare assistant placeholder
      const assistantId = nextId();
      const assistantMsg: DisplayMessage = {
        id: assistantId,
        role: "assistant",
        content: "",
        timestamp: new Date(),
        isStreaming: true,
        tokenCount: 0,
      };

      setMessages((prev) => [...prev, userMsg, assistantMsg]);
      setIsStreaming(true);

      // Build the full conversation for the API
      const apiMessages: ChatMessage[] = [
        ...messages.map((m) => ({
          role: m.role as ChatMessage["role"],
          content: m.content,
        })),
        { role: "user" as const, content: content.trim() },
      ];

      const abort = new AbortController();
      abortRef.current = abort;

      let tokenCount = 0;

      client
        .streamChat(apiMessages, {
          model,
          signal: abort.signal,
          onToken: (token) => {
            tokenCount++;
            setMessages((prev) =>
              prev.map((m) =>
                m.id === assistantId
                  ? {
                      ...m,
                      content: m.content + token,
                      tokenCount: tokenCount,
                    }
                  : m,
              ),
            );
          },
          onDone: () => {
            setMessages((prev) =>
              prev.map((m) =>
                m.id === assistantId
                  ? { ...m, isStreaming: false, tokenCount }
                  : m,
              ),
            );
            setTotalTokens((prev) => prev + tokenCount);
            setIsStreaming(false);
            abortRef.current = null;
          },
          onError: (err) => {
            setMessages((prev) =>
              prev.map((m) =>
                m.id === assistantId
                  ? {
                      ...m,
                      content: m.content || "(no response)",
                      isStreaming: false,
                    }
                  : m,
              ),
            );
            setError(err.message);
            setIsStreaming(false);
            abortRef.current = null;
          },
        })
        .catch((err) => {
          setError(err instanceof Error ? err.message : "Unknown error");
          setIsStreaming(false);
        });
    },
    [client, model, messages, isStreaming],
  );

  const clearMessages = useCallback(() => {
    if (abortRef.current) {
      abortRef.current.abort();
      abortRef.current = null;
    }
    setMessages([]);
    setIsStreaming(false);
    setError(null);
    setTotalTokens(0);
  }, []);

  return {
    messages,
    isStreaming,
    error,
    sendMessage,
    clearMessages,
    totalTokens,
  };
}
