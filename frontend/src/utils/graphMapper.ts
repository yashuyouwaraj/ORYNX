import type { Workflow } from "@/types/workflow";

import type { GraphNode, GraphConnection } from "@/types/graph";

export const mapWorkflowsToGraph = (
  workflows: Workflow[],
): {
  nodes: GraphNode[];
  connections: GraphConnection[];
} => {
  const nodes: GraphNode[] = workflows.map((workflow, index) => ({
    id: workflow.id,

    name: workflow.name,

    status: workflow.status,

    x: 100 + (index % 3) * 320,

    y: 100 + Math.floor(index / 3) * 220,
  }));

  const connections: GraphConnection[] = []

  for(let i =0 ; i< workflows.length-1; i++){
    connections.push({
      source: workflows[i].id,

      target: workflows[i+1].id,
    })
  }

  return {
    nodes,
    connections
  };
};
