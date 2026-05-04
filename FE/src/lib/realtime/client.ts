import { Client, type IMessage, type StompSubscription } from "@stomp/stompjs";
import { useEffect, useState } from "react";
import { AppState, type AppStateStatus, type NativeEventSubscription } from "react-native";
import { API_BASE_URL } from "../../api/config";
import { getAccessToken } from "../../api/client";

export type RealtimeStatus = "idle" | "connecting" | "connected" | "disconnected";

export type RealtimeFrameHandler = (frame: { body: string; headers: Record<string, string> }) => void;

export interface RealtimeSubscription {
  /**
   * Tear down the subscription. Safe to call multiple times — second call
   * is a no-op. Idempotency matters for component cleanup paths that may
   * fire on both unmount and AppState change.
   */
  unsubscribe(): void;
}

interface PendingSubscription {
  destination: string;
  handler: RealtimeFrameHandler;
  active: StompSubscription | null;
}

/**
 * Convert the REST base URL into the STOMP/WebSocket URL.
 *
 * Examples:
 *   https://api.rearleg.com/yeolsal/api/v1  →  wss://api.rearleg.com/yeolsal/ws
 *   http://localhost:8080/api/v1            →  ws://localhost:8080/ws
 */
export function deriveWebSocketUrl(restBase: string): string {
  const upgraded = restBase.replace(/^http(s?):/, "ws$1:");
  return upgraded.replace(/\/api\/v1\/?$/, "/ws");
}

/**
 * Singleton STOMP-over-WebSocket client. One TCP connection per app.
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>{@link connect} — spins up the underlying client with the latest
 *       access token and ties to {@link AppState}. Background → close;
 *       active → reconnect. Re-establishing all in-flight subscriptions
 *       happens automatically inside the client's onConnect callback.</li>
 *   <li>{@link subscribe} — returns immediately even if disconnected; the
 *       subscription is queued and replayed when the next CONNECTED
 *       frame arrives. Caller never has to retry by hand.</li>
 *   <li>{@link disconnect} — full teardown. Used on logout.</li>
 * </ul>
 */
export class RealtimeClient {
  private client: Client | null = null;
  private status: RealtimeStatus = "idle";
  private statusListeners: Set<(s: RealtimeStatus) => void> = new Set();
  private subscriptions: Map<string, PendingSubscription[]> = new Map();
  private appStateSub: NativeEventSubscription | null = null;
  private connectInflight = false;

  getStatus(): RealtimeStatus {
    return this.status;
  }

  onStatusChange(listener: (s: RealtimeStatus) => void): () => void {
    this.statusListeners.add(listener);
    return () => {
      this.statusListeners.delete(listener);
    };
  }

  async connect(): Promise<void> {
    if (this.client && this.client.connected) return;
    if (this.connectInflight) return;
    this.connectInflight = true;
    try {
      const token = await getAccessToken();
      if (!token) {
        // No auth — silently skip. Provider re-tries on auth change.
        this.setStatus("idle");
        return;
      }
      this.setStatus("connecting");
      const brokerURL = deriveWebSocketUrl(API_BASE_URL);
      const client = new Client({
        brokerURL,
        connectHeaders: { Authorization: `Bearer ${token}` },
        reconnectDelay: 2_000,
        heartbeatIncoming: 10_000,
        heartbeatOutgoing: 10_000,
        onConnect: () => {
          this.setStatus("connected");
          this.replaySubscriptions();
        },
        onDisconnect: () => this.setStatus("disconnected"),
        onWebSocketClose: () => this.setStatus("disconnected"),
        onStompError: (frame) => {
          // STOMP-level error frames are typically auth failures. Tear down
          // so the next AppState/auth tick re-attempts with a fresh token.
          // eslint-disable-next-line no-console -- broker errors are operationally important
          console.warn("[realtime] STOMP error", frame.headers, frame.body);
          this.setStatus("disconnected");
        },
      });
      this.client = client;
      client.activate();
      this.bindAppState();
    } finally {
      this.connectInflight = false;
    }
  }

  disconnect(): void {
    if (this.appStateSub) {
      this.appStateSub.remove();
      this.appStateSub = null;
    }
    const client = this.client;
    this.client = null;
    if (client) {
      // deactivate() returns a Promise but we don't need to await — the
      // teardown is best-effort.
      client.deactivate().catch(() => undefined);
    }
    // Drop active subscription handles but keep destination/handler entries
    // so a subsequent connect() can replay them.
    for (const list of this.subscriptions.values()) {
      for (const entry of list) entry.active = null;
    }
    this.setStatus("idle");
  }

