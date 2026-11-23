"use client";

import {
  Client,
  type IMessage,
  type IStompSocket,
  type StompSubscription,
} from "@stomp/stompjs";
import SockJS from "sockjs-client";

import type { TaskExecutionEvent } from "@/types/task-event";
import type { WorkflowEvent } from "@/types/workflow";

const WS_BASE_URL =
  process.env.NEXT_PUBLIC_WS_BASE_URL ?? "http://localhost:8080";

const MAX_SEEN_EVENT_IDS = 500;

export type WebSocketConnectionState =
  | "connecting"
  | "connected"
  | "reconnecting"
  | "disconnected"
  | "error";

export interface ConnectWebSocketOptions {
  token?: string;
  workflowId?: number;
  onReconnect?: () => void;
  onConnectionStateChange?: (state: WebSocketConnectionState) => void;
  clientFactory?: (config: ConstructorParameters<typeof Client>[0]) => Client;
}

export interface WebSocketDeduplicator {
  shouldProcess: (
    workflowId: number | undefined,
    eventId?: string,
    generation?: number,
  ) => boolean;
}

export const createEventDeduplicator = (): WebSocketDeduplicator => {
  const seenEventIds = new Set<string>();
  const latestGenerationByWorkflow = new Map<number, number>();

  return {
    shouldProcess(workflowId, eventId, generation) {
      if (eventId) {
        if (seenEventIds.has(eventId)) {
          return false;
        }
        seenEventIds.add(eventId);
        if (seenEventIds.size > MAX_SEEN_EVENT_IDS) {
          const oldest = seenEventIds.values().next().value;
          if (oldest !== undefined) {
            seenEventIds.delete(oldest);
          }
        }
      }

      if (workflowId !== undefined && generation !== undefined) {
        const currentMax = latestGenerationByWorkflow.get(workflowId);
        if (currentMax !== undefined && generation < currentMax) {
          return false;
        }
        if (currentMax === undefined || generation > currentMax) {
          latestGenerationByWorkflow.set(workflowId, generation);
        }
      }

      return true;
    },
  };
};

let stompClient: Client | null = null;

export const connectWebSocket = (
  onMessageReceived: (message: WorkflowEvent) => void,
  onTaskEvent: (event: TaskExecutionEvent) => void,
  options: ConnectWebSocketOptions = {},
) => {
  const {
    token,
    workflowId,
    onReconnect,
    onConnectionStateChange,
    clientFactory,
  } = options;

  if (!token) {
    onConnectionStateChange?.("disconnected");
    return () => {};
  }

  const deduplicator = createEventDeduplicator();
  let subscriptions: StompSubscription[] = [];
  let hasConnectedOnce = false;
  let disposed = false;

  const clearSubscriptions = () => {
    for (const sub of subscriptions) {
      try {
        sub.unsubscribe();
      } catch {
        // Ignore errors when cleaning up already-closed subscriptions
      }
    }
    subscriptions = [];
  };

  const handleWorkflowMessage = (message: IMessage) => {
    try {
      const body = JSON.parse(message.body) as WorkflowEvent;
      if (
        !deduplicator.shouldProcess(
          body.workflowId,
          body.eventId,
          body.generation,
        )
      ) {
        return;
      }
      onMessageReceived(body);
    } catch (err) {
      console.error("Malformed workflow WebSocket payload:", err);
    }
  };

  const handleTaskMessage = (message: IMessage) => {
    try {
      const body = JSON.parse(message.body) as TaskExecutionEvent;
      if (
        !deduplicator.shouldProcess(
          body.workflowId,
          body.eventId,
          body.generation,
        )
      ) {
        return;
      }
      onTaskEvent(body);
    } catch (err) {
      console.error("Malformed task WebSocket payload:", err);
    }
  };

  onConnectionStateChange?.("connecting");

  const clientConfig: ConstructorParameters<typeof Client>[0] = {
    webSocketFactory: () =>
      new SockJS(`${WS_BASE_URL}/ws`) as unknown as IStompSocket,
    connectHeaders: {
      Authorization: `Bearer ${token}`,
    },
    reconnectDelay: 5000,
    debug: () => undefined,
    onConnect: () => {
      if (disposed) {
        void client.deactivate();
        return;
      }

      clearSubscriptions();
      onConnectionStateChange?.("connected");

      subscriptions.push(
        client.subscribe("/user/queue/workflows", handleWorkflowMessage),
        client.subscribe("/user/queue/tasks", handleTaskMessage),
      );

      if (workflowId !== undefined) {
        subscriptions.push(
          client.subscribe(
            `/topic/workflows/${workflowId}`,
            handleWorkflowMessage,
          ),
          client.subscribe(
            `/topic/workflows/${workflowId}/tasks`,
            handleTaskMessage,
          ),
        );
      }

      if (hasConnectedOnce) {
        onReconnect?.();
      }
      hasConnectedOnce = true;
    },
    onStompError: (frame) => {
      console.error("WebSocket broker error:", frame.headers.message);
      onConnectionStateChange?.("error");
    },
    onWebSocketClose: () => {
      clearSubscriptions();
      if (!disposed) {
        onConnectionStateChange?.(
          hasConnectedOnce ? "reconnecting" : "disconnected",
        );
      }
    },
    onWebSocketError: (event) => {
      console.error("WebSocket connection error:", event);
      onConnectionStateChange?.("error");
    },
  };

  const client = clientFactory
    ? clientFactory(clientConfig)
    : new Client(clientConfig);

  stompClient = client;
  client.activate();

  return () => {
    disposed = true;
    clearSubscriptions();
    void client.deactivate();
    onConnectionStateChange?.("disconnected");

    if (stompClient === client) {
      stompClient = null;
    }
  };
};
