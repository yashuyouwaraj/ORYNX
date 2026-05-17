"use client";

import { useEffect, useState } from "react";

import { connectWebSocket } from "@/services/websocket";
import type { WorkflowEvent } from "@/types/workflow";

export default function Home() {

  const [events, setEvents] = useState<
    WorkflowEvent[]
  >([]);

  useEffect(() => {

    const disconnect = connectWebSocket((event) => {

      setEvents((prev) => [
        event,
        ...prev
      ]);
    });

    return disconnect;
  }, []);

  return (

    <main className="min-h-screen bg-black p-10 text-white">

      <h1 className="mb-10 text-4xl font-bold">
        ORYNX Orchestration Dashboard
      </h1>

      <div className="grid gap-4">

        {events.map((event, index) => (

          <div
            key={index}
            className="rounded-xl border border-gray-700 bg-zinc-900 p-5"
          >

            <h2 className="text-xl font-semibold">
              {event.workflowName}
            </h2>

            <p className="mt-2">
              Workflow ID:
              {event.workflowId}
            </p>

            <p className="mt-2 text-cyan-400">
              Status:
              {event.status}
            </p>

          </div>
        ))}

      </div>

    </main>
  );
}
