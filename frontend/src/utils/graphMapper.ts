import type { Workflow } from "@/types/workflow";

import type { GraphNode } from "@/types/graph";

export const mapWorkflowsToGraph = (workflows: Workflow[]): GraphNode[] => {
  return workflows.map((workflow, index) => ({
    id: workflow.id,

    name: workflow.name,

    status: workflow.status,

    x: 100 + (index % 3) * 320,

    y: 100 + Math.floor(index / 3) * 220,
  }));
};
