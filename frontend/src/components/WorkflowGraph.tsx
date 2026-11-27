"use client";

import type { GraphNode, GraphConnection } from "@/types/graph";

interface Props {
  nodes: GraphNode[];

  connections: GraphConnection[];
}

const NODE_WIDTH = 240;
const NODE_HEIGHT = 112;
const CANVAS_PADDING = 96;

const getStatusColor = (status?: string) => {
  switch (status) {
    case "RUNNING":
      return "#06b6d4";

    case "COMPLETED":
      return "#22c55e";

    case "FAILED":
      return "#ef4444";

    case "RETRYING":
      return "#f97316";

    case "SKIPPED":
      return "#71717a";

    case "CANCELLED":
      return "#a855f7";

    default:
      return "#eab308";
  }
};

const getStatusClasses = (status?: string) => {
  switch (status) {
    case "RUNNING":
      return "border-cyan-400/70 bg-cyan-400/10 shadow-[0_0_30px_rgba(34,211,238,0.18)]";

    case "COMPLETED":
      return "border-emerald-400/60 bg-emerald-400/10 shadow-[0_0_24px_rgba(52,211,153,0.14)]";

    case "FAILED":
      return "border-rose-400/60 bg-rose-400/10 shadow-[0_0_24px_rgba(251,113,133,0.14)]";

    case "RETRYING":
      return "border-orange-400/60 bg-orange-400/10 shadow-[0_0_24px_rgba(251,146,60,0.14)]";

    case "SKIPPED":
      return "border-zinc-600/60 bg-zinc-800/40 opacity-75";

    case "CANCELLED":
      return "border-purple-400/60 bg-purple-400/10";

    default:
      return "border-amber-300/50 bg-amber-300/10 shadow-[0_0_20px_rgba(252,211,77,0.1)]";
  }
};

const getProgress = (status?: string) => {
  switch (status) {
    case "COMPLETED":
      return "100%";

    case "RUNNING":
    case "RETRYING":
      return "58%";

    case "FAILED":
    case "CANCELLED":
      return "100%";

    case "SKIPPED":
      return "0%";

    default:
      return "28%";
  }
};

