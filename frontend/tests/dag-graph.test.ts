import test from "node:test";
import assert from "node:assert/strict";
import { mapWorkflowTasksToDagGraph, mapWorkflowsToGraph } from "../src/utils/graphMapper.ts";

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
