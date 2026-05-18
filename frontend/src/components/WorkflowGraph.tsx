"use client";

import type { GraphNode, GraphConnection } from "@/types/graph";

interface Props {
  nodes: GraphNode[];

  connections: GraphConnection[];
}

const getStatusColor = (status: string) => {
  switch (status) {
    case "RUNNING":
      return "#06b6d4";

    case "COMPLETED":
      return "#22c55e";

    case "FAILED":
      return "#ef4444";

    default:
      return "#eab308";
  }
};

export default function WorkflowGraph({ nodes, connections }: Props) {
  const getNodeById = (id: number) => {
    return nodes.find((node) => node.id === id);
  };

  return (
    <div
      className="relative w-full h-[700px] overflow-hidden rounded-3xl border border-zinc-800 bg-zinc-950
"
    >
      <svg
        className="
          absolute
          inset-0
          w-full
          h-full
        "
      >
        {connections.map((connection, index) => {
          const source = getNodeById(connection.source);

          const target = getNodeById(connection.target);

          if (!source || !target) {
            return null;
          }

          return (
            <g key={index}>
              <line
                x1={source.x + 110}
                y1={source.y + 50}
                x2={target.x + 110}
                y2={target.y + 50}
                stroke="#27272a"
                strokeWidth="3"
              />

              <line
                x1={source.x + 110}
                y1={source.y + 50}
                x2={target.x + 110}
                y2={target.y + 50}
                stroke="#06b6d4"
                strokeWidth="3"
                strokeDasharray="10"
              >
                <animate
                  attributeName="
                      stroke-dashoffset
                    "
                  from="100"
                  to="0"
                  dur="2s"
                  repeatCount="indefinite"
                />
              </line>
            </g>
          );
        })}
      </svg>

      {nodes.map((node) => (
        <div
          key={node.id}
          className="
            absolute
            w-56
            rounded-2xl
            border
            bg-zinc-900
            p-5
            shadow-lg
            transition-all
            duration-500
          "
          style={{
            left: node.x,
            top: node.y,
            borderColor: getStatusColor(node.status),
          }}
        >
          <div
            className="
            flex
            items-center
            justify-between
          "
          >
            <h3
              className="
              text-lg
              font-semibold
            "
            >
              {node.name}
            </h3>

            <span
              className="
                text-xs
              "
              style={{
                color: getStatusColor(node.status),
              }}
            >
              {node.status}
            </span>
          </div>

          <div
            className="
            mt-4
            h-2
            overflow-hidden
            rounded-full
            bg-zinc-800
          "
          >
            <div
              className="
                h-full
                animate-pulse
              "
              style={{
                backgroundColor: getStatusColor(node.status),
              }}
            />
          </div>
        </div>
      ))}
    </div>
  );
}
