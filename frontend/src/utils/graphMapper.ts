import type { Workflow } from "@/types/workflow";

import type { GraphNode, GraphConnection } from "@/types/graph";

type TaskStatuses = Record<number, string>;

export const mapWorkflowsToGraph = (
  workflows: Workflow[],
  taskStatuses: TaskStatuses,
): {
  nodes: GraphNode[];

  connections: GraphConnection[];
} => {
  const nodes: GraphNode[] = workflows.map((workflow, index) => ({
    id: workflow.id,

    label: workflow.name,

    status: taskStatuses[workflow.id] || workflow.status,

    x: 120 + (index % 3) * 320,

    y: 120 + Math.floor(index / 3) * 220,
  }));

  const connections: GraphConnection[] = [];

  for (let i = 0; i < nodes.length - 1; i++) {
    connections.push({
      source: nodes[i].id,

      target: nodes[i + 1].id,
    });
  }

  return {
    nodes,

    connections,
  };
};