  subscribe(destination: string, handler: RealtimeFrameHandler): RealtimeSubscription {
    const entry: PendingSubscription = { destination, handler, active: null };
    const list = this.subscriptions.get(destination) ?? [];
    list.push(entry);
    this.subscriptions.set(destination, list);
    if (this.client && this.client.connected) {
      entry.active = this.attach(entry);
    }
    return {
      unsubscribe: () => this.unsubscribe(entry),
    };
  }

  // For tests — verify which destinations have pending subscriptions.
  // Not part of the public API.
  _internalSubscriptionCount(destination?: string): number {
    if (destination !== undefined) {
      return this.subscriptions.get(destination)?.length ?? 0;
    }
    let n = 0;
    for (const list of this.subscriptions.values()) n += list.length;
    return n;
  }

  private bindAppState(): void {
    if (this.appStateSub) return;
    this.appStateSub = AppState.addEventListener("change", (next: AppStateStatus) => {
      if (next === "active") {
        if (!this.client || !this.client.connected) {
          this.connect().catch(() => undefined);
        }
      } else if (next === "background") {
        const client = this.client;
        if (client && client.connected) {
          client.deactivate().catch(() => undefined);
        }
      }
    });
  }

  private replaySubscriptions(): void {
    for (const list of this.subscriptions.values()) {
      for (const entry of list) {
        if (entry.active === null) {
          entry.active = this.attach(entry);
        }
      }
    }
  }

  private attach(entry: PendingSubscription): StompSubscription | null {
    const client = this.client;
    if (!client || !client.connected) return null;
    return client.subscribe(entry.destination, (msg: IMessage) => {
      // Wrap the IMessage into a minimal frame shape so callers don't need
      // to depend on the @stomp/stompjs type surface directly.
      entry.handler({ body: msg.body, headers: { ...msg.headers } });
    });
  }

  private unsubscribe(entry: PendingSubscription): void {
    const list = this.subscriptions.get(entry.destination);
    if (!list) return;
    const idx = list.indexOf(entry);
    if (idx === -1) return;
    list.splice(idx, 1);
    if (list.length === 0) {
      this.subscriptions.delete(entry.destination);
    }
    const active = entry.active;
    entry.active = null;
    if (active) {
      try {
        active.unsubscribe();
      } catch {
        // Best-effort — broker may already have torn it down.
      }
    }
  }

  private setStatus(next: RealtimeStatus): void {
    if (this.status === next) return;
    this.status = next;
    for (const listener of this.statusListeners) {
      try {
        listener(next);
      } catch {
        // A bad listener must not break the others.
      }
    }
  }
}

/** Process-wide singleton — one TCP connection per app. */
let singleton: RealtimeClient | null = null;

export function getRealtimeClient(): RealtimeClient {
  if (!singleton) {
    singleton = new RealtimeClient();
  }
  return singleton;
}

/** Test-only — reset the singleton between cases. */
export function _resetRealtimeClientForTests(): void {
  if (singleton) {
    singleton.disconnect();
  }
  singleton = null;
}

/**
 * React-friendly view of the singleton's connection status. Components
 * can use this to e.g. throttle their REST polling fallback when WS is
 * delivering updates in real-time.
 */
export function useRealtimeStatus(): RealtimeStatus {
  const client = getRealtimeClient();
  const [status, setStatus] = useState<RealtimeStatus>(client.getStatus());
  useEffect(() => {
    setStatus(client.getStatus());
    return client.onStatusChange(setStatus);
  }, [client]);
  return status;
}

/**
 * Subscribe a component to a STOMP destination. Auto-tears-down on unmount
 * or destination change. The handler is wrapped to JSON-decode the frame
 * body and is invoked with the decoded payload (or {@code null} if the
 * frame body is malformed).
 */
export function useRealtimeSubscription<T>(
  destination: string | null,
  handler: (payload: T) => void,
): void {
  useEffect(() => {
    if (!destination) return;
    const sub = getRealtimeClient().subscribe(destination, (frame) => {
      let parsed: T | null = null;
      try {
        parsed = JSON.parse(frame.body) as T;
      } catch {
        return;
      }
      handler(parsed);
    });
    return () => {
      sub.unsubscribe();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- handler captured by ref intentionally; destination changes drive re-subscription
  }, [destination]);
}
