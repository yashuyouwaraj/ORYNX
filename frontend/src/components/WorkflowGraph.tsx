"use client";

import type { GraphNode } from "@/types/graph";

interface Props {
  nodes: GraphNode[];
}

export default function WorkflowGraph({ nodes }: Props) {
  const getStatusColor = (status: string) => {
    switch (status) {
      case "RUNNING":
        return "border-cyan-500";

      case "COMPLETED":
        return "border-green-500";

      case "FAILED":
        return "border-red-500";

      default:
        return "border-yellow-500";
    }
  };

  return (
    <div className="relative h-[700px] w-full overflow-hidden rounded-3xl border border-zinc-800 bg-zinc-950">
      {nodes.map((node) => (
        <div
          key={node.id}
          className={`absolute w-56 rounded-2xl border ${getStatusColor(node.status)} bg-zinc-900 p-5 shadow-lg transition-all duration-500`}
          style={{
            left: node.x,
            top: node.y,
          }}
        >
          <div className="flex items-center justify-between">
            <h3 className="text-lg font-semibold">{node.name}</h3>

            <span className="text-xs text-cyan-400">{node.status}</span>
          </div>

          <div className="mt-4 h-2 overflow-hidden rounded-full bg-zinc-800">
            <div className="h-full animate-pulse bg-cyan-400" />
          </div>
        </div>
      ))}
    </div>
  );
}
