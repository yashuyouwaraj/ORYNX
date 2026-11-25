import assert from "node:assert/strict";
import * as nodeModule from "node:module";
import path from "node:path";
import test from "node:test";
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
  buildGrafanaTraceUrl,
  buildGrafanaWorkflowLogsUrl,
  createSafeApiErrorContext,
  generateTraceContext,
  sanitizeEndpointForLogging,
} from "../src/services/observability.ts";

const {
  getAnalytics,
  getPerformance,
  getPerformanceSummary,
  getTimelinePage,
  getWorkflowHealth,
  getWorkflowsPage,
} = await import("../src/services/api.ts");

test("generateTraceContext produces valid W3C traceparent and correlationId", () => {
  const ctx = generateTraceContext();
  assert.match(ctx.traceId, /^[0-9a-f]{32}$/);
  assert.match(ctx.spanId, /^[0-9a-f]{16}$/);
  assert.equal(ctx.traceparent, `00-${ctx.traceId}-${ctx.spanId}-01`);
  assert.ok(ctx.correlationId.length >= 16);
});

test("sanitizeEndpointForLogging strips query parameters to avoid leaking secrets", () => {
  const sanitized = sanitizeEndpointForLogging(
    "http://localhost:8080/api/v1/workflows?token=secret123&page=0",
  );
  assert.equal(sanitized, "http://localhost:8080/api/v1/workflows");
});

test("createSafeApiErrorContext captures status, correlationId, traceId without secrets", () => {
  const ctx = generateTraceContext();
  const errCtx = createSafeApiErrorContext(
    "http://localhost:8080/api/v1/workflows?api_key=abc",
    503,
    ctx,
  );
  assert.equal(errCtx.endpoint, "http://localhost:8080/api/v1/workflows");
  assert.equal(errCtx.status, 503);
  assert.equal(errCtx.correlationId, ctx.correlationId);
  assert.equal(errCtx.traceId, ctx.traceId);
});

test("Grafana investigation URLs degrade gracefully when NEXT_PUBLIC_GRAFANA_URL is unset or invalid", () => {
  const prev = process.env.NEXT_PUBLIC_GRAFANA_URL;
  delete process.env.NEXT_PUBLIC_GRAFANA_URL;
  assert.equal(buildGrafanaTraceUrl("0123456789abcdef0123456789abcdef"), null);
  assert.equal(buildGrafanaWorkflowLogsUrl(42), null);

  process.env.NEXT_PUBLIC_GRAFANA_URL = "http://127.0.0.1:3001/";
  const traceUrl = buildGrafanaTraceUrl("0123456789abcdef0123456789abcdef");
  assert.ok(traceUrl?.startsWith("http://127.0.0.1:3001/explore?left="));
  assert.equal(buildGrafanaTraceUrl("not-a-valid-trace-id"), null);

  const logsUrl = buildGrafanaWorkflowLogsUrl(42);
  assert.ok(logsUrl?.startsWith("http://127.0.0.1:3001/explore?left="));
  assert.equal(buildGrafanaWorkflowLogsUrl(-1), null);

  if (prev !== undefined) {
    process.env.NEXT_PUBLIC_GRAFANA_URL = prev;
  } else {
    delete process.env.NEXT_PUBLIC_GRAFANA_URL;
  }
});

test("all API functions propagate traceparent, X-Request-ID, X-Correlation-ID and emit safe correlation errors", async () => {
  const originalFetch = globalThis.fetch;
  const capturedHeaders: Record<string, string>[] = [];

  try {
    // 1. Success path across all endpoints (including performance & workflow health)
    globalThis.fetch = (async (_input: RequestInfo | URL, init?: RequestInit) => {
      capturedHeaders.push((init?.headers ?? {}) as Record<string, string>);
      return new Response(JSON.stringify({ content: [], ok: true }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });
    }) as typeof fetch;

    await getWorkflowsPage("secret-jwt-token-123");
    await getAnalytics("secret-jwt-token-123");
    await getTimelinePage(42, "secret-jwt-token-123");
    await getPerformance("secret-jwt-token-123");
    await getPerformanceSummary("secret-jwt-token-123");
    await getWorkflowHealth("secret-jwt-token-123");

    assert.equal(capturedHeaders.length, 6);
    for (const h of capturedHeaders) {
      assert.match(h["traceparent"], /^00-[0-9a-f]{32}-[0-9a-f]{16}-01$/);
      assert.ok(h["X-Request-ID"] && h["X-Request-ID"].length >= 16);
      assert.equal(h["X-Correlation-ID"], h["X-Request-ID"]);
      assert.equal(h["Authorization"], "Bearer secret-jwt-token-123");
    }

    // 2. Error path across performance & workflow health endpoints: includes status, correlationId, traceId, never leaks token
    globalThis.fetch = (async () =>
      new Response("Internal Server Error", { status: 503 })) as typeof fetch;

    const calls: Array<{ name: string; fn: () => Promise<unknown> }> = [
      { name: "getPerformance", fn: () => getPerformance("super-secret-bearer-token") },
      { name: "getPerformanceSummary", fn: () => getPerformanceSummary("super-secret-bearer-token") },
      { name: "getWorkflowHealth", fn: () => getWorkflowHealth("super-secret-bearer-token") },
    ];

    for (const c of calls) {
      await assert.rejects(c.fn, (err: unknown) => {
        assert.ok(err instanceof Error);
        assert.match(err.message, /status=503/);
        assert.match(err.message, /correlationId=[0-9a-f-]+/i);
        assert.match(err.message, /traceId=[0-9a-f]{32}/);
        assert.ok(!err.message.includes("super-secret-bearer-token"), `${c.name} leaked bearer token`);
        return true;
      });
    }
  } finally {
    globalThis.fetch = originalFetch;
  }
});
