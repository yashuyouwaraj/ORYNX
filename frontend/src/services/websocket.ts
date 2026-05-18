"use client";

import { Client, type IMessage, type IStompSocket } from "@stomp/stompjs";
import SockJS from "sockjs-client";

import type { WorkflowEvent } from "@/types/workflow";

const WS_BASE_URL =
  process.env.NEXT_PUBLIC_WS_BASE_URL ?? "http://localhost:8082";

let stompClient: Client | null = null;

export const connectWebSocket = (
  onMessageReceived: (message: WorkflowEvent) => void,
) => {
  const client = new Client({
    webSocketFactory: () =>
      new SockJS(`${WS_BASE_URL}/ws`) as unknown as IStompSocket,
    reconnectDelay: 5000,
    debug: () => undefined,
    onConnect: () => {
      console.log("Connected to WebSocket");

      client.subscribe("/topic/workflows", (message: IMessage) => {
        const body = JSON.parse(message.body) as WorkflowEvent;

        onMessageReceived(body);
      });
    },
    onStompError: (frame) => {
      console.error("WebSocket broker error:", frame.headers.message);
    },
    onWebSocketClose: (event) => {
      console.warn("WebSocket closed:", event.reason || event.code);
    },
    onWebSocketError: (event) => {
      console.error("WebSocket connection error:", event);
    },
  });

  stompClient = client;
  client.activate();

  return () => {
    void client.deactivate();

    if (stompClient === client) {
      stompClient = null;
    }
  };
};
