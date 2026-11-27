"use client";

import { useEffect, useRef, useState } from "react";

import WorkflowGraph from "@/components/WorkflowGraph";
import {
  getAnalytics,
  getPerformance,
  getTimeline,
  getWorkflows,
  getWorkflowTasks,
  getPerformanceSummary,
  getWorkflowHealth,
} from "@/services/api";
import { connectWebSocket } from "@/services/websocket";
import { useTaskStore } from "@/store/task-store";
import type { Analytics } from "@/types/analytics";
import type { TimelineEvent } from "@/types/timeline";
import type { Workflow, WorkflowEvent } from "@/types/workflow";
import type { WorkflowTaskNode } from "@/types/graph";
import {
  resolveWorkflowGraphView,
  type GraphViewMode,
} from "@/utils/graphMapper";
import { createWorkflowSelectionGuard } from "@/utils/workflowSelectionGuard";
import { WorkflowPerformance } from "@/types/performance";
import type { PerformanceSummary } from "@/types/performance-summary";
import type { WorkflowHealth } from "@/types/workflow-health";

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

interface MetricCardProps {
  title: string;
  value: string | number;
  valueClassName?: string;
}

function MetricCard({
  title,
  value,
  valueClassName = "text-white",
}: MetricCardProps) {
  return (
    <div className="rounded-2xl border border-zinc-800 bg-zinc-900 p-6">
      <p className="text-sm text-zinc-500">{title}</p>

      <h2 className={`mt-3 text-4xl font-bold ${valueClassName}`}>{value}</h2>
    </div>
  );
}

const getHealthColor = (health: string) => {
  switch (health) {
    case "HEALTHY":
      return "text-emerald-400";

    case "WARNING":
      return "text-yellow-400";

    case "CRITICAL":
      return "text-red-400";

    default:
      return "text-zinc-400";
  }
};

