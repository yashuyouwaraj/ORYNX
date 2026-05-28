"use client";

import { useEffect, useState } from "react";

import WorkflowGraph from "@/components/WorkflowGraph";
import { getAnalytics, getTimeline, getWorkflows } from "@/services/api";
import { connectWebSocket } from "@/services/websocket";
import { useTaskStore } from "@/store/task-store";
import type { Analytics } from "@/types/analytics";
import type { TimelineEvent } from "@/types/timeline";
import type { Workflow, WorkflowEvent } from "@/types/workflow";
import { mapWorkflowsToGraph } from "@/utils/graphMapper";

const getStatusTone = (status?: string) => {
  switch (status) {
    case "RUNNING":
      return "text-cyan-300";

    case "COMPLETED":
      return "text-emerald-300";

    case "FAILED":
      return "text-rose-300";

    default:
      return "text-amber-300";
  }
};

const formatTimelineTime = (timestamp?: number) => {
  if (!timestamp) {
    return "No timestamp";
  }

  return new Intl.DateTimeFormat("en-US", {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    timeZone: "UTC",
  }).format(new Date(timestamp));
};

export default function Home() {
  const [workflows, setWorkflows] = useState<Workflow[]>([]);
  const [activityFeed, setActivityFeed] = useState<WorkflowEvent[]>([]);
  const [analytics, setAnalytics] = useState<Analytics | null>(null);
  const [timeline, setTimeline] = useState<TimelineEvent[]>([]);

  const updateTaskStatus = useTaskStore((state) => state.updateTaskStatus);
  const taskStatuses = useTaskStore((state) => state.taskStatuses);

  useEffect(() => {
    const loadData = async () => {
      try {
        const workflowData = await getWorkflows();
        const analyticsData = await getAnalytics();

        setWorkflows(workflowData);
        setAnalytics(analyticsData);

        if (workflowData.length > 0) {
          const latestWorkflow = workflowData[0];
          const timelineData = await getTimeline(latestWorkflow.id);

          setTimeline(timelineData);
        }
      } catch (error) {
        console.error(error);
      }
    };

    void loadData();

    const disconnect = connectWebSocket(
      async (event) => {
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
                goal: event.goal ?? "AI Generated Workflow",
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

        try {
          const analyticsData = await getAnalytics();
          const timelineData = await getTimeline(event.workflowId);

          setAnalytics(analyticsData);
          setTimeline(timelineData);
        } catch (error) {
          console.error(error);
        }
      },
      (taskEvent) => {
        updateTaskStatus(taskEvent.taskId, taskEvent.status);
      },
    );

    return disconnect;
  }, [updateTaskStatus]);

  const { nodes, connections } = mapWorkflowsToGraph(workflows, taskStatuses);

  return (
    <main className="min-h-screen bg-[#080808] text-zinc-100">
      <div className="mx-auto flex w-full max-w-7xl flex-col gap-8 px-5 py-6 sm:px-8 lg:px-10">
        <header className="flex flex-col gap-6 border-b border-zinc-800 pb-6 lg:flex-row lg:items-end lg:justify-between">
          <div>
            <div className="mb-3 flex items-center gap-3">
              <div className="grid h-9 w-9 place-items-center rounded-lg border border-cyan-300/30 bg-cyan-300/10 text-sm font-bold text-cyan-200">
                OX
              </div>

              <span className="rounded-full border border-zinc-800 bg-zinc-900 px-3 py-1 text-xs font-medium uppercase tracking-wide text-zinc-400">
                Live Control Plane
              </span>
            </div>

            <h1 className="text-3xl font-semibold tracking-normal text-white sm:text-4xl">
              ORYNX
            </h1>

            <p className="mt-2 max-w-2xl text-sm leading-6 text-zinc-400">
              Distributed orchestration, workflow state, and execution activity
              in one operational view.
            </p>
          </div>
        </header>

        <div className="grid grid-cols-1 gap-6 md:grid-cols-4">
          <div className="rounded-2xl border border-zinc-800 bg-zinc-900 p-6">
            <p className="text-zinc-400">Workflows</p>
            <h2 className="mt-2 text-4xl font-bold">
              {analytics?.totalWorkflows ?? 0}
            </h2>
          </div>

          <div className="rounded-2xl border border-zinc-800 bg-zinc-900 p-6">
            <p className="text-zinc-400">Completed</p>
            <h2 className="mt-2 text-4xl font-bold text-emerald-400">
              {analytics?.completedWorkflows ?? 0}
            </h2>
          </div>

          <div className="rounded-2xl border border-zinc-800 bg-zinc-900 p-6">
            <p className="text-zinc-400">Running</p>
            <h2 className="mt-2 text-4xl font-bold text-cyan-400">
              {analytics?.runningWorkflows ?? 0}
            </h2>
          </div>

          <div className="rounded-2xl border border-zinc-800 bg-zinc-900 p-6">
            <p className="text-zinc-400">Avg Execution</p>
            <h2 className="mt-2 text-4xl font-bold text-orange-400">
              {Math.floor((analytics?.averageExecutionTime ?? 0) / 1000)}s
            </h2>
          </div>
        </div>

        <div className="grid grid-cols-1 gap-6 xl:grid-cols-[minmax(0,1fr)_360px]">
          <section className="min-w-0">
            <div className="mb-4 flex items-center justify-between gap-4">
              <div>
                <h2 className="text-lg font-semibold text-white">
                  Orchestration Graph
                </h2>

                <p className="mt-1 text-sm text-zinc-500">
                  {nodes.length} workflows - {connections.length} dependencies
                </p>
              </div>

              <div className="hidden items-center gap-2 rounded-lg border border-zinc-800 bg-[#111111] px-3 py-2 text-xs text-zinc-400 sm:flex">
                <span className="h-2 w-2 rounded-full bg-cyan-300" />
                Streaming updates
              </div>
            </div>

            <WorkflowGraph nodes={nodes} connections={connections} />
          </section>

          <aside className="min-w-0">
            <div className="mb-8">
              <h2 className="mb-6 text-2xl font-semibold">
                Execution Timeline
              </h2>

              <div className="space-y-4">
                {timeline.map((event, index) => (
                  <div
                    key={index}
                    className="rounded-xl border border-zinc-800 bg-zinc-900 p-4"
                  >
                    <p className="text-cyan-400">{event.taskName}</p>
                    <p className="mt-1 text-sm text-zinc-400">
                      {event.status}
                    </p>
                    <p className="mt-2 text-xs text-zinc-500">
                      {formatTimelineTime(event.timestamp)}
                    </p>
                  </div>
                ))}
              </div>
            </div>

            <div className="mb-4 flex items-center justify-between">
              <div>
                <h2 className="text-lg font-semibold text-white">
                  Live Activity
                </h2>
                <p className="mt-1 text-sm text-zinc-500">
                  Recent workflow events
                </p>
              </div>

              <span className="rounded-full border border-zinc-800 bg-[#111111] px-3 py-1 text-xs text-zinc-400">
                {activityFeed.length}
              </span>
            </div>

            <div className="h-[620px] overflow-hidden rounded-lg border border-zinc-800 bg-[#101010]">
              {activityFeed.length === 0 ? (
                <div className="flex h-full items-center justify-center px-8 text-center">
                  <div>
                    <div className="mx-auto mb-4 h-2 w-20 rounded-full bg-zinc-800" />

                    <p className="font-medium text-zinc-200">
                      Waiting for events
                    </p>

                    <p className="mt-2 text-sm leading-6 text-zinc-500">
                      Workflow status updates will stream into this panel.
                    </p>
                  </div>
                </div>
              ) : (
                <div className="max-h-full space-y-3 overflow-y-auto p-4">
                  {activityFeed.map((event, index) => (
                    <div
                      key={index}
                      className="rounded-lg border border-zinc-800 bg-[#151515] p-4 transition hover:border-zinc-700"
                    >
                      <div className="flex items-start justify-between gap-3">
                        <p className="min-w-0 truncate text-sm font-medium text-zinc-100">
                          {event.workflowName}
                        </p>

                        <span
                          className={`shrink-0 text-xs font-semibold uppercase tracking-wide ${getStatusTone(event.status)}`}
                        >
                          {event.status}
                        </span>
                      </div>

                      <p className="mt-3 text-xs text-zinc-500">
                        Workflow #{event.workflowId}
                      </p>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </aside>
        </div>
      </div>
    </main>
  );
}
