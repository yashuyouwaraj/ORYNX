import assert from "node:assert/strict";
import test from "node:test";

import {
  buildGrafanaTraceUrl,
  buildGrafanaWorkflowLogsUrl,
  createSafeApiErrorContext,
  generateTraceContext,
  sanitizeEndpointForLogging,
} from "../src/services/observability.ts";

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