export default function Home() {
  const [workflows, setWorkflows] = useState<Workflow[]>([]);
  const [activityFeed, setActivityFeed] = useState<WorkflowEvent[]>([]);
  const [analytics, setAnalytics] = useState<Analytics | null>(null);
  const [timeline, setTimeline] = useState<TimelineEvent[]>([]);
  const [performance, setPerformance] = useState<WorkflowPerformance[]>([]);
  const [performanceSummary, setPerformanceSummary] =
    useState<PerformanceSummary | null>(null);
  const [workflowHealth, setWorkflowHealth] = useState<WorkflowHealth[]>([]);
  const [selectedWorkflowId, setSelectedWorkflowId] = useState<number | null>(
    null,
  );
  const [workflowTasks, setWorkflowTasks] = useState<WorkflowTaskNode[]>([]);
  const [graphViewMode, setGraphViewMode] = useState<GraphViewMode>("overview");
  const selectionGuardRef = useRef(createWorkflowSelectionGuard());

  const updateTaskStatus = useTaskStore((state) => state.updateTaskStatus);
  const taskStatuses = useTaskStore((state) => state.taskStatuses);

  useEffect(() => {
    const token =
      typeof window !== "undefined"
        ? (localStorage.getItem("orynx_token") ??
          localStorage.getItem("token") ??
          undefined)
        : undefined;

    const loadData = async () => {
      try {
        const workflowData = await getWorkflows(token);
        const analyticsData = await getAnalytics(token);
        const performanceData = await getPerformance(token);
        const summary = await getPerformanceSummary(token);
        const healthData = await getWorkflowHealth(token);

        setWorkflowHealth(healthData);
        setWorkflows(workflowData);
        setPerformance(performanceData);
        setAnalytics(analyticsData);
        setPerformanceSummary(summary);

        if (workflowData.length > 0) {
          const activeId =
            selectionGuardRef.current.getSelectedWorkflowId() ??
            workflowData[0].id;
          const fetchToken =
            selectionGuardRef.current.getSelectedWorkflowId() === null
              ? selectionGuardRef.current.selectWorkflow(activeId)
              : selectionGuardRef.current.beginFetchForCurrentSelection(activeId);

          if (fetchToken) {
            setSelectedWorkflowId(activeId);
            const [timelineData, tasksData] = await Promise.all([
              getTimeline(activeId, token),
              getWorkflowTasks(activeId, token).catch(() => []),
            ]);

            if (selectionGuardRef.current.shouldApplyFetchResult(fetchToken)) {
              setTimeline(timelineData);
              setWorkflowTasks(tasksData);
              if (
                tasksData.some(
                  (t) => Array.isArray(t.dependsOn) && t.dependsOn.length > 0,
                )
              ) {
                setGraphViewMode("dag");
              }
            }
          }
        }
      } catch (error) {
        console.error(error);
      }
    };

    void loadData();

    const disconnect = connectWebSocket(
      async (event) => {
        setActivityFeed((prev) => {
          if (
            event.eventId &&
            prev.some((existing) => existing.eventId === event.eventId)
          ) {
            return prev;
          }
          return [event, ...prev];
        });

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
          const [analyticsData, performanceData, healthData] =
            await Promise.all([
              getAnalytics(token),
              getPerformance(token),
              getWorkflowHealth(token),
            ]);

          setWorkflowHealth(healthData);
          setAnalytics(analyticsData);
          setPerformance(performanceData);

          // Only refresh workflow-specific Task DAG and Timeline when the event
          // matches the currently selected workflow and no newer selection superseded it.
          const fetchToken =
            selectionGuardRef.current.beginFetchForCurrentSelection(
              event.workflowId,
            );
          if (fetchToken) {
            const [timelineData, tasksData] = await Promise.all([
              getTimeline(event.workflowId, token),
              getWorkflowTasks(event.workflowId, token).catch(() => null),
            ]);

            if (selectionGuardRef.current.shouldApplyFetchResult(fetchToken)) {
              setTimeline(timelineData);
              if (tasksData) {
                setWorkflowTasks(tasksData);
              }
            }
          }
        } catch (error) {
          console.error(error);
        }
      },
      (taskEvent) => {
        updateTaskStatus(taskEvent.taskId, taskEvent.status);
      },
      {
        token,
        onReconnect: () => {
          void loadData();
        },
      },
    );

    return disconnect;
  }, [updateTaskStatus]);

  const handleSelectWorkflow = async (workflowId: number) => {
    const fetchToken = selectionGuardRef.current.selectWorkflow(workflowId);
    setSelectedWorkflowId(workflowId);
    const token =
      typeof window !== "undefined"
        ? (localStorage.getItem("orynx_token") ??
          localStorage.getItem("token") ??
          undefined)
        : undefined;
    try {
      const [tasksData, timelineData] = await Promise.all([
        getWorkflowTasks(workflowId, token),
        getTimeline(workflowId, token),
      ]);
      if (selectionGuardRef.current.shouldApplyFetchResult(fetchToken)) {
        setWorkflowTasks(tasksData);
        setTimeline(timelineData);
        setGraphViewMode("dag");
      }
    } catch (error) {
      console.error(error);
    }
  };

  const resolvedGraph = resolveWorkflowGraphView(
    graphViewMode,
    workflows,
    workflowTasks,
    taskStatuses,
  );
  const { nodes, connections } = resolvedGraph;

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

        <section className="space-y-8">
          <div>
            <h2 className="text-lg font-semibold text-white">
              Platform Metrics
            </h2>

            <p className="mt-1 text-sm text-zinc-500">
              Workflow execution statistics
            </p>

            <div className="mt-4 grid grid-cols-1 gap-6 md:grid-cols-2 xl:grid-cols-4">
              <MetricCard
                title="Workflows"
                value={analytics?.totalWorkflows ?? 0}
              />

              <MetricCard
                title="Completed"
                value={analytics?.completedWorkflows ?? 0}
                valueClassName="text-emerald-400"
              />

              <MetricCard
                title="Running"
                value={analytics?.runningWorkflows ?? 0}
                valueClassName="text-cyan-400"
              />

              <MetricCard
                title="Avg Execution"
                value={`${Math.floor((analytics?.averageExecutionTime ?? 0) / 1000)}s`}
                valueClassName="text-orange-400"
              />
            </div>
          </div>

          <div>
            <h2 className="text-lg font-semibold text-white">
              Platform Health
            </h2>

            <p className="mt-1 text-sm text-zinc-500">
              Operational telemetry and execution health
            </p>

            <div className="mt-4 grid grid-cols-1 gap-6 md:grid-cols-2 xl:grid-cols-4">
              <MetricCard
                title="Success Rate"
                value={`${(analytics?.workflowSuccessRate ?? 0).toFixed(1)}%`}
                valueClassName="text-emerald-400"
              />

              <MetricCard
                title="Failure Rate"
                value={`${(analytics?.workflowFailureRate ?? 0).toFixed(1)}%`}
                valueClassName="text-rose-400"
              />

              <MetricCard
                title="Executed Tasks"
                value={analytics?.executedTaskCount ?? 0}
                valueClassName="text-cyan-400"
              />

              <MetricCard
                title="Event Volume"
                value={analytics?.eventCount ?? 0}
                valueClassName="text-orange-400"
              />
            </div>

            <section className="space-y-4">
              <div>
                <h2 className="text-lg font-semibold text-white">
                  Performance Intelligence
                </h2>

                <p className="mt-1 text-sm text-zinc-500">
                  Runtime execution insights
                </p>
              </div>

              <div className="grid grid-cols-1 gap-6 md:grid-cols-2 xl:grid-cols-4">
                <MetricCard
                  title="Fastest"
                  value={
                    performanceSummary
                      ? `${(performanceSummary.fastestDuration / 1000).toFixed(
                          2,
                        )}s`
                      : "-"
                  }
                  valueClassName="text-emerald-400"
                />

                <MetricCard
                  title="Slowest"
                  value={
                    performanceSummary
                      ? `${(performanceSummary.slowestDuration / 1000).toFixed(
                          2,
                        )}s`
                      : "-"
                  }
                  valueClassName="text-rose-400"
                />

                <MetricCard
                  title="Average"
                  value={
                    performanceSummary
                      ? `${(performanceSummary.averageDuration / 1000).toFixed(
                          2,
                        )}s`
                      : "-"
                  }
                  valueClassName="text-cyan-400"
                />

                <MetricCard
                  title="Completed"
                  value={performanceSummary?.completedWorkflowCount ?? 0}
                  valueClassName="text-orange-400"
                />
              </div>
              <div className="rounded-2xl border border-zinc-800 bg-zinc-900 p-6">
                <h3 className="text-lg font-semibold">Performance Summary</h3>

                <div className="mt-4 space-y-2 text-sm">
                  <p>
                    ⚡ Fastest:{" "}
                    <span className="text-emerald-400">
                      {performanceSummary?.fastestWorkflow ?? "-"}
                    </span>
                  </p>

                  <p>
                    🐢 Slowest:{" "}
                    <span className="text-rose-400">
                      {performanceSummary?.slowestWorkflow ?? "-"}
                    </span>
                  </p>
                </div>
              </div>
            </section>
          </div>
        </section>

        <div className="grid grid-cols-1 gap-6 xl:grid-cols-[minmax(0,1fr)_360px]">
          <section className="min-w-0">
            <div className="mb-4 flex items-center justify-between gap-4">
              <div>
                <h2 className="text-lg font-semibold text-white">
                  {resolvedGraph.mode === "dag"
                    ? "Task DAG Graph"
                    : "Orchestration Graph"}
                </h2>

                <p className="mt-1 text-sm text-zinc-500">
                  {resolvedGraph.mode === "dag"
                    ? `${nodes.length} tasks - ${connections.length} dependencies${resolvedGraph.isDagStructure ? " (DAG)" : " (Linear)"}`
                    : `${nodes.length} workflows - ${connections.length} dependencies`}
                </p>
              </div>

              <div className="flex flex-wrap items-center gap-2">
                {workflows.length > 0 && (
                  <select
                    aria-label="Select workflow for DAG view"
                    className="rounded-lg border border-zinc-800 bg-[#111111] px-2.5 py-1.5 text-xs text-zinc-200"
                    value={selectedWorkflowId ?? ""}
                    onChange={(e) => {
                      const id = Number(e.target.value);
                      if (!Number.isNaN(id)) {
                        void handleSelectWorkflow(id);
                      }
                    }}
                  >
                    {workflows.map((wf) => (
                      <option key={wf.id} value={wf.id}>
                        #{wf.id} {wf.name}
                      </option>
                    ))}
                  </select>
                )}

                <div className="flex overflow-hidden rounded-lg border border-zinc-800 bg-[#111111] text-xs">
                  <button
                    type="button"
                    onClick={() => setGraphViewMode("overview")}
                    className={`px-3 py-1.5 transition ${
                      graphViewMode === "overview"
                        ? "bg-cyan-500/20 text-cyan-200"
                        : "text-zinc-400 hover:text-zinc-200"
                    }`}
                  >
                    Overview
                  </button>
                  <button
                    type="button"
                    onClick={() => setGraphViewMode("dag")}
                    disabled={workflowTasks.length === 0}
                    className={`px-3 py-1.5 transition disabled:opacity-40 ${
                      graphViewMode === "dag"
                        ? "bg-cyan-500/20 text-cyan-200"
                        : "text-zinc-400 hover:text-zinc-200"
                    }`}
                  >
                    Task DAG
                  </button>
                </div>

                <div className="hidden items-center gap-2 rounded-lg border border-zinc-800 bg-[#111111] px-3 py-2 text-xs text-zinc-400 sm:flex">
                  <span className="h-2 w-2 rounded-full bg-cyan-300" />
                  Streaming updates
                </div>
              </div>
            </div>

            <WorkflowGraph nodes={nodes} connections={connections} />
          </section>

          <aside className="min-w-0">
            <div className="mb-8">
              <h2 className="mb-6 text-2xl font-semibold">
                Execution Timeline
              </h2>

              <div className="max-h-[420px] overflow-y-auto space-y-4">
                {timeline.map((event, index) => (
                  <div
                    key={index}
                    className="rounded-xl border border-zinc-800 bg-zinc-900 p-4"
                  >
                    <p className="text-cyan-400">{event.taskName}</p>
                    <p className="mt-1 text-sm text-zinc-400">{event.status}</p>
                    <p className="mt-2 text-xs text-zinc-500">
                      {formatTimelineTime(event.timestamp)}
                    </p>
                  </div>
                ))}
              </div>
            </div>

            <div className="mb-8">
              <h2 className="mb-6 text-2xl font-semibold">
                Performance Leaderboard
              </h2>

              {performance.length === 0 ? (
                <p className="text-zinc-500">No completed workflows yet</p>
              ) : (
                <div className="space-y-3">
                  {performance.map((workflow, index) => (
                    <div
                      key={index}
                      className="
                      rounded-xl
                      border
                      border-zinc-800
                      bg-zinc-900
                      p-4
          "
                    >
                      <div
                        className="
                        flex
                        items-center
                        justify-between
                      "
                      >
                        <p
                          className="
                        text-sm
                        font-medium
                        text-zinc-200
                      "
                        >
                          {workflow.workflowName}
                        </p>

                        <span
                          className="
                        text-cyan-400
                        font-semibold
              "
                        >
                          {(workflow.durationMs / 1000).toFixed(2)}s
                        </span>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>

            <div className="mb-8">
              <h2 className="mb-6 text-2xl font-semibold">Workflow Health</h2>

              <div className="max-h-[260px] space-y-3 overflow-y-auto">
                {workflowHealth.map((workflow, index) => (
                  <div
                    key={index}
                    className="
            rounded-xl
            border
            border-zinc-800
            bg-zinc-900
            p-4
          "
                  >
                    <div
                      className="
              flex
              items-center
              justify-between
            "
                    >
                      <p
                        className="
                truncate
                text-sm
                font-medium
                text-zinc-100
              "
                      >
                        {workflow.workflowName}
                      </p>

                      <span
                        className={`
                text-xs
                font-bold
                ${getHealthColor(workflow.health)}
              `}
                      >
                        {workflow.health}
                      </span>
                    </div>

                    <p
                      className="
              mt-2
              text-xs
              text-zinc-500
            "
                    >
                      {workflow.reason}
                    </p>

                    <p
                      className="
              mt-2
              text-xs
              text-cyan-400
            "
                    >
                      {(workflow.durationMs / 1000).toFixed(2)} s
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

            <div className="h-[420px] overflow-hidden rounded-lg border border-zinc-800 bg-[#101010]">
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
