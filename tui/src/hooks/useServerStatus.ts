/**
 * Periodically checks server health and fetches model info.
 */

import { useState, useEffect, useCallback } from "react";
import { PocketdClient } from "../utils/api.js";

export interface ServerStatus {
  connected: boolean;
  modelName: string;
  checking: boolean;
  lastCheck: Date | null;
}

export function useServerStatus(
  client: PocketdClient,
  pollIntervalMs: number = 10_000,
): ServerStatus {
  const [connected, setConnected] = useState(false);
  const [modelName, setModelName] = useState("unknown");
  const [checking, setChecking] = useState(true);
  const [lastCheck, setLastCheck] = useState<Date | null>(null);

  const check = useCallback(async () => {
    setChecking(true);
    try {
      const healthy = await client.isHealthy();
      setConnected(healthy);
      if (healthy) {
        try {
          const models = await client.listModels();
          if (models.length > 0) {
            setModelName(models[0]!.id);
          }
        } catch {
          // Keep existing model name
        }
      }
    } catch {
      setConnected(false);
    }
    setChecking(false);
    setLastCheck(new Date());
  }, [client]);

  useEffect(() => {
    check();
    const interval = setInterval(check, pollIntervalMs);
    return () => clearInterval(interval);
  }, [check, pollIntervalMs]);

  return { connected, modelName, checking, lastCheck };
}
