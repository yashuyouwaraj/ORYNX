"use client";

import { useEffect, useState } from "react";

import WorkflowGraph from "@/components/WorkflowGraph";

import { mapWorkflowsToGraph } from "@/utils/graphMapper";

import { connectWebSocket } from "@/services/websocket";

import { getWorkflows } from "@/services/api";

import type { Workflow, WorkflowEvent } from "@/types/workflow";

export default function Home() {
  const [workflows, setWorkflows] = useState<Workflow[]>([]);

  const [activityFeed, setActivityFeed] = useState<WorkflowEvent[]>([]);

  useEffect(() => {
    const loadWorkflows = async () => {
      try {
        const data = await getWorkflows();

        setWorkflows(data);
      } catch (error) {
        console.error(error);
      }
    };

    void loadWorkflows();

    const disconnect = connectWebSocket((event) => {
      setActivityFeed((prev) => [event, ...prev]);

      setWorkflows((prev) => {
        const exists = prev.find(
          (workflow) => workflow.id === event.workflowId,
        );

        if (!exists) {
          return [
            {
              id: event.workflowId,
              name: event.workflowName,
              goal: "Realtime Workflow",
              status: event.status,
            },
            ...prev,
          ];
        }

        return prev.map((workflow) =>
          workflow.id === event.workflowId
            ? {
                ...workflow,
                status: event.status,
              }
            : workflow,
        );
      });
    });

    return disconnect;
  }, []);

  const { nodes, connections } = mapWorkflowsToGraph(workflows);

  return (
    <main className="min-h-screen bg-black p-10 text-white">
      <div className="mb-10 flex items-center justify-between">
        <div>
          <h1 className="text-5xl font-bold">ORYNX</h1>

          <p className="mt-2 text-zinc-400">
            Distributed Orchestration Platform
          </p>
        </div>
      </div>

      <div className="grid grid-cols-1 gap-8 lg:grid-cols-3">
        <div className="lg:col-span-2">
          <h2 className="mb-6 text-2xl font-semibold">Orchestration Graph</h2>

          <WorkflowGraph nodes={nodes} connections={connections} />
        </div>

        <div>
          <h2 className="mb-6 text-2xl font-semibold">Live Activity</h2>

          <div className="space-y-3">
            {activityFeed.map((event, index) => (
              <div
                key={index}
                className="rounded-xl border border-zinc-800 bg-zinc-900 p-4"
              >
                <p className="text-sm text-cyan-400">{event.status}</p>

                <p className="mt-1">{event.workflowName}</p>
              </div>
            ))}
          </div>
        </div>
      </div>
    </main>
  );
}
