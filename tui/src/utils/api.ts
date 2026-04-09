/**
 * OpenAI-compatible API client for the pocketd local LLM server.
 * Supports both streaming (SSE) and non-streaming chat completions.
 */

export interface ChatMessage {
  role: "user" | "assistant" | "system";
  content: string;
}

export interface ChatCompletionRequest {
  model: string;
  messages: ChatMessage[];
  stream: boolean;
  temperature?: number;
  max_tokens?: number;
}

export interface ModelInfo {
  id: string;
  object: string;
  owned_by: string;
}

// ── SSE streaming chunk ──────────────────────────────────────────────────

interface StreamDelta {
  role?: string;
  content?: string;
}

interface StreamChoice {
  index: number;
  delta: StreamDelta;
  finish_reason: string | null;
}

interface ChatCompletionChunk {
  id: string;
  object: string;
  created: number;
  model: string;
  choices: StreamChoice[];
}

// ── Client ───────────────────────────────────────────────────────────────

export class PocketdClient {
  constructor(
    public baseUrl: string = "http://localhost:8080",
    public bearerToken?: string,
  ) {}

  private headers(): Record<string, string> {
    const h: Record<string, string> = {
      "Content-Type": "application/json",
    };
    if (this.bearerToken) {
      h["Authorization"] = `Bearer ${this.bearerToken}`;
    }
    return h;
  }

  /**
   * Fetch available models from /v1/models.
   */
  async listModels(): Promise<ModelInfo[]> {
    const res = await fetch(`${this.baseUrl}/v1/models`, {
      headers: this.headers(),
    });
    if (!res.ok) {
      throw new Error(`Models request failed: ${res.status} ${res.statusText}`);
    }
    const body = (await res.json()) as { data: ModelInfo[] };
    return body.data;
  }

  /**
   * Send a chat completion request with SSE streaming.
   * Calls `onToken` for each incremental text token.
   * Calls `onDone` when the stream ends.
   * Calls `onError` if something goes wrong.
   */
  async streamChat(
    messages: ChatMessage[],
    opts: {
      model?: string;
      onToken: (token: string) => void;
      onDone: () => void;
      onError: (err: Error) => void;
      signal?: AbortSignal;
    },
  ): Promise<void> {
    const { model = "local", onToken, onDone, onError, signal } = opts;

    let res: Response;
    try {
      res = await fetch(`${this.baseUrl}/v1/chat/completions`, {
        method: "POST",
        headers: this.headers(),
        body: JSON.stringify({
          model,
          messages,
          stream: true,
        } satisfies ChatCompletionRequest),
        signal,
      });
    } catch (err) {
      onError(
        err instanceof Error ? err : new Error("Network request failed"),
      );
      return;
    }

    if (!res.ok) {
      onError(new Error(`HTTP ${res.status}: ${res.statusText}`));
      return;
    }

    if (!res.body) {
      onError(new Error("Response body is null"));
      return;
    }

    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";

    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split("\n");
        // Keep the last potentially incomplete line in the buffer
        buffer = lines.pop() ?? "";

        for (const line of lines) {
          const trimmed = line.trim();
          if (!trimmed || !trimmed.startsWith("data: ")) continue;

          const data = trimmed.slice(6); // Remove "data: " prefix
          if (data === "[DONE]") {
            onDone();
            return;
          }
          if (data === "[ERROR]") {
            onError(new Error("Server reported stream error"));
            return;
          }

          try {
            const chunk = JSON.parse(data) as ChatCompletionChunk;
            const content = chunk.choices?.[0]?.delta?.content;
            if (content) {
              onToken(content);
            }
          } catch {
            // Skip malformed JSON lines
          }
        }
      }
      // If we exit the loop without [DONE], still signal completion
      onDone();
    } catch (err) {
      if (signal?.aborted) return;
      onError(err instanceof Error ? err : new Error("Stream read failed"));
    }
  }

  /**
   * Quick health check: tries to hit /v1/models.
   * Returns true if the server responds with 200.
   */
  async isHealthy(): Promise<boolean> {
    try {
      const res = await fetch(`${this.baseUrl}/v1/models`, {
        headers: this.headers(),
        signal: AbortSignal.timeout(3000),
      });
      return res.ok;
    } catch {
      return false;
    }
  }
}
