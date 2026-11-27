import test from "node:test";
import assert from "node:assert/strict";
import * as nodeModule from "node:module";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const registerHooks = (
  nodeModule as unknown as {
    registerHooks?: (hooks: {
      resolve: (
        specifier: string,
        context: unknown,
        nextResolve: (specifier: string, context: unknown) => unknown,
      ) => unknown;
    }) => void;
  }
).registerHooks;

registerHooks?.({
  resolve(specifier, context, nextResolve) {
    if (specifier.startsWith("@/")) {
      const target = path.resolve(
        path.dirname(fileURLToPath(import.meta.url)),
        "..",
        "src",
        `${specifier.slice(2)}.ts`,
      );
      return { shortCircuit: true, url: pathToFileURL(target).href };
    }
    return nextResolve(specifier, context);
  },
});

import {
  mapWorkflowTasksToDagGraph,
  mapWorkflowsToGraph,
  resolveWorkflowGraphView,
} from "../src/utils/graphMapper.ts";

const { getWorkflowTasks } = await import("../src/services/api.ts");

test("mapWorkflowTasksToDagGraph lays out diamond DAG A -> {B, C} -> D with fan-out and fan-in edges", () => {
  const result = mapWorkflowTasksToDagGraph([
    { id: 1, taskKey: "A", name: "Extract", taskType: "NOOP", status: "COMPLETED", executionOrder: 1, dependsOn: [] },
    { id: 2, taskKey: "B", name: "Branch Left", taskType: "ECHO", status: "RUNNING", executionOrder: 2, dependsOn: ["A"], retryCount: 1 },
    { id: 3, taskKey: "C", name: "Branch Right", taskType: "TRANSFORM", status: "COMPLETED", executionOrder: 3, dependsOn: ["A"] },
    { id: 4, taskKey: "D", name: "Join", taskType: "TRANSFORM", status: "PENDING", executionOrder: 4, dependsOn: ["B", "C"] },
  ]);

  assert.equal(result.nodes.length, 4);
  assert.equal(result.connections.length, 4);

  const byKey = new Map(result.nodes.map((n) => [n.taskKey, n]));
  assert.equal(byKey.get("A")?.depth, 0);
  assert.equal(byKey.get("B")?.depth, 1);
  assert.equal(byKey.get("C")?.depth, 1);
  assert.equal(byKey.get("D")?.depth, 2);

  // B and C share column x, differ on lane y
  assert.equal(byKey.get("B")?.x, byKey.get("C")?.x);
  assert.notEqual(byKey.get("B")?.y, byKey.get("C")?.y);
  assert.equal(byKey.get("B")?.retryCount, 1);

  // Verify exact fan-out and fan-in edges
  const edges = new Set(result.connections.map((c) => `${c.source}->${c.target}`));
  assert.ok(edges.has("1->2"));
  assert.ok(edges.has("1->3"));
  assert.ok(edges.has("2->4"));
  assert.ok(edges.has("3->4"));
});

test("mapWorkflowTasksToDagGraph preserves legacy linear executionOrder chain when dependsOn is omitted", () => {
  const result = mapWorkflowTasksToDagGraph([
    { id: 10, name: "Step 2", executionOrder: 2, status: "PENDING" },
    { id: 11, name: "Step 1", executionOrder: 1, status: "COMPLETED" },
    { id: 12, name: "Step 3", executionOrder: 3, status: "SKIPPED" },
  ]);

  assert.equal(result.nodes.length, 3);
  assert.deepEqual(
    result.connections.map((c) => `${c.source}->${c.target}`),
    ["11->10", "10->12"],
  );
  assert.equal(result.nodes[2].status, "SKIPPED");
});

test("mapWorkflowsToGraph remains backward compatible for workflow list view", () => {
  const result = mapWorkflowsToGraph(
    [
      { id: 1, name: "WF-1", goal: "g1", status: "COMPLETED" },
      { id: 2, name: "WF-2", goal: "g2", status: "RUNNING" },
    ],
    {},
  );
  assert.equal(result.nodes.length, 2);
  assert.equal(result.connections.length, 1);
});

test("resolveWorkflowGraphView switches between DAG and overview modes, merges statuses, and handles malformed deps", () => {
  const workflows = [
    { id: 1, name: "Diamond-WF", goal: "test", status: "RUNNING" },
  ];
  const tasks = [
    { id: 101, taskKey: "A", name: "Root", taskType: "NOOP", status: "COMPLETED", executionOrder: 1, dependsOn: [] },
    { id: 102, taskKey: "B", name: "Left", taskType: "ECHO", status: "PENDING", executionOrder: 2, dependsOn: ["A", "NONEXISTENT", ""] },
    { id: 103, taskKey: "C", name: "Right", taskType: "TRANSFORM", status: "FAILED", executionOrder: 3, dependsOn: ["A"] },
    { id: 104, taskKey: "D", name: "Join", taskType: "NOOP", status: "SKIPPED", executionOrder: 4, dependsOn: ["B", "C"] },
  ];

  // Live task status override for B -> RETRYING
  const dagView = resolveWorkflowGraphView("dag", workflows, tasks, { 102: "RETRYING" });
  assert.equal(dagView.mode, "dag");
  assert.equal(dagView.isDagStructure, true);
  assert.equal(dagView.nodes.length, 4);
  assert.equal(dagView.connections.length, 4); // NONEXISTENT & empty string gracefully ignored
  const nodeB = dagView.nodes.find((n) => n.taskKey === "B");
  const nodeD = dagView.nodes.find((n) => n.taskKey === "D");
  assert.equal(nodeB?.status, "RETRYING");
  assert.equal(nodeD?.status, "SKIPPED");

  // Overview mode falls back to workflow-level graph
  const overview = resolveWorkflowGraphView("overview", workflows, tasks, {});
  assert.equal(overview.mode, "overview");
  assert.equal(overview.nodes.length, 1);
});

test("getWorkflowTasks unwraps PageEnvelope response from orchestrator tasks API", async () => {
  const originalFetch = globalThis.fetch;
  try {
    globalThis.fetch = (async () =>
      new Response(
        JSON.stringify({
          content: [
            { id: 1, taskKey: "A", name: "Root", status: "COMPLETED", executionOrder: 1, dependsOn: [] },
            { id: 2, taskKey: "B", name: "Child", status: "CANCELLED", executionOrder: 2, dependsOn: ["A"] },
          ],
          totalElements: 2,
        }),
        { status: 200, headers: { "Content-Type": "application/json" } },
      )) as typeof fetch;

    const tasks = await getWorkflowTasks(42, "test-jwt");
    assert.equal(tasks.length, 2);
    assert.equal(tasks[0].taskKey, "A");
    assert.equal(tasks[1].status, "CANCELLED");
  } finally {
    globalThis.fetch = originalFetch;
  }
});
