import type { Workflow } from "@/types/workflow";

import type { GraphNode, GraphConnection, WorkflowTaskNode } from "@/types/graph";

type TaskStatuses = Record<number, string>;

export const mapWorkflowsToGraph = (
  workflows: Workflow[] | null | undefined,
  taskStatuses: TaskStatuses,
): {
  nodes: GraphNode[];

  connections: GraphConnection[];
} => {
  const workflowList = Array.isArray(workflows) ? workflows : [];

  const nodes: GraphNode[] = workflowList.map((workflow, index) => ({
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

/**
 * Maps workflow tasks (DAG with explicit dependsOn edges, or legacy linear executionOrder)
 * into layered topological coordinates and multi-edge fan-out/fan-in connections.
 */
export const mapWorkflowTasksToDagGraph = (
  tasks: WorkflowTaskNode[] | null | undefined,
): {
  nodes: GraphNode[];
  connections: GraphConnection[];
} => {
  const taskList = Array.isArray(tasks) ? [...tasks] : [];
  if (taskList.length === 0) {
    return { nodes: [], connections: [] };
  }

  const hasExplicitDeps = taskList.some(
    (t) => Array.isArray(t.dependsOn) && t.dependsOn.length > 0,
  );

  if (!hasExplicitDeps) {
    taskList.sort((a, b) => (a.executionOrder ?? a.id) - (b.executionOrder ?? b.id));
  }

  const keyById = new Map<number, string>();
  const idByKey = new Map<string, number>();

  taskList.forEach((task, idx) => {
    const key =
      task.taskKey && task.taskKey.trim().length > 0
        ? task.taskKey.trim()
        : `task-${task.executionOrder ?? idx + 1}`;
    keyById.set(task.id, key);
    idByKey.set(key, task.id);
  });

  const depsByKey = new Map<string, string[]>();
  const connections: GraphConnection[] = [];

  taskList.forEach((task, idx) => {
    const key = keyById.get(task.id)!;
    let deps: string[];
    if (hasExplicitDeps) {
      deps = (task.dependsOn ?? []).filter((d) => idByKey.has(d) && d !== key);
    } else {
      deps = idx === 0 ? [] : [keyById.get(taskList[idx - 1].id)!];
    }
    depsByKey.set(key, deps);
    for (const depKey of deps) {
      const sourceId = idByKey.get(depKey);
      if (sourceId !== undefined) {
        connections.push({ source: sourceId, target: task.id });
      }
    }
  });

  // Compute topological depth (longest path from root) with cycle guard
  const depthByKey = new Map<string, number>();
  const visiting = new Set<string>();

  const computeDepth = (key: string): number => {
    const cached = depthByKey.get(key);
    if (cached !== undefined) {
      return cached;
    }
    if (visiting.has(key)) {
      return 0;
    }
    visiting.add(key);
    const deps = depsByKey.get(key) ?? [];
    let maxParent = -1;
    for (const dep of deps) {
      maxParent = Math.max(maxParent, computeDepth(dep));
    }
    visiting.delete(key);
    const depth = maxParent + 1;
    depthByKey.set(key, depth);
    return depth;
  };

  const laneCounters = new Map<number, number>();
  const nodes: GraphNode[] = taskList.map((task) => {
    const key = keyById.get(task.id)!;
    const depth = computeDepth(key);
    const lane = laneCounters.get(depth) ?? 0;
    laneCounters.set(depth, lane + 1);

    return {
      id: task.id,
      label: task.name,
      status: task.status ?? "PENDING",
      taskKey: key,
      taskType: task.taskType ?? "NOOP",
      retryCount: task.retryCount ?? 0,
      depth,
      x: 80 + depth * 320,
      y: 80 + lane * 168,
    };
  });

  return { nodes, connections };
};