export default function WorkflowGraph({ nodes, connections }: Props) {
  const nodeMap = new Map<number, GraphNode>();
  for (const n of nodes) {
    nodeMap.set(n.id, n);
  }
  const getNodeById = (id: number) => nodeMap.get(id);

  const maxNodeX = Math.max(...nodes.map((node) => node.x));
  const maxNodeY = Math.max(...nodes.map((node) => node.y));
  const canvasWidth = maxNodeX + NODE_WIDTH + CANVAS_PADDING;
  const canvasHeight = maxNodeY + NODE_HEIGHT + CANVAS_PADDING;

  if (nodes.length === 0) {
    return (
      <div className="relative flex h-[560px] w-full items-center justify-center overflow-hidden rounded-lg border border-zinc-800 bg-[#101010]">
        <div className="absolute inset-0 bg-[linear-gradient(to_right,rgba(255,255,255,0.045)_1px,transparent_1px),linear-gradient(to_bottom,rgba(255,255,255,0.045)_1px,transparent_1px)] bg-[size:36px_36px]" />
        <div className="relative text-center">
          <div className="mx-auto mb-4 h-2 w-24 rounded-full bg-zinc-800" />
          <h3 className="text-lg font-semibold text-zinc-100">No workflows yet</h3>
          <p className="mt-2 text-sm text-zinc-500">New orchestration runs will appear here automatically.</p>
        </div>
      </div>
    );
  }

  return (
    <div className="h-[620px] w-full overflow-auto rounded-lg border border-zinc-800 bg-[#101010]">
      <div
        className="relative min-h-full min-w-full"
        style={{
          height: canvasHeight,
          width: canvasWidth,
        }}
      >
        <div className="absolute inset-0 bg-[linear-gradient(to_right,rgba(255,255,255,0.05)_1px,transparent_1px),linear-gradient(to_bottom,rgba(255,255,255,0.05)_1px,transparent_1px)] bg-[size:36px_36px]" />
        <div className="absolute inset-0 bg-[radial-gradient(circle_at_30%_20%,rgba(6,182,212,0.12),transparent_32%),radial-gradient(circle_at_75%_65%,rgba(34,197,94,0.08),transparent_30%)]" />

        <svg className="absolute inset-0 h-full w-full">
          <defs>
            <marker
              id="workflow-arrow"
              markerHeight="8"
              markerWidth="8"
              orient="auto"
              refX="7"
              refY="4"
              viewBox="0 0 8 8"
            >
              <path d="M0,0 L8,4 L0,8 Z" fill="#52525b" />
            </marker>
          </defs>

          {connections.map((connection, index) => {
            const source = getNodeById(connection.source);

            const target = getNodeById(connection.target);

            if (!source || !target) {
              return null;
            }

            const startX = source.x + NODE_WIDTH / 2;
            const startY = source.y + NODE_HEIGHT / 2;
            const endX = target.x + NODE_WIDTH / 2;
            const endY = target.y + NODE_HEIGHT / 2;
            const curveOffset = Math.max(80, Math.abs(endX - startX) / 2);
            const path = `M ${startX} ${startY} C ${startX + curveOffset} ${startY}, ${endX - curveOffset} ${endY}, ${endX} ${endY}`;

            return (
              <g key={index}>
                <path
                  d={path}
                  fill="none"
                  markerEnd="url(#workflow-arrow)"
                  stroke="#3f3f46"
                  strokeLinecap="round"
                  strokeWidth="2"
                />

                <path
                  d={path}
                  fill="none"
                  stroke="#06b6d4"
                  strokeLinecap="round"
                  strokeWidth="2"
                  strokeDasharray="8 12"
                >
                  <animate
                    attributeName="stroke-dashoffset"
                    from="80"
                    to="0"
                    dur="2.4s"
                    repeatCount="indefinite"
                  />
                </path>
              </g>
            );
          })}
        </svg>

        {nodes.map((node) => (
          <div
            key={node.id}
            className={[
              "absolute w-60 rounded-lg border p-4 backdrop-blur transition duration-300",
              getStatusClasses(node.status),
            ].join(" ")}
            style={{
              left: node.x,
              top: node.y,
              borderColor: getStatusColor(node.status),
            }}
          >
            <div className="flex items-start justify-between gap-4">
              <h3 className="min-w-0 truncate text-sm font-semibold text-zinc-50">
                {node.label}
              </h3>

              <span
                className="shrink-0 rounded-full border border-white/10 bg-black/30 px-2 py-1 text-[10px] font-semibold uppercase tracking-wide"
                style={{
                  color: getStatusColor(node.status),
                }}
              >
                {node.status ?? "PENDING"}
              </span>
            </div>

            <div className="mt-4 flex items-center justify-between gap-2 text-xs text-zinc-400">
              <div className="flex items-center gap-2 truncate">
                <span
                  className="h-2 w-2 shrink-0 rounded-full"
                  style={{
                    backgroundColor: getStatusColor(node.status),
                  }}
                />
                <span className="truncate">
                  {node.taskKey ? `${node.taskKey}${node.taskType ? ` · ${node.taskType}` : ""}` : `Workflow #${node.id}`}
                </span>
              </div>
              {node.retryCount && node.retryCount > 0 ? (
                <span className="shrink-0 rounded bg-orange-500/20 px-1.5 py-0.5 text-[10px] font-medium text-orange-300">
                  retries: {node.retryCount}
                </span>
              ) : null}
            </div>

            <div className="mt-4 h-1.5 overflow-hidden rounded-full bg-zinc-800">
              <div
                className="h-full rounded-full transition-all duration-500"
                style={{
                  backgroundColor: getStatusColor(node.status),
                  width: getProgress(node.status),
                }}
              />
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
