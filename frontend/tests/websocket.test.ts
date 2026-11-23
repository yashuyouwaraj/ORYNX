import assert from "node:assert/strict";
import test from "node:test";
import type { Client, IFrame, IMessage, StompSubscription } from "@stomp/stompjs";

import {
  connectWebSocket,
  createEventDeduplicator,
} from "../src/services/websocket.ts";

test("createEventDeduplicator suppresses duplicate eventIds and stale generations", () => {
  const dedup = createEventDeduplicator();

  assert.equal(dedup.shouldProcess(1, "ev-1", 1), true);
  assert.equal(dedup.shouldProcess(1, "ev-1", 1), false);
  assert.equal(dedup.shouldProcess(1, "ev-2", 2), true);
  // Stale generation 1 < 2 must be dropped
  assert.equal(dedup.shouldProcess(1, "ev-3", 1), false);
  // Different workflowId has independent generation tracking
  assert.equal(dedup.shouldProcess(2, "ev-4", 1), true);
});

test("connectWebSocket does not connect without a token", () => {
  const states: string[] = [];
  let factoryCalled = false;

  const disconnect = connectWebSocket(
    () => undefined,
    () => undefined,
    {
      onConnectionStateChange: (state) => states.push(state),
      clientFactory: () => {
        factoryCalled = true;
        return {} as Client;
      },
    },
  );

  assert.equal(factoryCalled, false);
  assert.deepEqual(states, ["disconnected"]);
  disconnect();
});

test("connectWebSocket authenticates, subscribes, deduplicates, reconnects, and cleans up", () => {
  const subscribed: string[] = [];
  const unsubscribed: string[] = [];
  const handlers = new Map<string, (msg: IMessage) => void>();
  let activated = false;
  let deactivated = false;
  let capturedConfig: Parameters<typeof connectWebSocket>[2] extends infer _
    ? {
        connectHeaders?: Record<string, string>;
        onConnect?: (frame: IFrame) => void;
        onWebSocketClose?: (evt: unknown) => void;
      }
    : never;

  const workflowEvents: unknown[] = [];
  const taskEvents: unknown[] = [];
  let reconnectCount = 0;

  const disconnect = connectWebSocket(
    (ev) => workflowEvents.push(ev),
    (ev) => taskEvents.push(ev),
    {
      token: "jwt-abc-123",
      workflowId: 42,
      onReconnect: () => {
        reconnectCount += 1;
      },
      clientFactory: (config) => {
        capturedConfig = config;
        return {
          activate: () => {
            activated = true;
          },
          deactivate: async () => {
            deactivated = true;
          },
          subscribe: (dest: string, cb: (msg: IMessage) => void): StompSubscription => {
            subscribed.push(dest);
            handlers.set(dest, cb);
            return {
              id: dest,
              unsubscribe: () => {
                unsubscribed.push(dest);
              },
            };
          },
        } as unknown as Client;
      },
    },
  );

  assert.equal(activated, true);
  assert.equal(capturedConfig!.connectHeaders?.Authorization, "Bearer jwt-abc-123");

  // Simulate initial connect
  capturedConfig!.onConnect?.({} as IFrame);
  assert.deepEqual(subscribed, [
    "/user/queue/workflows",
    "/user/queue/tasks",
    "/topic/workflows/42",
    "/topic/workflows/42/tasks",
  ]);
  assert.equal(reconnectCount, 0);

  // Deliver duplicate and valid workflow/task messages
  const wfHandler = handlers.get("/topic/workflows/42")!;
  wfHandler({
    body: JSON.stringify({
      workflowId: 42,
      workflowName: "WF",
      status: "RUNNING",
      generation: 1,
      eventId: "w-1",
    }),
  } as IMessage);
  wfHandler({
    body: JSON.stringify({
      workflowId: 42,
      workflowName: "WF",
      status: "RUNNING",
      generation: 1,
      eventId: "w-1",
    }),
  } as IMessage);
  assert.equal(workflowEvents.length, 1);

  const taskHandler = handlers.get("/topic/workflows/42/tasks")!;
  taskHandler({
    body: JSON.stringify({
      workflowId: 42,
      taskId: 9,
      taskName: "T1",
      status: "COMPLETED",
      generation: 1,
      eventId: "t-1",
    }),
  } as IMessage);
  assert.equal(taskEvents.length, 1);

  // Simulate disconnect + reconnect -> triggers REST recovery callback
  capturedConfig!.onWebSocketClose?.({});
  assert.equal(unsubscribed.length, 4);
  capturedConfig!.onConnect?.({} as IFrame);
  assert.equal(reconnectCount, 1);

  // Unmount cleanup
  disconnect();
  assert.equal(deactivated, true);
  assert.equal(unsubscribed.length, 8);
});
